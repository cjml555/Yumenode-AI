// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Traduce los parámetros de muestreo de una petición OpenAI a [ChatCore.GenOptions].
 *
 * Todo es opcional: lo que el cliente no manda queda en null y la app usa sus
 * valores por defecto. Los valores fuera de rango se recortan en vez de fallar,
 * que es lo que hacen los servidores de inferencia reales.
 */
object SamplingParams {

    fun from(req: JSONObject?): ChatCore.GenOptions {
        if (req == null) return ChatCore.GenOptions()
        return ChatCore.GenOptions(
            topP = req.optDoubleOrNull("top_p")?.let { it.coerceIn(0.0, 1.0).toFloat() },
            topK = req.optIntOrNull("top_k")?.coerceAtLeast(0),
            minP = req.optDoubleOrNull("min_p")?.let { it.coerceIn(0.0, 1.0).toFloat() },
            seed = req.optIntOrNull("seed"),
            stop = stopSequences(req.opt("stop")),
            frequencyPenalty = req.optDoubleOrNull("frequency_penalty")
                ?.let { it.coerceIn(-2.0, 2.0).toFloat() },
            presencePenalty = req.optDoubleOrNull("presence_penalty")
                ?.let { it.coerceIn(-2.0, 2.0).toFloat() },
            // extra: no está en el estándar pero llama.cpp lo usa mucho
            repeatPenalty = req.optDoubleOrNull("repeat_penalty")
                ?.let { it.coerceIn(0.0, 4.0).toFloat() },
            cachePrompt = if (req.has("cache_prompt")) req.optBoolean("cache_prompt") else null,
        )
    }

    /** `stop` acepta un string o un array de strings (contrato OpenAI). */
    fun stopSequences(value: Any?): List<String> = when (value) {
        null, JSONObject.NULL -> emptyList()
        is String -> listOfNotNull(value.takeIf { it.isNotEmpty() })
        is JSONArray -> (0 until value.length()).mapNotNull { i ->
            value.optString(i).takeIf { it.isNotEmpty() }
        }.distinct()
        else -> emptyList()
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
}
