// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

/**
 * Splits the streaming output of a GGUF model into two streams: "thought" content and "body" content.
 *
 * Supported thought markers:
 *  - Gemma 3n / 4: `<|channel>thought` … `<channel|>`
 *  - DeepSeek / Qwen / GLM: ` thinking` … `<｜end▁of▁thinking｜>`
 *
 * Markers may be split across adjacent chunks (e.g. `<chan` + `nel|>`), so we keep an internal buffer:
 * if the tail looks like a prefix of a marker, hold it back and wait for the next chunk before deciding.
 * Thought markers can only appear at the very start of the output (leading whitespace allowed); anything else is body.
 */
class ReasoningSplitter(
    private val onThought: (String) -> Unit,
    private val onAnswer: (String) -> Unit,
    assumeThinking: Boolean = false,
) {
    private val starts = listOf("<|channel>thought", " thinking")
    private val ends = listOf("<channel|>", "<|channel|>", "<｜end▁of▁thinking｜>")

    /** 0 = undecided (waiting for a leading thought marker); 1 = thinking; 2 = body */
    private var state = if (assumeThinking) 1 else 0
    private val buf = StringBuilder()
    private var totalThought = 0

    private companion object {
        /** When thought mode is on but the end marker never arrives, treat content beyond this length as plain body to avoid swallowing it. */
        const val ASSUMED_THINK_LIMIT = 4000
    }

    fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        buf.append(chunk)
        var again = true
        while (again) {
            again = false
            val s = buf.toString()
            if (s.isEmpty()) return
            when (state) {
                0 -> {
                    val lead = s.length - s.trimStart().length
                    val core = s.substring(lead)
                    // El marcador " thinking" empieza por un espacio, asi que
                    // hay que buscarlo en el texto CON los blancos iniciales
                    // (s), no en `core` (que ya no los tiene): si no, nunca
                    // se encuentra y el razonamiento de DeepSeek/Qwen/GLM no
                    // se separa de la respuesta.
                    val hit = findFirst(s, starts)
                    if (hit != null) {
                        // Content before the marker (including leading whitespace) is emitted as body
                        // El texto previo al marcador se EMITE, no se pierde:
                        // `lead` son solo los blancos iniciales, pero puede
                        // haber texto antes del marcador.
                        if (hit.first > 0) onAnswer(s.substring(0, hit.first))
                        buf.delete(0, hit.first + hit.second.length)
                        state = 1
                        again = true
                    } else {
                        // El sufijo del buffer puede ser el inicio de un marcador:
                        // se retiene hasta ver si se completa. El resto (el
                        // texto que ya sabemos que NO es marcador) se emite
                        // como respuesta.
                        //
                        // OJO: `pendingTail` se calcula sobre `s` (que incluye
                        // los blancos iniciales) y el texto que va antes del
                        // marcador es `s` menos ese sufijo. Comparar el
                        // resultado con `core.length` (sin blancos) no tiene
                        // sentido: son magnitudes distintas y la retencion
                        // nunca ocurre.
                        val keep = pendingTail(s, starts)
                        val emit = s.length - keep
                        if (emit > 0) onAnswer(s.substring(0, emit))
                        if (keep > 0) {
                            buf.delete(0, emit)
                        } else {
                            buf.setLength(0)
                            state = 2
                        }
                    }
                }
                1 -> {
                    val hit = findFirst(s, ends)
                    if (hit != null) {
                        if (hit.first > 0) onThought(s.substring(0, hit.first))
                        buf.delete(0, hit.first + hit.second.length)
                        state = 2
                        again = true
                    } else {
                        // Presumed "thinking" never hit the end marker: treat overlong content as plain body, avoid swallowing it
                        if (totalThought + s.length > ASSUMED_THINK_LIMIT) {
                            onAnswer(s)
                            buf.setLength(0)
                            state = 2
                        } else {
                            val keep = pendingTail(s, ends)
                            val emit = s.length - keep
                            if (emit > 0) {
                                onThought(s.substring(0, emit))
                                totalThought += emit
                                buf.delete(0, emit)
                            }
                        }
                    }
                }
                else -> {
                    onAnswer(s)
                    buf.setLength(0)
                }
            }
        }
    }

    /** Flush whatever remains in the buffer at end of stream (an unclosed thought segment counts as thought). */
    fun finish() {
        val s = buf.toString()
        if (s.isEmpty()) return
        if (state == 1) onThought(s) else onAnswer(s)
        buf.setLength(0)
    }

    private fun findFirst(s: String, markers: List<String>): Pair<Int, String>? {
        var bestIndex = -1
        var bestMarker: String? = null
        for (m in markers) {
            val i = s.indexOf(m)
            if (i >= 0 && (bestIndex < 0 || i < bestIndex)) {
                bestIndex = i
                bestMarker = m
            }
        }
        return if (bestMarker != null) bestIndex to bestMarker else null
    }

    /** How many trailing characters of s could be the start of some marker (needs to be held back until the next chunk). */
    private fun pendingTail(s: String, markers: List<String>): Int {
        var best = 0
        for (m in markers) {
            var k = minOf(m.length - 1, s.length)
            while (k > best) {
                if (s.regionMatches(s.length - k, m, 0, k)) {
                    best = k
                    break
                }
                k--
            }
        }
        return best
    }
}
