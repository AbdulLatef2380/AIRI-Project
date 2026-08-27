package com.airi.assistant.ui.screens

/**
 * Availability of actions that operate on a persisted chat session.
 *
 * An empty automatically created conversation must not expose actions that
 * imply a transcript or durable user content. A deliberate title still permits
 * organising the saved conversation, but never sharing or exporting an empty
 * transcript.
 */
internal data class ChatSessionActionAvailability(
    val canShareOrExport: Boolean,
    val canPinOrRename: Boolean,
)

internal object ChatSessionActionPolicy {
    private val automaticTitles = setOf("new chat")

    fun availability(
        hasPersistedSession: Boolean,
        sessionId: String,
        messageCount: Int,
        title: String,
    ): ChatSessionActionAvailability {
        val validSession = hasPersistedSession && sessionId.isNotBlank()
        val hasMessages = messageCount > 0
        val hasExplicitTitle = title.trim().lowercase() !in automaticTitles && title.isNotBlank()
        return ChatSessionActionAvailability(
            canShareOrExport = validSession && hasMessages,
            canPinOrRename = validSession && (hasMessages || hasExplicitTitle),
        )
    }
}
