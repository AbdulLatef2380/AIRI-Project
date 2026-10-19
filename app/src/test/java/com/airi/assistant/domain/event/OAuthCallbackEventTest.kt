package com.airi.assistant.domain.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthCallbackEventTest {
    @Test
    fun githubCallbackEventCarriesProviderMetadataWithoutChangingCallbackPayload() {
        val event = AppEvent.OAuthCallbackReceived(
            code = "code-fixture",
            state = "state-fixture",
            provider = "github",
        )

        assertEquals("github", event.provider)
        assertEquals("code-fixture", event.code)
        assertEquals("state-fixture", event.state)
        assertTrue(event.eventId.isNotBlank())
    }
}
