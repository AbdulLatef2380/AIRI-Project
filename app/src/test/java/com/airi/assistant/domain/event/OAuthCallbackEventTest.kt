package com.airi.assistant.domain.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthCallbackEventTest {
    @Test
    fun completionEventCarriesOnlyProviderMetadata() {
        val event = AppEvent.OAuthCallbackCompleted(provider = "github")

        assertEquals("github", event.provider)
        assertTrue(event.eventId.isNotBlank())
    }
}
