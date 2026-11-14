// Yumenode AI · (c) 2026 visitor257 · MIT License
// ChatState: data classes for chat sessions, shared between MainActivity and SessionManager.
package com.yumenode.ai

/** One turn in a conversation thread. */
data class QaTurn(
    val user: String,
    var answer: String = "",
    var thought: String = "",
    /** Drawing round: the local file name of the generated image under filesDir/chatimg/; null for non-drawing rounds */
    var image: String? = null,
    /** File name of the image this round's user sent (filesDir/chatimg/); null if no image */
    var userImage: String? = null,
    /** File name of the file this round's user sent (| separated); null if no file */
    var userFile: String? = null,
) {
    /** Body block of the file this round (header + trailing instruction): goes into inference, not into chat history */
    var fileText: String? = null
    /** Original content of this round's file (JSON: name/bytes/body/truncated), used to restore attachments on "regenerate" */
    var fileStore: String? = null
}

/** A standalone conversation, with its own complete history. */
class ChatSession(var id: Long, var title: String) {
    val turns = mutableListOf<QaTurn>()
}
