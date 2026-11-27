// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parámetros de muestreo de la API: parseo, rangos y `stop`. */
class SamplingParamsTest {

    @Test
    fun `sin parametros todo queda en null`() {
        val o = SamplingParams.from(JSONObject())
        assertNull(o.topP); assertNull(o.topK); assertNull(o.minP); assertNull(o.seed)
        assertNull(o.frequencyPenalty); assertNull(o.presencePenalty); assertNull(o.repeatPenalty)
        assertNull(o.cachePrompt)
        assertTrue(o.stop.isEmpty())
    }

    @Test
    fun `lee los valores estandar`() {
        val req = JSONObject()
            .put("top_p", 0.5).put("top_k", 20).put("min_p", 0.05)
            .put("seed", 1234)
            .put("frequency_penalty", 0.3).put("presence_penalty", -0.2)
        val o = SamplingParams.from(req)
        assertEquals(0.5f, o.topP!!, 1e-6f)
        assertEquals(20, o.topK)
        assertEquals(0.05f, o.minP!!, 1e-6f)
        assertEquals(1234, o.seed)
        assertEquals(0.3f, o.frequencyPenalty!!, 1e-6f)
        assertEquals(-0.2f, o.presencePenalty!!, 1e-6f)
    }

    @Test
    fun `recorta valores fuera de rango`() {
        val req = JSONObject().put("top_p", 5.0).put("frequency_penalty", -9.0).put("top_k", -3)
        val o = SamplingParams.from(req)
        assertEquals(1.0f, o.topP!!, 1e-6f)
        assertEquals(-2.0f, o.frequencyPenalty!!, 1e-6f)
        assertEquals(0, o.topK)
    }

    @Test
    fun `stop como string`() {
        assertEquals(listOf("\n\n"), SamplingParams.stopSequences("\n\n"))
    }

    @Test
    fun `stop como array sin vacios ni duplicados`() {
        val arr = JSONArray().put("\n").put("").put("\n").put("FIN")
        assertEquals(listOf("\n", "FIN"), SamplingParams.stopSequences(arr))
    }

    @Test
    fun `stop nulo o de tipo raro devuelve lista vacia`() {
        assertTrue(SamplingParams.stopSequences(null).isEmpty())
        assertTrue(SamplingParams.stopSequences(JSONObject.NULL).isEmpty())
        assertTrue(SamplingParams.stopSequences(42).isEmpty())
    }

    @Test
    fun `cache_prompt se respeta y por defecto es null`() {
        assertEquals(false, SamplingParams.from(JSONObject().put("cache_prompt", false)).cachePrompt)
        assertEquals(true, SamplingParams.from(JSONObject().put("cache_prompt", true)).cachePrompt)
        assertNull(SamplingParams.from(JSONObject()).cachePrompt)
    }

    @Test
    fun `req nulo no rompe`() {
        val o = SamplingParams.from(null)
        assertNull(o.topP)
        assertTrue(o.stop.isEmpty())
    }
}
