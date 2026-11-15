package com.airi.assistant.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTurnContextResolverTest {
    @Test
    fun scopesEvidenceToSelectedAssistantTurn() {
        val messages = listOf(
            ResponseTurnMessageEvidence(1, true, "old", hasImages = true),
            ResponseTurnMessageEvidence(2, false, "old answer"),
            ResponseTurnMessageEvidence(3, true, "current", hasFiles = true),
            ResponseTurnMessageEvidence(4, false, "current answer", hasToolOutputs = true),
        )

        val context = CurrentTurnContextResolver.resolve(messages, assistantMessageId = 4)

        assertEquals("current", context.latestUserText)
        assertTrue(context.hasFiles)
        assertTrue(context.hasToolOutputs)
        assertFalse(context.hasImages)
        assertEquals(4, context.messageCount)
    }

    @Test
    fun unknownAssistantIdDoesNotAdmitHistoricalEvidence() {
        val context = CurrentTurnContextResolver.resolve(
            listOf(ResponseTurnMessageEvidence(1, true, "old", hasImages = true)),
            assistantMessageId = 99,
        )

        assertFalse(context.hasImages)
        assertEquals("", context.latestUserText)
    }

    @Test
    fun exposesOnlySelectedTurnIdsWhileRetainingHistoryMetrics() {
        val context = CurrentTurnContextResolver.resolve(
            listOf(
                ResponseTurnMessageEvidence(1, true, "old", hasImages = true),
                ResponseTurnMessageEvidence(2, false, "old answer"),
                ResponseTurnMessageEvidence(3, true, "current"),
                ResponseTurnMessageEvidence(4, false, "answer"),
            ),
            assistantMessageId = 4,
        )

        assertEquals(listOf(3L, 4L), context.currentTurnMessageIds)
        assertEquals(4, context.messageCount)
        assertFalse(context.hasImages)
    }
}
***
