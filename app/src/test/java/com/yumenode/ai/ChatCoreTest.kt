// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de caracterización de [ChatCore.mergeStream], la única lógica pura de
 * la pieza sesiones/render (extractText/mergeStreamDelta en MainActivity delegan
 * en ChatCore, ya instrumentado). Fijan el comportamiento antes del refactor.
 */
class ChatCoreTest {

    @Test fun empty_cur_appends_delta() =
        assertEquals("abc", ChatCore.mergeStream("", "abc"))

    @Test fun empty_delta_keeps_cur() =
        assertEquals("abc", ChatCore.mergeStream("abc", ""))

    @Test fun empty_both_stays_empty() =
        assertEquals("", ChatCore.mergeStream("", ""))

    @Test fun identical_snapshot_is_dropped() =
        assertEquals("abc", ChatCore.mergeStream("abc", "abc"))

    @Test fun longer_snapshot_replaces() =
        assertEquals("abcd", ChatCore.mergeStream("abc", "abcd"))

    @Test fun incremental_delta_appends() =
        assertEquals("abcdef", ChatCore.mergeStream("abc", "def"))

    @Test fun tail_repeat_is_dropped() =
        assertEquals("abcdef", ChatCore.mergeStream("abcdef", "def"))

    @Test fun tail_repeat_ignoring_whitespace_is_dropped() =
        assertEquals("abcdef", ChatCore.mergeStream("abcdef", "  def"))

    @Test fun snapshot_starting_with_cur_replaces() =
        assertEquals("abcX", ChatCore.mergeStream("abc", "abcX"))
}
