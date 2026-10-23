package com.airi.assistant.ai.skills.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class GmailMessageListParserTest {
    @Test
    fun preservesConnectorOrderAndHonorsRequestedMaximum() {
        val data = """
            Found 3 messages:
            Message ID: m3
            Message ID: m2
            Message ID: m1
        """.trimIndent()

        assertEquals(listOf("m3", "m2"), GmailMessageListParser.messageIds(data, 2))
    }

    @Test
    fun removesDuplicateIdsWithoutChangingFirstOccurrenceOrder() {
        val data = "Message ID: m1\nMessage ID: m1\nMessage ID: m2"

        assertEquals(listOf("m1", "m2"), GmailMessageListParser.messageIds(data, 5))
    }

    @Test
    fun clampsMaximumToSafeSkillLimit() {
        val data = (1..25).joinToString("\n") { "Message ID: m$it" }

        assertEquals(20, GmailMessageListParser.messageIds(data, 50).size)
    }
}
