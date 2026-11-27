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

    /**
     * Instrucción de sistema para `response_format`: el modelo debe responder
     * solo un JSON válido que cumpla el schema (mismo enfoque que las tools,
     * porque el binding no aplica gramáticas en `generateChat`).
     */
    fun jsonInstructions(schema: String): String =
        "Responde EXCLUSIVAMENTE con un valor JSON válido que cumpla este JSON Schema. " +
            "No escribas texto antes ni después, no uses vallas de código y NO copies el " +
            "schema en la respuesta: devuelve datos que lo cumplan.\nSchema:\n" + schema

    /** `tool_choice: "required"`: el cliente exige que se llame a alguna herramienta. */
    fun toolChoiceRequired(req: JSONObject): Boolean =
        (req.opt("tool_choice") as? String)?.equals("required", ignoreCase = true) == true

    /**
     * Schema de `response_format` (OpenAI).
     *
     * - `{"type":"json_object"}` → cualquier objeto JSON
     * - `{"type":"json_schema","json_schema":{"schema":{...}}}` → ese schema
     * - `text` o ausente → null (sin restricción)
     */
    fun responseFormatSchema(req: JSONObject): String? {
        val rf = req.optJSONObject("response_format") ?: return null
        return when (rf.optString("type").lowercase()) {
            "json_object" -> "{\"type\":\"object\"}"
            "json_schema" -> when (val inner = rf.optJSONObject("json_schema")?.opt("schema")) {
                is JSONObject -> inner.toString()
                is String -> inner.ifBlank { null }
                else -> null
            }
            else -> null
        }
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

    /**
     * `response_format`: deja solo el JSON, aunque el modelo lo envuelva en
     * vallas de código o añada texto alrededor (los modelos pequeños lo hacen).
     * Si no hay JSON válido, devuelve el texto tal cual.
     */
    fun normalizeJsonOutput(text: String): String {
        val raw = text.trim()
        // 1) valla de código: ```json ... ``` (el modelo suele envolver la respuesta)
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.get(1)?.trim()
        if (fenced != null && isJsonValue(fenced)) return fenced
        // 2) el propio texto ya es JSON
        if (isJsonValue(raw)) return raw
        // 3) JSON embebido entre texto (objeto o array)
        embeddedJson(raw)?.let { return it }
        return raw
    }

    /** true si el texto es un objeto o array JSON válido. */
    fun isJsonValue(text: String): Boolean =
        runCatching { JSONObject(text) }.isSuccess || runCatching { JSONArray(text) }.isSuccess

    /** Tipo de primer nivel del schema (`object`, `array`, …), si lo declara. */
    fun schemaTopType(schema: String): String? =
        runCatching {
            JSONObject(schema).optString("type").takeIf { it.isNotBlank() }
        }.getOrNull()

    /** true si [text] es un JSON del tipo de primer nivel [type]. */
    fun matchesTopType(text: String, type: String): Boolean = when (type) {
        "array" -> runCatching { JSONArray(text) }.isSuccess
        "object" -> runCatching { JSONObject(text) }.isSuccess
        else -> true
    }

    /** Primer objeto/array JSON balanceado del texto, respetando cadenas y escapes. */
    fun embeddedJson(text: String): String? {
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val open = text[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                inStr -> {}
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) {
                        val candidate = text.substring(start, i + 1)
                        return if (isJsonValue(candidate)) candidate else null
                    }
                }
            }
        }
        return null
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
