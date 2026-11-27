package com.yumenode.ai

import java.util.Locale

/**
 * Pure, testable send-time logic factored out of `doSend()` in MainActivity.
 *
 * `doSend()` orchestrates model/engine state (Conversation, llamaModel, engine,
 * attachmentHandler) and is intentionally NOT moved here. What moves is the
 * *stateless* decision math that only depends on the inputs already collected
 * by doSend — this lets us pin the current behavior with JVM unit tests
 * (ChatSendLogicTest) *before* refactoring doSend to call back into this class,
 * satisfying the constraint that doSend/runRound may only be touched once
 * characterization coverage exists.
 */
object ChatSendLogic {

    /**
     * Mirrors the exact composition in `doSend()`:
     *  - text only → text
     *  - fileBlock only → fileBlock
     *  - both → text + "\n\n" + fileBlock
     * (empty text AND empty fileBlock is rejected by the caller before this)
     */
    fun composeSendText(text: String, fileBlock: String): String =
        if (fileBlock.isEmpty()) text
        else if (text.isEmpty()) fileBlock
        else text + "\n\n" + fileBlock

    /**
     * Resume el primer mensaje del usuario en un título corto y legible.
     *
     * - colapsa saltos de línea y espacios repetidos
     * - quita bloques/código en línea y URLs (no aportan al título)
     * - corta en límite de palabra por [maxChars] y limpia puntuación final
     * - capitaliza la primera letra (respetando «¿» / «¡» de apertura)
     *
     * Es determinista y sin coste de inferencia: se aplica en el primer envío.
     */
    fun summarizeTitle(text: String, maxChars: Int = 40): String {
        var s = text.replace('\r', ' ').replace('\n', ' ')
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`[^`]*`"), " ")
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        s = s.trim(' ', ',', '.', ';', ':', '-', '—', '"', '\'')
        if (s.isEmpty()) return ""
        if (s.length > maxChars) {
            val cut = s.take(maxChars)
            val at = cut.lastIndexOf(' ')
            s = (if (at >= maxChars / 2) cut.substring(0, at) else cut)
                .trimEnd(' ', ',', '.', ';', ':', '-', '—')
        }
        val firstLetter = s.indexOfFirst { it.isLetter() }
        if (firstLetter >= 0) {
            s = s.substring(0, firstLetter) + s[firstLetter].uppercase() + s.substring(firstLetter + 1)
        }
        return s
    }

    /** Títulos por defecto de una sesión recién creada (dependen del idioma). */
    private val KNOWN_DEFAULT_TITLES = setOf(
        "new chat", "nuevo chat", "nueva conversación", "新对话", "chat",
    )

    /**
     * ¿El título sigue siendo el de una sesión sin estrenar?
     *
     * La comparación con `getString(R.string.s_109)` falla cuando la sesión se
     * creó con otro idioma (p.ej. "New chat" guardado y la app en español), así
     * que además de la cadena localizada se aceptan los defaults conocidos.
     */
    fun isPlaceholderTitle(title: String, localizedDefault: String? = null): Boolean {
        val t = title.trim().lowercase()
        if (t.isEmpty()) return true
        if (t in KNOWN_DEFAULT_TITLES) return true
        return localizedDefault != null && t == localizedDefault.trim().lowercase()
    }

    /**
     * Título inicial de la sesión a partir del primer envío: usa el resumen de
     * [summarizeTitle] y cae al nombre del primer archivo adjunto y, si no hay
     * nada, al título por defecto.
     */
    fun computeSeedTitle(
        text: String,
        firstFileName: String?,
        defaultTitle: String,
        isPlaceholderTitle: Boolean,
    ): String {
        if (!isPlaceholderTitle) return defaultTitle
        summarizeTitle(text).takeIf { it.isNotEmpty() }?.let { return it }
        if (!firstFileName.isNullOrEmpty()) return firstFileName
        return defaultTitle
    }

    /** Number of non-empty image paths encoded in a "|" separated `userImage`. */
    fun countImages(userImage: String?): Int =
        userImage?.split("|")?.count { it.isNotEmpty() } ?: 0

    /**
     * Mirrors `tagHint()`: a localized notice (s_329, 1-arg count) followed by
     * the tool prompt that tells a vision-less model it may call <tag>.
     * `toolPrompt` carries the exact string currently in `tagToolPrompt`.
     */
    fun buildTagHint(count: Int, s_329: String, toolPrompt: String): String =
        s_329.format(Locale.getDefault(), count) + "\n" + toolPrompt
}
