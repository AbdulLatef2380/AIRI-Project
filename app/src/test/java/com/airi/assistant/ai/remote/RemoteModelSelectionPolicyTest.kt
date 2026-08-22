package com.airi.assistant.ai.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteModelSelectionPolicyTest {

    @Test
    fun selectsRegisteredModel() {
        val decision = RemoteModelSelectionPolicy.decide(
            availableIds = setOf("remote-a", "remote-b"),
            requestedId = "remote-b"
        )

        assertEquals(RemoteModelSelectionPolicy.Decision.Select("remote-b"), decision)
    }

    @Test
    fun rejectsUnknownModel() {
        val decision = RemoteModelSelectionPolicy.decide(
            availableIds = setOf("remote-a"),
            requestedId = "unknown"
        )

        assertEquals(RemoteModelSelectionPolicy.Decision.RejectUnknown, decision)
    }

    @Test
    fun rejectsBlankModel() {
        val decision = RemoteModelSelectionPolicy.decide(
            availableIds = setOf("remote-a"),
            requestedId = " "
        )

        assertEquals(RemoteModelSelectionPolicy.Decision.RejectBlank, decision)
    }
}
