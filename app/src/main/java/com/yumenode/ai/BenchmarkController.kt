// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Controlador de benchmark: corre N generaciones cortas sobre el modelo
 * cargado y reporta tok/s, latencia, modelo, backend y dispositivo.
 */
class BenchmarkController(private val host: BenchmarkHost) {

    /** Benchmark local: corre N generaciones cortas sobre el modelo cargado. */
    fun runBenchmark(onUpdate: (String) -> Unit) {
        // Check if model is loaded via ChatCore
        if (!ChatCore.isReady()) {
            onUpdate(host.getString(R.string.s_480))
            return
        }
        if (ChatCore.busy) {
            onUpdate(host.getString(R.string.s_481))
            return
        }
        host.scope.launch(Dispatchers.Main) {
            val prompt = "Write a short, coherent paragraph about the sea."
            val runs = 3
            val tps = mutableListOf<Double>()
            val lat = mutableListOf<Long>()
            var lastTokens = 0
            for (i in 1..runs) {
                onUpdate(host.getString(R.string.s_487, i, runs))
                val t0 = SystemClock.uptimeMillis()
                val out = try {
                    ChatCore.generate(listOf(ChatCore.Msg("user", prompt)), 0.7f, 128) { }
                } catch (e: Throwable) {
                    onUpdate("Error: ${e.message ?: e.javaClass.simpleName}")
                    return@launch
                }
                val dt = (SystemClock.uptimeMillis() - t0).coerceAtLeast(1)
                lastTokens = (out.length / 4).coerceAtLeast(1)
                lat += dt
                tps += lastTokens / (dt / 1000.0)
            }
            val name = ChatCore.modelName ?: "?"
            val backend = when {
                name.endsWith(".gguf", true) -> "llama.cpp (CPU)"
                name.endsWith(".litertlm", true) -> "LiteRT (CPU)"
                else -> "?"
            }
            val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
            val tpsAvg = "%.1f".format(tps.average())
            val tpsMin = "%.1f".format(tps.minOrNull() ?: 0.0)
            val tpsMax = "%.1f".format(tps.maxOrNull() ?: 0.0)
            val bench = BenchStats(
                model = name,
                backend = backend,
                device = Build.MODEL,
                soc = soc,
                tpsAvg = tps.average(),
                tpsMin = tps.minOrNull() ?: 0.0,
                tpsMax = tps.maxOrNull() ?: 0.0,
                latMin = lat.minOrNull() ?: 0L,
                latMax = lat.maxOrNull() ?: 0L,
                tokens = lastTokens,
            )
            host.lastBench = bench
            onUpdate(host.getString(
                R.string.s_486,
                name, backend, Build.MODEL, soc, lastTokens, tpsAvg, tpsMin, tpsMax, lat.minOrNull() ?: 0L, lat.maxOrNull() ?: 0L
            ))
        }
    }
}