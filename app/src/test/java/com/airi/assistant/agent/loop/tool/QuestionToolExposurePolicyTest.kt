package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.QueryType
import com.airi.assistant.domain.intent.ChatIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionToolExposurePolicyTest {
    private val tools = listOf(
        ToolSchema(name = "current_time", description = "time"),
        ToolSchema(name = "web_search", description = "search"),
        ToolSchema(name = "memory_recall", description = "memory"),
        ToolSchema(name = "set_alarm", description = "alarm", dangerous = true),
        ToolSchema(name = "calendar_read", description = "calendar"),
    )

    @Test
    fun remoteSimpleQuestionKeepsOnlyTheReadOnlyLookupTools() {
        val selected = QuestionToolExposurePolicy.select(
            ChatIntent.Kind.QUESTION, QueryType.SIMPLE, "openai", tools,
        )
        assertEquals(setOf("current_time", "web_search", "memory_recall"), selected.map { it.name }.toSet())
    }

    @Test
    fun localProviderKeepsTheNoToolFastPath() {
        assertTrue(QuestionToolExposurePolicy.select(ChatIntent.Kind.QUESTION, QueryType.SIMPLE, "local", tools).isEmpty())
        assertTrue(QuestionToolExposurePolicy.select(ChatIntent.Kind.QUESTION, QueryType.SIMPLE, "", tools).isEmpty())
    }

    @Test
    fun conversationAndCreativeTurnsStayToolFree() {
        assertTrue(QuestionToolExposurePolicy.select(ChatIntent.Kind.CONVERSATION, QueryType.SIMPLE, "openai", tools).isEmpty())
        assertTrue(QuestionToolExposurePolicy.select(ChatIntent.Kind.CREATIVE, QueryType.CREATIVE, "openai", tools).isEmpty())
    }

    @Test
    fun unclassifiedRequestOnRemoteProviderGetsOnlyTheReadOnlyLookupTools() {
        val selected = QuestionToolExposurePolicy.selectUnclassified(
            queryType = QueryType.UNKNOWN,
            capabilityRequiresTools = false,
            userSelectedSkills = false,
            providerId = "openai",
            availableTools = tools,
        )
        assertEquals(setOf("current_time", "web_search", "memory_recall"), selected!!.map { it.name }.toSet())
    }

    @Test
    fun unclassifiedRequestOnTheDeviceModelGetsNoTools() {
        val selected = QuestionToolExposurePolicy.selectUnclassified(
            QueryType.UNKNOWN, false, false, "local", tools,
        )
        assertTrue(selected!!.isEmpty())
    }

    @Test
    fun unclassifiedRequestIsNotNarrowedWhenACapabilityOrSkillWasChosen() {
        assertEquals(null, QuestionToolExposurePolicy.selectUnclassified(QueryType.UNKNOWN, true, false, "openai", tools))
        assertEquals(null, QuestionToolExposurePolicy.selectUnclassified(QueryType.UNKNOWN, false, true, "openai", tools))
    }

    @Test
    fun otherQueryTypesAreNeverNarrowedByTheUnclassifiedRule() {
        listOf(QueryType.ACTION, QueryType.ANALYTICAL, QueryType.SIMPLE, QueryType.CREATIVE).forEach { type ->
            assertEquals(null, QuestionToolExposurePolicy.selectUnclassified(type, false, false, "openai", tools))
        }
    }

    @Test
    fun localProviderWithRoomKeepsOnlyOfflineSafeTools() {
        val selected = QuestionToolExposurePolicy.select(
            ChatIntent.Kind.QUESTION, QueryType.SIMPLE, "local", tools, localAvailableTokens = 4_096,
        )
        assertEquals(setOf("current_time", "memory_recall"), selected.map { it.name }.toSet())
    }

    @Test
    fun localProviderWithSmallWindowStaysToolFree() {
        assertTrue(
            QuestionToolExposurePolicy.select(
                ChatIntent.Kind.QUESTION, QueryType.SIMPLE, "local", tools, localAvailableTokens = 1_024,
            ).isEmpty()
        )
    }
}
