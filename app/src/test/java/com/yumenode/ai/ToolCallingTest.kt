// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tool calling: parseo de `tools`, schema de salida y traduccion a tool_calls. */
class ToolCallingTest {

    private fun request(vararg tools: Pair<String, String>): JSONObject {
        val arr = JSONArray()
        tools.forEach { (name, desc) ->
            arr.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", name)
                    .put("description", desc)
                    .put("parameters", JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject()
                            .put("city", JSONObject().put("type", "string"))))))
        }
        return JSONObject().put("tools", arr)
    }

    @Test
    fun `parseTools lee el formato OpenAI`() {
        val tools = ToolCalling.parseTools(request("weather" to "clima", "time" to "hora"))
        assertEquals(listOf("weather", "time"), tools.map { it.name })
        assertEquals("clima", tools[0].description)
        assertEquals("object", tools[0].parameters.optString("type"))
    }

    @Test
    fun `parseTools acepta la variante abreviada`() {
        val req = JSONObject().put("tools", JSONArray().put(
            JSONObject().put("name", "echo").put("parameters", JSONObject().put("type", "object"))
        ))
        val tools = ToolCalling.parseTools(req)
        assertEquals(1, tools.size)
        assertEquals("echo", tools[0].name)
    }

    @Test
    fun `parseTools ignora entradas sin nombre y peticiones sin tools`() {
        val bad = JSONObject().put("tools", JSONArray().put(JSONObject().put("type", "function")))
        assertTrue(ToolCalling.parseTools(bad).isEmpty())
        assertTrue(ToolCalling.parseTools(JSONObject()).isEmpty())
    }

    @Test
    fun `tool_choice none desactiva las herramientas`() {
        assertTrue(ToolCalling.toolsAllowed(JSONObject()))
        assertTrue(ToolCalling.toolsAllowed(JSONObject().put("tool_choice", "auto")))
        assertTrue(!ToolCalling.toolsAllowed(JSONObject().put("tool_choice", "none")))
    }

    @Test
    fun `tool_choice con funcion fuerza esa herramienta`() {
        val req = JSONObject().put("tool_choice", JSONObject()
            .put("type", "function")
            .put("function", JSONObject().put("name", "weather")))
        assertEquals("weather", ToolCalling.forcedTool(req))
        assertNull(ToolCalling.forcedTool(JSONObject().put("tool_choice", "auto")))
    }

    @Test
    fun `responseSchema enumera los nombres y prohibe propiedades extra`() {
        val tools = ToolCalling.parseTools(request("weather" to "clima", "time" to "hora"))
        val schema = JSONObject(ToolCalling.responseSchema(tools))
        assertEquals("object", schema.getString("type"))
        assertEquals(false, schema.getBoolean("additionalProperties"))
        val names = schema.getJSONObject("properties").getJSONObject("tool").getJSONArray("enum")
        assertEquals(listOf("weather", "time"), (0 until names.length()).map { names.getString(it) })
    }

    @Test
    fun `responseSchema forzado exige tool y arguments`() {
        val tools = ToolCalling.parseTools(request("weather" to "clima"))
        val schema = JSONObject(ToolCalling.responseSchema(tools, "weather"))
        val required = schema.getJSONArray("required")
        assertEquals(listOf("tool", "arguments"), (0 until required.length()).map { required.getString(it) })
        assertEquals("weather", schema.getJSONObject("properties")
            .getJSONObject("tool").getJSONArray("enum").getString(0))
    }

    @Test
    fun `instructions describe cada herramienta`() {
        val tools = ToolCalling.parseTools(request("weather" to "clima de una ciudad"))
        val text = ToolCalling.instructions(tools)
        assertTrue(text.contains("weather"))
        assertTrue(text.contains("clima de una ciudad"))
        assertTrue(text.contains("{\"answer\""))
    }

    @Test
    fun `parseOutput traduce tool y arguments`() {
        val out = ToolCalling.parseOutput("""{"tool":"weather","arguments":{"city":"Toluca"}}""")
        assertEquals("weather", out.call?.name)
        assertEquals("""{"city":"Toluca"}""", out.call?.arguments)
        assertTrue(out.answer.isEmpty())
    }

    @Test
    fun `parseOutput acepta arguments como string`() {
        val out = ToolCalling.parseOutput("""{"tool":"weather","arguments":"{\"city\":\"X\"}"}""")
        assertEquals("""{"city":"X"}""", out.call?.arguments)
    }

    @Test
    fun `parseOutput sin arguments devuelve objeto vacio`() {
        val out = ToolCalling.parseOutput("""{"tool":"ping"}""")
        assertEquals("{}", out.call?.arguments)
    }

    @Test
    fun `parseOutput devuelve la respuesta cuando no hay tool`() {
        val out = ToolCalling.parseOutput("""{"answer":"Hola, ¿en qué ayudo?"}""")
        assertNull(out.call)
        assertEquals("Hola, ¿en qué ayudo?", out.answer)
    }

    @Test
    fun `parseOutput tolera vallas de codigo y texto alrededor`() {
        val raw = """
            Claro:
            ```json
            {"tool": "time", "arguments": {}}
            ```
        """.trimIndent()
        assertEquals("time", ToolCalling.parseOutput(raw).call?.name)
    }

    @Test
    fun `parseOutput con JSON invalido conserva el texto`() {
        val out = ToolCalling.parseOutput("no soy json")
        assertNull(out.call)
        assertEquals("no soy json", out.answer)
    }

    @Test
    fun `extractJsonObject respeta llaves dentro de cadenas`() {
        val raw = """antes {"answer":"usa {esto} y } aquello"} despues"""
        assertEquals("""{"answer":"usa {esto} y } aquello"}""", ToolCalling.extractJsonObject(raw))
    }

    @Test
    fun `extractJsonObject devuelve null sin objeto`() {
        assertNull(ToolCalling.extractJsonObject("sin llaves"))
    }

    @Test
    fun `chatCompletionToolCalls usa finish_reason tool_calls y content null`() {
        val calls = JSONArray().put(OpenAiApi.toolCall("call_1", "weather", """{"city":"X"}"""))
        val body = OpenAiApi.chatCompletionToolCalls(
            "chatcmpl-1", "m", 0L, calls, JSONObject().put("total_tokens", 1)
        )
        val choice = body.getJSONArray("choices").getJSONObject(0)
        assertEquals("tool_calls", choice.getString("finish_reason"))
        assertEquals(JSONObject.NULL, choice.getJSONObject("message").get("content"))
        val call = choice.getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("function", call.getString("type"))
        assertEquals("weather", call.getJSONObject("function").getString("name"))
    }

    @Test
    fun `chatChunkToolCalls emite index id y funcion`() {
        val calls = JSONArray().put(OpenAiApi.toolCall("call_1", "time", "{}"))
        val chunk = OpenAiApi.chatChunkToolCalls("chatcmpl-1", "m", 0L, calls)
        val delta = chunk.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
        val entry = delta.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals(0, entry.getInt("index"))
        assertEquals("call_1", entry.getString("id"))
        assertEquals("time", entry.getJSONObject("function").getString("name"))
    }

    @Test
    fun `chatChunkFinish mantiene stop por defecto`() {
        val body = OpenAiApi.chatChunkFinish("id", "m", 0L)
        assertEquals("stop", body.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        val tc = OpenAiApi.chatChunkFinish("id", "m", 0L, "tool_calls")
        assertEquals("tool_calls", tc.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
    }

    @Test
    fun `normalize convierte el rol tool en texto de usuario`() {
        val out = ToolCalling.normalize(listOf(
            ChatCore.Msg("assistant", "hola"),
            ChatCore.Msg("tool", """{"temp":21}"""),
        ))
        assertEquals("assistant", out[0].role)
        assertEquals("user", out[1].role)
        assertTrue(out[1].text.startsWith("Resultado de la herramienta:"))
        assertTrue(out[1].text.contains("temp"))
    }
}
