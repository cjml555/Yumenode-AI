// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Búsqueda en el historial: filtrado por título/texto y fragmentos. */
class ChatSearchTest {

    private fun session(id: Long, title: String, vararg turns: Pair<String, String>): ChatSession {
        val s = ChatSession(id, title)
        turns.forEach { (u, a) -> s.turns.add(QaTurn(u, a)) }
        return s
    }

    @Test
    fun `consulta vacia devuelve todas las sesiones sin fragmento`() {
        val sessions = listOf(session(1, "Uno"), session(2, "Dos"))
        val out = ChatSearch.filter(sessions, "   ")
        assertEquals(2, out.size)
        assertTrue(out.all { it.snippet == null })
    }

    @Test
    fun `coincide por titulo`() {
        val sessions = listOf(session(1, "Receta de pastel"), session(2, "Nodos"))
        val out = ChatSearch.filter(sessions, "pastel")
        assertEquals(1, out.size)
        assertTrue(out[0].inTitle)
        assertNull(out[0].snippet)
    }

    @Test
    fun `coincide por texto de la pregunta y devuelve fragmento`() {
        val sessions = listOf(
            session(1, "Charla", "¿cómo cocino un pastel sin horno?" to "Usa una olla…"),
            session(2, "Otra", "hola" to "hola!")
        )
        val out = ChatSearch.filter(sessions, "horno")
        assertEquals(1, out.size)
        assertEquals("Charla", out[0].session.title)
        assertTrue(out[0].snippet!!.contains("horno"))
    }

    @Test
    fun `coincide por la respuesta`() {
        val sessions = listOf(session(1, "X", "pregunta" to "la respuesta menciona un nodo"))
        val out = ChatSearch.filter(sessions, "nodo")
        assertEquals(1, out.size)
        assertTrue(out[0].snippet!!.contains("nodo"))
    }

    @Test
    fun `sin coincidencias devuelve lista vacia`() {
        val sessions = listOf(session(1, "Uno", "hola" to "qué tal"))
        assertTrue(ChatSearch.filter(sessions, "zzz").isEmpty())
    }

    @Test
    fun `no distingue mayusculas`() {
        val sessions = listOf(session(1, "CLUSTER", "Nodo" to "ok"))
        assertEquals(1, ChatSearch.filter(sessions, "cluster").size)
        assertEquals(1, ChatSearch.filter(sessions, "nodo").size)
    }

    @Test
    fun `fragmento recorta y anade puntos suspensivos`() {
        val long = "a".repeat(100) + " aguja " + "b".repeat(100)
        val turn = QaTurn(long, "")
        val sn = ChatSearch.snippetAround(turn, "aguja")!!
        assertTrue("empieza con …", sn.startsWith("…"))
        assertTrue("termina con …", sn.endsWith("…"))
        assertTrue("contiene la aguja", sn.contains("aguja"))
        assertTrue("corto: ${sn.length}", sn.length < 80)
    }

    @Test
    fun `fragmento sin recorte no lleva puntos suspensivos`() {
        val turn = QaTurn("hola nodo", "")
        assertEquals("hola nodo", ChatSearch.snippetAround(turn, "nodo"))
    }

    @Test
    fun `fragmento devuelve null si no hay coincidencia`() {
        assertNull(ChatSearch.snippetAround(QaTurn("hola", "adios"), "zzz"))
    }
}
