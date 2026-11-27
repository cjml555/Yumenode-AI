// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool calling (function calling) sobre el servidor API OpenAI-compatible.
 *
 * El modelo local no habla el protocolo de herramientas de ningún proveedor:
 * lo que hace esta pieza es (1) describir las herramientas en el prompt,
 * (2) obligar al modelo a responder con un JSON válido mediante
 * `withJsonSchema` (decodificación restringida de llama.cpp) y (3) traducir
 * ese JSON al formato `tool_calls` de OpenAI.
 *
 * Funciona con cualquier GGUF, sin depender de que la plantilla de chat del
 * modelo soporte herramientas nativas.
 */
object ToolCalling {

    /** Una herramienta declarada por el cliente, en formato OpenAI. */
    data class Tool(
        val name: String,
        val description: String,
        /** JSON Schema de los argumentos (objeto). */
        val parameters: JSONObject,
    )

    /** Llamada pedida por el modelo. */
    data class Call(val name: String, val arguments: String)

    /** Resultado de interpretar la salida del modelo. */
    data class Result(val call: Call?, val answer: String)

    /**
     * Adapta el historial al backend local: llama.cpp no tiene rol `tool`, así
     * que el resultado de una herramienta se entrega como texto de usuario con
     * una etiqueta clara.
     */
    fun normalize(messages: List<ChatCore.Msg>): List<ChatCore.Msg> = messages.map { m ->
        if (m.role == "tool") ChatCore.Msg("user", "Resultado de la herramienta:\n" + m.text) else m
    }

    /**
     * Lee `tools` de la petición. Acepta el formato OpenAI
     * (`[{"type":"function","function":{...}}]`) y también la variante
     * abreviada (`[{"name":...,"parameters":...}]`).
     */
    fun parseTools(req: JSONObject): List<Tool> {
        val arr = req.optJSONArray("tools") ?: return emptyList()
        val out = ArrayList<Tool>(arr.length())
        for (i in 0 until arr.length()) {
            val raw = arr.optJSONObject(i) ?: continue
            val fn = raw.optJSONObject("function") ?: raw
            val name = fn.optString("name").trim()
            if (name.isEmpty()) continue
            val params = fn.optJSONObject("parameters")
                ?: JSONObject("{\"type\":\"object\",\"properties\":{}}")
            out.add(Tool(name, fn.optString("description"), params))
        }
        return out
    }

    /** `tool_choice`: "none" desactiva el modo herramientas. */
    fun toolsAllowed(req: JSONObject): Boolean {
        val choice = req.opt("tool_choice")
        return when (choice) {
            null, JSONObject.NULL -> true
            is String -> !choice.equals("none", ignoreCase = true)
            is JSONObject -> true        // {"type":"function","function":{"name":...}} → forzada
            else -> true
        }
    }

    /** Nombre de herramienta forzada por `tool_choice`, si lo hay. */
    fun forcedTool(req: JSONObject): String? {
        val choice = req.optJSONObject("tool_choice") ?: return null
        return choice.optJSONObject("function")?.optString("name")?.takeIf { it.isNotEmpty() }
    }

    /**
     * Bloque de instrucciones que se añade como mensaje de sistema.
     * Mantiene el formato JSON corto para que un modelo pequeño no se pierda.
     */
    fun instructions(tools: List<Tool>, forced: String? = null, answerOnly: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("Tienes acceso a estas herramientas:\n")
        tools.forEach { t ->
            sb.append("- ").append(t.name)
            if (t.description.isNotBlank()) sb.append(": ").append(t.description)
            sb.append("\n  argumentos (JSON Schema): ").append(t.parameters.toString())
            sb.append('\n')
        }
        sb.append("\nResponde SIEMPRE con un único objeto JSON, sin texto alrededor:\n")
        if (forced != null) {
            sb.append("{\"tool\":\"").append(forced).append("\",\"arguments\":{...}}\n")
            sb.append("Usa la herramienta ").append(forced).append(" en esta respuesta.\n")
        } else {
            sb.append("{\"tool\":\"<nombre>\",\"arguments\":{...}} para llamar a una herramienta, o\n")
            sb.append("{\"answer\":\"<tu respuesta>\"} si no necesitas ninguna.\n")
            if (answerOnly) {
                sb.append("Ya tienes el resultado de una herramienta: responde al usuario con ")
                sb.append("{\"answer\":\"...\"} usando esa información, sin volver a llamarla.\n")
            }
        }
        return sb.toString()
    }

    /**
     * JSON Schema que se pasa a `withJsonSchema` para que el muestreo solo
     * pueda producir un objeto con `tool`/`arguments` o `answer`.
     */
    fun responseSchema(tools: List<Tool>, forced: String? = null): String {
        val props = JSONObject()
        if (forced != null) {
            props.put("tool", JSONObject().put("type", "string").put("enum", JSONArray().put(forced)))
        } else if (tools.isNotEmpty()) {
            props.put(
                "tool",
                JSONObject().put("type", "string").put("enum", JSONArray().apply {
                    tools.forEach { put(it.name) }
                })
            )
        }
        props.put("arguments", JSONObject().put("type", "object"))
        props.put("answer", JSONObject().put("type", "string"))
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("additionalProperties", false)
        if (forced != null) schema.put("required", JSONArray().put("tool").put("arguments"))
        return schema.toString()
    }

    /**
     * Interpreta la salida del modelo. Tolera vallas de código, texto antes o
     * después del objeto y JSON inválido (en ese caso devuelve el texto tal cual
     * como respuesta normal, para no romper al cliente).
     */
    fun parseOutput(text: String): Result {
        val json = extractJsonObject(text) ?: return Result(null, text.trim())
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return Result(null, text.trim())
        val name = obj.optString("tool").trim()
        if (name.isNotEmpty()) {
            val args = obj.opt("arguments")
            val argsJson = when (args) {
                null, JSONObject.NULL -> "{}"
                is JSONObject -> args.toString()
                is String -> args.ifBlank { "{}" }
                else -> args.toString()
            }
            return Result(Call(name, argsJson), obj.optString("answer").trim())
        }
        val answer = obj.optString("answer")
        return Result(null, answer.ifBlank { text.trim() })
    }

    /** Primer objeto JSON balanceado del texto (respeta cadenas y escapes). */
    fun extractJsonObject(text: String): String? {
        var depth = 0
        var start = -1
        var inStr = false
        var escaped = false
        text.forEachIndexed { i, c ->
            if (inStr) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
                return@forEachIndexed
            }
            when (c) {
                '"' -> inStr = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}
