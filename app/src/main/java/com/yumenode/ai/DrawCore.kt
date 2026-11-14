// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.graphics.Bitmap
import com.yumenode.ai.draw.SdCppEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Shared entry point for the drawing engine: both the app UI and the "API server" drawing
 * interface generate images from here.
 *
 * Why this layer (same reasoning as [ChatCore]): sd.cpp's handle was originally a private field
 * of DrawPage, freed when the page/Activity is destroyed; but the API service must keep generating
 * after the app goes to background, so the handle and params must live on a process-level singleton.
 * DrawPage just registers the handle on load ([attach]); parameter changes sync a snapshot of
 * the already-parsed params ([updateParams]); view rebuilds or theme changes don't affect it here.
 *
 * Why not read UI controls directly: API request threads aren't the main thread, and EditText
 * can only be read on the main thread; plus after Activity is destroyed, control state shouldn't
 * be depended on anyway. So this stores "values", not "controls".
 *
 * Serialization: one image takes tens of seconds to minutes; the local engine runs one task at a time.
 * [generate] takes [lock] and exposes [busy]; the UI reports its own status via [uiBusy],
 * and the two sides avoid each other (UI uses DrawPage.isBusy(), API checks busy/apiBusy then returns 429).
 */
object DrawCore {

    /** Parameter snapshot (all "already-parsed final values", no UI dependency) */
    class Params(
        val steps: Int = 20,
        val cfg: Float = 7.0f,
        val width: Int = 512,
        val height: Int = 512,
        val negative: String = "",
        val sampler: SdCppEngine.Sampler = SdCppEngine.Sampler.EULER_A,
        val scheduler: SdCppEngine.Scheduler = SdCppEngine.Scheduler.DISCRETE,
        val loraPath: String? = null,
        val loraScale: Float = 1.0f,
    )

    class Result(val bitmap: Bitmap, val seed: Long)

    @Volatile private var handle = 0L

    /** Currently loaded drawing model name (used as id by /v1/models and status display) */
    @Volatile var modelName: String? = null
        private set

    /** Actual backend used (CPU / Vulkan0…), display only */
    @Volatile var backend: String? = null
        private set

    /** Parameter snapshot; synced by UI on every param change / model load */
    @Volatile private var params = Params()

    /** API is generating */
    @Volatile var busy = false
        private set

    /** UI is generating (reported by DrawPage) — API side avoids it, don't fight the UI for the same handle */
    @Volatile var uiBusy = false

    @Volatile private var cancelFlag = false

    private val lock = Mutex()

    /** Registers handle. **Only stores the reference, does not take ownership** — DrawPage still frees the handle. */
    fun attach(h: Long, name: String?, backendName: String? = null) {
        handle = h
        modelName = name
        backend = backendName
    }

    /** Syncs parameter snapshot (called from main thread) */
    fun updateParams(p: Params) {
        params = p
    }

    /** Clears reference only, does not free (free is DrawPage's responsibility, and **must be called first**, otherwise the API would grab an already-freed handle) */
    fun release() {
        handle = 0L
        modelName = null
        backend = null
    }

    fun isReady(): Boolean = handle != 0L

    fun requestCancel() {
        cancelFlag = true
        val h = handle
        if (h != 0L) runCatching { SdCppEngine.nativeCancel(h) }
    }

    /**
     * Generates (for API use). Fields not passed fall back to current UI settings.
     *
     * `n` images are generated **in one lock hold** in a row: otherwise a gap appears between two,
     * and the UI could sneak in and grab the same handle by tapping "generate".
     *
     * Failing to get the lock throws an exception (upper layer returns 429) — queueing is pointless;
     * one image takes minutes.
     */
    suspend fun generate(
        prompt: String,
        n: Int = 1,
        seed: Long = -1L,
        steps: Int? = null,
        cfg: Float? = null,
        width: Int? = null,
        height: Int? = null,
        negative: String? = null,
        sampler: SdCppEngine.Sampler? = null,
        scheduler: SdCppEngine.Scheduler? = null,
        onStep: ((Int, Int) -> Unit)? = null,
        // ---- Native hires fix (sd.cpp internal) ----
        /** Hires scale multiplier (2 = 2x). 0 or <=1 means no upscaling. */
        hiresScale: Float = 0f,
        /** Second-round denoising strength (0.05–0.99). */
        hiresDenoise: Float = 0.7f,
        /** Hires upscaler. */
        hiresUpscaler: SdCppEngine.HiresUpscaler = SdCppEngine.HiresUpscaler.LATENT,
        // ---- VAE tiling (block encode/decode, saves memory) ----
        /** Whether VAE tiling is enabled. Large images on phone eat up hundreds of MB; after tiling the peak only depends on tile size. */
        vaeTiling: Boolean = false,
        /** Tile size in pixels. */
        vaeTileSize: Int = 512,
        /** Overlap between tiles (0–0.5), avoids seams. */
        vaeTileOverlap: Float = 0.25f,
    ): List<Result> {
        if (!lock.tryLock()) throw IllegalStateException("绘图引擎正忙")
        busy = true
        cancelFlag = false
        try {
            val p = params
            val h = handle
            if (h == 0L) throw IllegalStateException("未加载绘图模型")

            // sd.cpp requires multiples of 64; clamp to 64~2048 to guard against typos
            val w = (width ?: p.width).let { (it / 64) * 64 }.coerceIn(64, 2048)
            val ht = (height ?: p.height).let { (it / 64) * 64 }.coerceIn(64, 2048)
            val st = (steps ?: p.steps).coerceIn(1, 150)
            val cf = (cfg ?: p.cfg).coerceIn(1f, 30f)

            // Native hires fix: target size = base size × hiresScale (aligned to multiples of 64)
            val useHires = hiresScale > 1.01f
            val targetW = if (useHires) ((w * hiresScale).toInt() / 64 * 64).coerceIn(64, 2048) else w
            val targetH = if (useHires) ((ht * hiresScale).toInt() / 64 * 64).coerceIn(64, 2048) else ht

            val out = ArrayList<Result>(n)
            for (i in 0 until n) {
                if (cancelFlag) break
                // If seed given, make image i = seed+i, so n>1 doesn't produce the same image
                val useSeed = if (seed < 0) System.currentTimeMillis() % 1_000_000_000L else seed + i
                val bmp = withContext(Dispatchers.IO) {
                    SdCppEngine.render(
                        handle = h,
                        prompt = prompt,
                        negative = negative ?: p.negative,
                        loraPath = p.loraPath,
                        loraScale = p.loraScale,
                        width = w,
                        height = ht,
                        steps = st,
                        cfg = cf,
                        seed = useSeed,
                        sampler = sampler ?: p.sampler,
                        scheduler = scheduler ?: p.scheduler,
                        cb = { cur, total -> onStep?.invoke(cur, total) },
                        hiresEnabled = useHires,
                        hiresTargetWidth = targetW,
                        hiresTargetHeight = targetH,
                        hiresSteps = st,
                        hiresDenoiseStrength = hiresDenoise.coerceIn(0.05f, 0.99f),
                        hiresUpscaler = hiresUpscaler,
                        vaeTilingEnabled = vaeTiling,
                        vaeTileSize = vaeTileSize,
                        vaeTileOverlap = vaeTileOverlap.coerceIn(0f, 0.5f),
                    )
                } ?: throw IllegalStateException("Generation failed (engine returned empty bitmap)")
                out.add(Result(bmp, useSeed))
            }
            if (out.isEmpty()) throw IllegalStateException("Cancelled")
            return out
        } finally {
            busy = false
            lock.unlock()
        }
    }
}
