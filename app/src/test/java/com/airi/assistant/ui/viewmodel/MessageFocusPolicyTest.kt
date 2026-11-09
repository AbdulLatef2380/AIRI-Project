package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageFocusPolicyTest {

    private val request = MessageFocusRequest(sessionId = "s1", messageId = 30L)
    // The chat list is reversed: newest first.
    private val ids = listOf(50L, 40L, 30L, 20L)

    @Test
    fun waitsWhileTheSessionIsLoading() {
        val d = MessageFocusPolicy.decide(request, ids, 0, SessionLoadState.Loading)
        assertEquals(MessageFocusDecision.Wait, d)
    }

    @Test
    fun scrollsToTheMessageIndexPlusLeadingItems() {
        val d = MessageFocusPolicy.decide(request, ids, 2, SessionLoadState.Ready("s1", 4))
        assertEquals(MessageFocusDecision.ScrollTo(4), d)
    }

    @Test
    fun scrollsToIndexZeroForTheNewestMessage() {
        val newest = request.copy(messageId = 50L)
        val d = MessageFocusPolicy.decide(newest, ids, 0, SessionLoadState.Ready("s1", 4))
        assertEquals(MessageFocusDecision.ScrollTo(0), d)
    }

    @Test
    fun dropsTheRequestWhenTheMessageIsGone() {
        val gone = request.copy(messageId = 999L)
        val d = MessageFocusPolicy.decide(gone, ids, 0, SessionLoadState.Ready("s1", 4))
        assertEquals(MessageFocusDecision.Drop, d)
    }

    @Test
    fun dropsTheRequestWhenTheSessionFailedToLoad() {
        val d = MessageFocusPolicy.decide(request, ids, 0, SessionLoadState.Failed("db"))
        assertEquals(MessageFocusDecision.Drop, d)
    }

    @Test
    fun dropsTheRequestForAnEmptySession() {
        val d = MessageFocusPolicy.decide(request, emptyList(), 0, SessionLoadState.Empty)
        assertEquals(MessageFocusDecision.Drop, d)
    }

    @Test
    fun negativeLeadingItemCountIsTreatedAsZero() {
        val d = MessageFocusPolicy.decide(request, ids, -3, SessionLoadState.Ready("s1", 4))
        assertEquals(MessageFocusDecision.ScrollTo(2), d)
    }
}
