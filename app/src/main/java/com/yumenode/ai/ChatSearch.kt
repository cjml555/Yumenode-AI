// Yumenode AI · (c) 2026 visitor257 · MIT License
package com.yumenode.ai

/**
 * Búsqueda en el historial de chats.
 *
 * Coincide por título y por el texto de cualquier turno (pregunta o
 * respuesta). Cuando el acierto está en un turno se devuelve un fragmento
 * alrededor de la coincidencia para mostrarlo bajo el título.
 *
 * Es lógica pura (sin Android) para poder fijarla con tests JVM.
 */
object ChatSearch {

    data class Match(
        val session: ChatSession,
        /** Fragmento del turno donde se encontró, o null si el acierto fue en el título. */
        val snippet: String?,
        /** true si el título coincide con la consulta. */
        val inTitle: Boolean,
    )

    /** Tamaño del fragmento: 20 caracteres antes y 40 después de la coincidencia. */
    private const val BEFORE = 20
    private const val AFTER = 40

    fun filter(sessions: List<ChatSession>, query: String): List<Match> {
        val q = query.trim()
        if (q.isEmpty()) return sessions.map { Match(it, null, false) }
        return sessions.mapNotNull { s ->
            val inTitle = s.title.contains(q, ignoreCase = true)
            val turn = s.turns.firstOrNull { t ->
                t.user.contains(q, ignoreCase = true) || t.answer.contains(q, ignoreCase = true)
            }
            if (!inTitle && turn == null) return@mapNotNull null
            val snippet = turn?.let { snippetAround(it, q) }
            Match(s, snippet, inTitle)
        }
    }

    /** Fragmento del turno con la coincidencia (en la pregunta si la hay, si no en la respuesta). */
    fun snippetAround(turn: QaTurn, query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val source = when {
            turn.user.contains(q, ignoreCase = true) -> turn.user
            turn.answer.contains(q, ignoreCase = true) -> turn.answer
            else -> return null
        }
        val hit = source.indexOf(q, ignoreCase = true)
        if (hit < 0) return null
        val start = (hit - BEFORE).coerceAtLeast(0)
        val end = (hit + q.length + AFTER).coerceAtMost(source.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < source.length) "…" else ""
        return prefix + source.substring(start, end).replace('\n', ' ') + suffix
    }
}
