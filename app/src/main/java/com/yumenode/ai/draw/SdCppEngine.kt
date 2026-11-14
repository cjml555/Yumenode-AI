// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai.draw

import android.graphics.Bitmap
import com.yumenode.ai.InferConfig

/**
 * Yumenode AI self-built stable-diffusion.cpp (GGUF) engine.
 *
 * Compared with llmedge, this directly controls: thread count, sampler, scheduler, LoRA, quantization type, cancellation.
 */
object SdCppEngine {

    init {
        // Depends on libc++_shared.so (provided by jniLibs) and libyumenode_sd.so (contains sd.cpp + ggml)
        System.loadLibrary("c++_shared")
        System.loadLibrary("yumenode_sd")
    }

    /** Sampler (corresponds to sd.cpp enum sample_method_t) */
    enum class Sampler(val code: Int, val label: String) {
        EULER(0, "Euler"),
        EULER_A(1, "Euler a"),
        HEUN(2, "Heun"),
        DPM2(3, "DPM2"),
        DPMPP2S_A(4, "DPM++ 2S a"),
        DPMPP2M(5, "DPM++ 2M"),
        DPMPP2M_V2(6, "DPM++ 2M v2"),
        IPNDM(7, "IPNDM"),
        IPNDM_V(8, "IPNDM V"),
        LCM(9, "LCM"),
        DDIM_TRAILING(10, "DDIM"),
        TCD(11, "TCD"),
        EULER_CFG_PP(15, "Euler CFG++"),
        EULER_A_CFG_PP(16, "Euler a CFG++"),

        // The following 7 are already implemented in sd.cpp (see dispatch in runtime/denoiser.hpp); previously just not exposed in UI.
        // Always append at the end: the dropdown is generated in declaration order, and old user preferences store indices — inserting in the middle would misalign them.
        RES_MULTISTEP(12, "Res Multistep"),
        RES_2S(13, "Res 2S"),
        ER_SDE(14, "ER SDE"),
        EULER_GE(17, "Euler GE"),
        DPMPP2M_SDE(18, "DPM++ 2M SDE"),
        DPMPP2M_SDE_BT(19, "DPM++ 2M SDE B&T"),
        LMS(20, "LMS"),
    }

    /** Scheduler (corresponds to sd.cpp enum scheduler_t) */
    enum class Scheduler(val code: Int, val label: String) {
        DISCRETE(0, "Discrete"),
        KARRAS(1, "Karras"),
        EXPONENTIAL(2, "Exponential"),
        AYS(3, "AYS"),
        SGM_UNIFORM(5, "SGM Uniform"),
        SIMPLE(6, "Simple"),
        SMOOTHSTEP(7, "Smoothstep"),
        KL_OPTIMAL(8, "KL Optimal"),
        LCM(9, "LCM"),
        BETA(15, "Beta"),
    }

    /** Weight type (corresponds to sd.cpp enum sd_type_t); KEEP = keep the model as-is */
    const val WTYPE_KEEP = -1
    const val WTYPE_F32 = 0
    const val WTYPE_F16 = 1
    const val WTYPE_Q4_0 = 2
    const val WTYPE_Q4_1 = 3
    const val WTYPE_Q5_0 = 6
    const val WTYPE_Q5_1 = 7
    const val WTYPE_Q8_0 = 8

    fun interface StepCallback {
        fun onStep(step: Int, steps: Int)
    }

    /** Generation preview: each step callbacks a low-res thumbnail (RGB888, row-major, top-left origin) */
    fun interface PreviewCallback {
        fun onPreview(step: Int, width: Int, height: Int, rgb: ByteArray)
    }

    external fun nativeInfo(): String

    /** Full parameters of the most recent new_sd_ctx (for diagnosing load failures) */
    external fun nativeLastParams(): String

