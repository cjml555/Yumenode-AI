package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for ChatSendLogic — pin the *current* doSend() behavior
 * extracted as pure functions BEFORE wiring them into doSend(). Each case maps to
 * an exact branch in the production code so a refactor that drifts behavior fails here.
 */
class ChatSendLogicTest {

    // ── composeSendText: file/text merge logic (doSend L3290–3293) ──────────
    @Test fun text_only_returns_text() =
        assertEquals("hola", ChatSendLogic.composeSendText("hola", ""))

    @Test fun fileBlock_only_returns_fileBlock() =
        assertEquals("fb", ChatSendLogic.composeSendText("", "fb"))

    @Test fun both_returns_text_double_newline_fileBlock() =
        assertEquals("hola\n\nfb", ChatSendLogic.composeSendText("hola", "fb"))

    @Test fun both_empty_returns_empty() =
        assertEquals("", ChatSendLogic.composeSendText("", ""))

    // ── computeSeedTitle / summarizeTitle: título desde el primer envío ──────
    @Test fun placeholder_with_text_seeds_from_summary() {
        assertEquals("Explícame cómo funciona Klipper",
            ChatSendLogic.computeSeedTitle(
                text = "explícame cómo funciona Klipper",
                firstFileName = null, defaultTitle = "Nuevo chat", isPlaceholderTitle = true))
    }

    @Test fun summarize_collapses_newlines_and_spaces() {
        assertEquals("Hola mundo", ChatSendLogic.summarizeTitle("hola\n\n   mundo"))
    }

    @Test fun summarize_drops_code_fences_and_urls() {
        val t = "mira https://ejemplo.com/x y ```code``` esto"
        assertEquals("Mira y esto", ChatSendLogic.summarizeTitle(t))
    }

    @Test fun summarize_caps_at_word_boundary() {
        val t = "esta es una frase bastante larga que debería recortarse en alguna palabra"
        val out = ChatSendLogic.summarizeTitle(t)
        assertTrue("len=${out.length}", out.length <= 40)
        assertFalse("corte a media palabra: $out", out.endsWith("palab"))
    }

    @Test fun summarize_keeps_opening_question_mark_and_capitalizes() {
        assertEquals("¿Qué es un nodo?", ChatSendLogic.summarizeTitle("¿qué es un nodo?"))
    }

    @Test fun summarize_empty_when_only_code_or_url() {
        assertEquals("", ChatSendLogic.summarizeTitle("```solo codigo```"))
        assertEquals("", ChatSendLogic.summarizeTitle("https://solo.url/x"))
    }

    @Test fun placeholder_empty_text_falls_back_to_firstFile() {
        assertEquals("model.gguf", ChatSendLogic.computeSeedTitle(
            text = "", firstFileName = "model.gguf",
            defaultTitle = "Nuevo chat", isPlaceholderTitle = true))
    }

    @Test fun placeholder_empty_text_and_file_falls_back_to_default() {
        assertEquals("Nuevo chat", ChatSendLogic.computeSeedTitle(
            text = "", firstFileName = null,
            defaultTitle = "Nuevo chat", isPlaceholderTitle = true))
    }

    @Test fun non_placeholder_returns_default_untouched() {
        assertEquals("Previous session title", ChatSendLogic.computeSeedTitle(
            text = "ignored text", firstFileName = "ignored.gguf",
            defaultTitle = "Previous session title", isPlaceholderTitle = false))
    }

    @Test fun seeded_title_never_exceeds_40_chars() {
        val out = ChatSendLogic.computeSeedTitle(
            text = "this is a very very long user prompt with many many words inside",
            firstFileName = null, defaultTitle = "Nuevo chat", isPlaceholderTitle = true)
        assertTrue("len=${out.length}", out.length <= 40)
    }

    // ── countImages: userImage "|" split (doSend L3372, tagHint L320) ────────
    @Test fun null_images_is_zero() =
        assertEquals(0, ChatSendLogic.countImages(null))

    @Test fun empty_string_is_zero() =
        assertEquals(0, ChatSendLogic.countImages(""))

    @Test fun single_image() =
        assertEquals(1, ChatSendLogic.countImages("u_1.jpg"))

    @Test fun multiple_trailing_empty_ignored() =
        assertEquals(2, ChatSendLogic.countImages("a.jpg|b.jpg|"))

    // ── buildTagHint: notice + tool prompt (doSend L3358, tagHint L319–322) ─
    @Test fun buildTagHint_concatenates_count_and_prompt() {
        val rendered = ChatSendLogic.buildTagHint(2, "Noticed %d images", "TOOL")
        assertEquals("Noticed 2 images\nTOOL", rendered)
    }

    @Test fun buildTagHint_zero_then_prompt() {
        val rendered = ChatSendLogic.buildTagHint(0, "Noticed %d images", "TOOL")
        assertEquals("Noticed 0 images\nTOOL", rendered)
    }

    @Test fun placeholder_detected_across_language() {
        // Sesión creada en inglés y app en español (el caso real que dejó títulos sin actualizar)
        assertTrue(ChatSendLogic.isPlaceholderTitle("New chat", "Nuevo chat"))
        assertTrue(ChatSendLogic.isPlaceholderTitle("Nuevo chat", "Nuevo chat"))
        assertTrue(ChatSendLogic.isPlaceholderTitle("  ", "Nuevo chat"))
        assertFalse(ChatSendLogic.isPlaceholderTitle("Explica qué es un nodo", "Nuevo chat"))
    }
}
