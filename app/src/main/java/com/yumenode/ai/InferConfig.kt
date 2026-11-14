// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.content.Context

/**
 * Inference thread count -- **global**, not just for drawing.
 *
 * Previously this value only lived on the draw page (pref key `drawThreads`), so "thread count" was treated as a draw-only toggle.
 * In fact it is a generic CPU inference parameter, consumed by all three paths:
 *   1. Drawing (stable-diffusion.cpp) -- `sd_ctx_params_t.n_threads`
 *   2. Chat GGUF (llama.cpp) -- `ModelParameters.setThreads / setThreadsBatch`
 *   3. Tagging (ONNX Runtime) -- `OrtSession.SessionOptions.setIntraOpNumThreads`
 *
 * ⚠ Exception: chat via **`.litertlm` (Google LiteRT-LM) has no thread-count parameter**.
 * `EngineConfig` only exposes modelPath / backend / visionBackend / audioBackend /
 * maxNumTokens / maxNumImages / cacheDir (confirmed by reverse-engineering v0.17.0), so that path
 * is not controlled here -- it is a library limitation, not a missing integration.
 *
 * Changes take effect **next time a model loads** (all three paths hand the thread count to native at load time).
 */
object InferConfig {

    private const val PREFS = "ponko"
    private const val KEY_THREADS = "inferThreads"

    /** Legacy key: misnamed for the draw page during the 1.7.2 test phase; read for compatibility, no longer written */
    private const val KEY_LEGACY_DRAW = "drawThreads"

    /** Default 4: the big-core count on most phones. Too many threads spill onto little cores and slow things down. */
    const val DEFAULT = 4

    /**
     * Upper bound = the device's **real usable** core count, no artificial cap (user directive 2026-10-13).
     *
     * Note `availableProcessors()` returns the **currently usable** core count (real-time limited by cpuset / battery-saver),
     * not necessarily the physical core count -- but for "how many inference threads can I run" that is exactly the desired semantics:
     * capping at the current usable count avoids a UI that allows 8 while only 4 cores are actually available.
     * Lower bound 1: at least one thread in all cases.
     */
    fun maxThreads(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * Hilos recomendados para este telefono: los nucleos "big".
     *
     * En SoC big.LITTLE (Snapdragon/Mali) mandar trabajo a los little o
     * esperar migraciones cuesta mas que usar solo los grandes.
     */
    fun recommendedThreads(c: Context): Int =
        DeviceProbe.recommendedThreads(DeviceProbe.read(c))

    /** Current thread count (reads the legacy key for compatibility; out-of-range values clamped back) */
    fun threads(c: Context): Int {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Sin valor guardado se usa el recomendado del SoC, no un 4 fijo.
        val def = recommendedThreads(c)
        val raw = when {
            p.contains(KEY_THREADS) -> p.getInt(KEY_THREADS, def)
            p.contains(KEY_LEGACY_DRAW) -> p.getInt(KEY_LEGACY_DRAW, def)
            else -> def
        }
        return raw.coerceIn(1, maxThreads())
    }

    fun setThreads(c: Context, value: Int) {
        val v = value.coerceIn(1, maxThreads())
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_THREADS, v)
            .remove(KEY_LEGACY_DRAW)
            .apply()
    }
}