    /** slots length = SLOT_KEYS.size; elements may be null (that slot is not filled). */
    external fun nativeCreate(
        modelPath: String,
        vaePath: String?,
        nThreads: Int,
        wtype: Int,
        flashAttn: Boolean,
        backend: String?,
        slots: Array<String?>?,
        diskWeights: Boolean,
    ): Long

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        negative: String,
        loraPath: String?,
        loraScale: Float,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        sampleMethod: Int,
        scheduler: Int,
        cb: StepCallback?,
        pcb: PreviewCallback?,
        initData: ByteArray?,
        initWidth: Int,
        initHeight: Int,
        strength: Float,
        hiresEnabled: Boolean,
        hiresTargetWidth: Int,
        hiresTargetHeight: Int,
        hiresSteps: Int,
        hiresDenoiseStrength: Float,
        hiresUpscaler: Int,
        vaeTilingEnabled: Boolean,
        vaeTileSize: Int,
        vaeTileOverlap: Float,
    ): IntArray?

    /** Native hires fix upscaler (sd.cpp's sd_hires_upscaler_t). */
    enum class HiresUpscaler(val code: Int, val label: String) {
        /** Latent-space upscale (cheapest compute, sd.cpp default). */
        LATENT(1, "Latent"),

        /** Latent-space nearest-neighbor upscale. */
        LATENT_NEAREST(2, "Latent nearest"),

        /** Latent-space precise nearest-neighbor upscale. */
        LATENT_NEAREST_EXACT(3, "Latent nearest exact"),

        /** Latent-space bilinear + anti-aliased. */
        LATENT_ANTIALIASED(4, "Latent antialiased"),

        /** Latent-space bicubic. */
        LATENT_BICUBIC(5, "Latent bicubic"),

        /** Latent-space bicubic + anti-aliased. */
        LATENT_BICUBIC_ANTIALIASED(6, "Latent bicubic antialiased"),

        /** Pixel-space Lanczos (decode VAE then upscale — highest quality, slowest). */
        LANCZOS(7, "Lanczos"),

        /** Pixel-space nearest-neighbor. */
        NEAREST(8, "Nearest"),
    }

    external fun nativeCancel(handle: Long)

    /** ggml backend device list: each line "name\tdescription" (e.g. "Vulkan0\tAdreno (TM) 650"). */
    external fun nativeListDevices(): String

    external fun nativeFree(handle: Long)

    fun info(): String = runCatching { nativeInfo() }.getOrDefault("(n/a)")

    /** Parameters used by the most recent new_sd_ctx (multiline text) */
    fun lastParams(): String = runCatching { nativeLastParams() }.getOrDefault("")

    /** Backend device list (name -> description); returns empty table if unavailable. */
    fun listDevices(): List<Pair<String, String>> =
        runCatching { nativeListDevices() }.getOrDefault("")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val i = line.indexOf('\t')
                if (i < 0) line to "" else line.substring(0, i) to line.substring(i + 1)
            }
            .toList()

    /** Whether an available Vulkan (GPU) device exists; returns its name if so (e.g. "Vulkan0"). */
    fun vulkanDevice(): String? =
        listDevices().firstOrNull { it.first.contains("vulkan", true) }?.first

    /** Loads model and returns handle; 0 means failure.
     *
     *  flashAttn: FlashAttention for CLIP and UNet. Measured: leaving it off slows each step by ~28%, on by default.
     *  backend: ggml backend/device name ("CPU" / "Vulkan0"); null = let sd.cpp pick automatically.
     */
    fun create(
        modelPath: String,
        vaePath: String?,
        nThreads: Int = InferConfig.DEFAULT,
        wtype: Int = WTYPE_KEEP,
        flashAttn: Boolean = true,
        backend: String? = null,
        slots: Array<String?>? = null,
        diskWeights: Boolean = true,
    ): Long = nativeCreate(modelPath, vaePath, nThreads, wtype, flashAttn, backend, slots, diskWeights)

    /**
     * Slot order (corresponds one-to-one with SlotIndex in JNI; sd.cpp's sd_ctx_params_t path fields).
     * Single-file models only use "model"; multi-file models fill diffusion / clip_l / t5xxl / llm / vae etc. by family.
     * "taesd" is optional small VAE weights (replaces VAE encode/decode, faster CPU rendering) — appended at the end,
     * staying in sync with JNI's SlotIndex.
     */
    val SLOT_KEYS = arrayOf(
        "model", "diffusion", "high_noise", "uncond",
        "clip_l", "clip_g", "clip_vision", "t5xxl",
        "llm", "llm_vision", "vae", "audio_vae", "taesd",
    )

    /** Converts slot table (key -> absolute path) into the fixed-size array JNI wants */
    fun buildSlots(map: Map<String, String>): Array<String?> {
        val out = arrayOfNulls<String>(SLOT_KEYS.size)
        for ((k, v) in map) {
            val i = SLOT_KEYS.indexOf(k)
            if (i >= 0) out[i] = v
        }
        return out
    }

    /** Generates one image; returns null on failure.
     *
     *  If initImage is non-null, goes img2img: it gets resized to width×height and used as the initial latent;
     *  strength(<1) decides from which step denoising starts (smaller = closer to original).
     */
    fun render(
        handle: Long,
        prompt: String,
        negative: String = "",
        loraPath: String? = null,
        loraScale: Float = 1.0f,
        width: Int = 512,
        height: Int = 512,
        steps: Int = 20,
        cfg: Float = 7.0f,
        seed: Long = -1L,
        sampler: Sampler = Sampler.EULER_A,
        scheduler: Scheduler = Scheduler.DISCRETE,
        cb: StepCallback? = null,
        onPreview: PreviewCallback? = null,
        initImage: ByteArray? = null,
        initWidth: Int = 0,
        initHeight: Int = 0,
        strength: Float = 1.0f,
        // ---- Native hires fix (implemented inside sd.cpp) ----
        hiresEnabled: Boolean = false,
        hiresTargetWidth: Int = 0,
        hiresTargetHeight: Int = 0,
        hiresSteps: Int = 0,
        hiresDenoiseStrength: Float = 0.7f,
        hiresUpscaler: HiresUpscaler = HiresUpscaler.LATENT,
        // ---- VAE tiling (tiled encode/decode, saves memory) ----
        vaeTilingEnabled: Boolean = false,
        vaeTileSize: Int = 512,
        vaeTileOverlap: Float = 0.25f,
    ): Bitmap? {
        val effectiveSeed = if (seed < 0) (System.currentTimeMillis() % 1_000_000_000L) else seed
        val useInit = initImage != null && initImage.isNotEmpty() && initWidth > 0 && initHeight > 0
        val useHires = hiresEnabled && hiresTargetWidth > 0 && hiresTargetHeight > 0
        val px = nativeGenerate(
            handle, prompt, negative, loraPath, loraScale,
            width, height, steps, cfg, effectiveSeed,
            sampler.code, scheduler.code, cb, onPreview,
            if (useInit) initImage else null,
            if (useInit) initWidth else 0,
            if (useInit) initHeight else 0,
            if (useInit) strength else 1.0f,
            useHires,
            if (useHires) hiresTargetWidth else 0,
            if (useHires) hiresTargetHeight else 0,
            if (useHires) hiresSteps else 0,
            if (useHires) hiresDenoiseStrength else 0.7f,
            if (useHires) hiresUpscaler.code else 0,
            vaeTilingEnabled,
            vaeTileSize,
            vaeTileOverlap,
        ) ?: return null
        if (px.isEmpty()) return null
        // With hires, output is target size, array laid out at target size
        val outW = if (useHires) hiresTargetWidth else width
        val outH = if (useHires) hiresTargetHeight else height
        if (px.size < outW * outH) return null
        return Bitmap.createBitmap(px, outW, outH, Bitmap.Config.ARGB_8888)
    }

        /** Preview small image (RGB888) → Bitmap (ARGB_8888); invalid input returns null */
    fun rgbToBitmap(width: Int, height: Int, rgb: ByteArray): Bitmap? {
        if (width <= 0 || height <= 0 || rgb.size < width * height * 3) return null
        val px = IntArray(width * height)
        var j = 0
        for (i in px.indices) {
            px[i] = (0xFF shl 24) or ((rgb[j].toInt() and 0xFF) shl 16) or
                    ((rgb[j + 1].toInt() and 0xFF) shl 8) or (rgb[j + 2].toInt() and 0xFF)
            j += 3
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

/**
     * Bitmap → RGB888 interleaved bytes (3 bytes per pixel, row-major, top-left origin).
     * sd.cpp's init_image uses this layout (see sd_image_get_f32).
     */
    fun bitmapToRgb888(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3)
        var j = 0
        for (i in px.indices) {
            val p = px[i]
            out[j++] = ((p shr 16) and 0xFF).toByte()
            out[j++] = ((p shr 8) and 0xFF).toByte()
            out[j++] = (p and 0xFF).toByte()
        }
        return out
    }
}
