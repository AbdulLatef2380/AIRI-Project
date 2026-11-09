package com.airi.assistant.ui.viewmodel

/** Asks the chat screen to open [sessionId] and bring [messageId] into view (used by the agent log). */
data class MessageFocusRequest(val sessionId: String, val messageId: Long)

/** What the chat list should do with a pending [MessageFocusRequest] on this composition. */
sealed interface MessageFocusDecision {
    /** Scroll the lazy list to this item index (already includes the items before the messages). */
    data class ScrollTo(val index: Int) : MessageFocusDecision
    /** The session is still loading; check again when messages or the load state change. */
    data object Wait : MessageFocusDecision
    /** The message cannot be shown (deleted, filtered out, load failed); forget the request. */
    data object Drop : MessageFocusDecision
}

/** Pure decision logic, kept apart from Compose so it can be unit-tested. */
object MessageFocusPolicy {
    /**
     * @param reversedMessageIds ids in list order (the chat list uses reverseLayout, newest first).
     * @param leadingItems number of lazy items that sit before the messages in the list
     *   (stopped notice, error bubble, streaming bubble, thinking row, verification badge).
     */
    fun decide(
        request: MessageFocusRequest,
        reversedMessageIds: List<Long>,
        leadingItems: Int,
        loadState: SessionLoadState,
    ): MessageFocusDecision {
        if (loadState is SessionLoadState.Loading) return MessageFocusDecision.Wait
        if (loadState is SessionLoadState.Failed) return MessageFocusDecision.Drop
        val index = reversedMessageIds.indexOf(request.messageId)
        return if (index >= 0) MessageFocusDecision.ScrollTo(leadingItems.coerceAtLeast(0) + index)
        else MessageFocusDecision.Drop
    }
}
