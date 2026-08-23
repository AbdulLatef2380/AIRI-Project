package com.airi.assistant.agent.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrowserUserTakeoverCoordinatorTest {

    @Before
    fun setUp() {
        BrowserUserTakeoverCoordinator.pending.value?.let { request ->
            BrowserUserTakeoverCoordinator.dismiss(request.id)
        }
    }

    @After
    fun tearDown() {
        BrowserUserTakeoverCoordinator.pending.value?.let { request ->
            BrowserUserTakeoverCoordinator.dismiss(request.id)
        }
    }

    @Test
    fun publicHttpsRequestIsPresentedOnceAndIsNormalizedBeforeUi() {
        val first = BrowserUserTakeoverCoordinator.request(
            rawUrl = "https://example.com/path",
            reason = "Open public browser"
        )
        val duplicate = BrowserUserTakeoverCoordinator.request(
            rawUrl = "https://example.com/path",
            reason = "Ignored duplicate reason"
        )

        assertNotNull(first)
        assertTrue(first!!.normalizedUrl.startsWith("https://"))
        assertSame(first, duplicate)
        assertEquals(first, BrowserUserTakeoverCoordinator.pending.value)
    }

    @Test
    fun privateOrUnsupportedUrlNeverCreatesVisibleTakeoverRequest() {
        assertNull(
            BrowserUserTakeoverCoordinator.request(
                rawUrl = "http://127.0.0.1:8080/admin",
                reason = "Must not surface"
            )
        )
        assertNull(BrowserUserTakeoverCoordinator.pending.value)
    }

    @Test
    fun onlyMatchingRequestIdCanDismissPendingHandoff() {
        val request = BrowserUserTakeoverCoordinator.request(
            rawUrl = "https://example.com",
            reason = "Open public browser"
        )!!

        BrowserUserTakeoverCoordinator.dismiss("unrelated-request")
        assertEquals(request, BrowserUserTakeoverCoordinator.pending.value)

        BrowserUserTakeoverCoordinator.dismiss(request.id)
        assertNull(BrowserUserTakeoverCoordinator.pending.value)
    }
}
