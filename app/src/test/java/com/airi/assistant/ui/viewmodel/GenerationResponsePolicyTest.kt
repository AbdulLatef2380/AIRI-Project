package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationResponsePolicyTest {
    @Test
    fun blankCompletionIsNotSuccessful() {
        assertEquals(
            GenerationResponseStatus.EMPTY_RESPONSE,
            GenerationResponsePolicy.classify("", "", cancelled = false)
        )
    }

    @Test
    fun streamedAnswerCanRescueBlankFinalCompletion() {
        assertEquals(
            GenerationResponseStatus.SUCCESS,
            GenerationResponsePolicy.classify("", "visible answer", cancelled = false)
        )
    }

    @Test
    fun cancellationWinsOverPartialText() {
        assertEquals(
            GenerationResponseStatus.CANCELLED,
            GenerationResponsePolicy.classify("partial", "partial", cancelled = true)
        )
    }
}

class ReasoningStreamParserTest {
    @Test
    fun splitThinkTagsNeverLeakReasoningIntoAnswer() {
        val parser = ReasoningStreamParser()
        val visible = buildString {
            append(parser.consume("<thi"))
            append(parser.consume("nk>private reasoning"))
            append(parser.consume("</thin"))
            append(parser.consume("k>final answer"))
            append(parser.finish())
        }
        assertEquals("final answer", visible.trim())
    }

    @Test
    fun structuredAnalysisIsRemovedFromFinalAnswer() {
        assertEquals(
            "answer",
            ReasoningStreamParser.extractAnswer("<analysis>hidden</analysis>answer")
        )
    }

    @Test
    fun ordinaryTextRemainsVisible() {
        val parser = ReasoningStreamParser()
        val visible = parser.consume("مرحباً AIRI") + parser.finish()
        assertTrue(visible.contains("مرحباً AIRI"))
    }
}
