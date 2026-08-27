package com.airi.assistant.ui.viewmodel

import com.airi.assistant.domain.ChatAttachment

/**
 * A transient, session-owned composer state.
 *
 * A draft stays in memory for the ViewModel lifetime only. Picker URIs and
 * camera bitmaps are therefore never persisted as chat metadata before a user
 * sends them. Keeping the state keyed by the durable conversation id prevents
 * a draft from appearing in a different conversation after navigation.
 */
data class ChatComposerDraft(
    val text: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
)

internal object ChatComposerDraftPolicy {
    fun current(
        drafts: Map<String, ChatComposerDraft>,
        sessionId: String,
    ): ChatComposerDraft = drafts[sessionId].orEmpty()

    fun replaceText(
        drafts: Map<String, ChatComposerDraft>,
        sessionId: String,
        text: String,
    ): Map<String, ChatComposerDraft> = update(drafts, sessionId) { it.copy(text = text) }

    fun replaceAttachments(
        drafts: Map<String, ChatComposerDraft>,
        sessionId: String,
        attachments: List<ChatAttachment>,
    ): Map<String, ChatComposerDraft> = update(drafts, sessionId) { it.copy(attachments = attachments) }

    fun clearAttachments(
        drafts: Map<String, ChatComposerDraft>,
        sessionId: String,
    ): Map<String, ChatComposerDraft> = replaceAttachments(drafts, sessionId, emptyList())

    fun removeSession(
        drafts: Map<String, ChatComposerDraft>,
        sessionId: String,
    ): Map<String, ChatComposerDraft> = drafts - sessionId

    private fun update(
        drafts: Map<String, ChatComposerDraft>,
        sessionId: String,
        transform: (ChatComposerDraft) -> ChatComposerDraft,
    ): Map<String, ChatComposerDraft> {
        if (sessionId.isBlank()) return drafts
        val updated = transform(current(drafts, sessionId))
        return if (updated.text.isBlank() && updated.attachments.isEmpty()) {
            drafts - sessionId
        } else {
            drafts + (sessionId to updated)
        }
    }
}

private fun ChatComposerDraft?.orEmpty(): ChatComposerDraft = this ?: ChatComposerDraft()
