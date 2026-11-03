package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningStreamParserTest {
    @Test
    fun closedReasoningBlockIsHidden() {
        assertEquals("answer", ReasoningStreamParser.extractAnswer("<think>secret</think>answer"))
    }

    @Test
    fun splitTagsAcrossChunksAreHandled() {
        val parser = ReasoningStreamParser()
        val out = StringBuilder()
        listOf("he", "llo <thi", "nk>hidden</th", "ink> world").forEach { out.append(parser.consume(it)) }
        out.append(parser.finish())
        assertEquals("hello  world", out.toString())
    }

    @Test
    fun unclosedTagDoesNotEraseTheRestOfTheReply() {
        // Report item 17: everything after an unclosed tag used to vanish silently.
        val text = "Use the <think> tag like this:\n```html\n<think>x\n```\nThat is all."
        val answer = ReasoningStreamParser.extractAnswer(text)
        assertTrue(answer, answer.startsWith("Use the"))
        assertTrue(answer, answer.contains("That is all."))
    }

    @Test
    fun unclosedBlockIsHiddenWhileStreamingButReleasedOnFinish() {
        val parser = ReasoningStreamParser()
        val streamed = parser.consume("before <think>cut off reasoning") + parser.consume(" and more")
        assertEquals("before ", streamed)
        assertEquals("<think>cut off reasoning and more", parser.finish())
    }

    @Test
    fun finishIsIdempotent() {
        val parser = ReasoningStreamParser()
        parser.consume("<analysis>unfinished")
        assertEquals("<analysis>unfinished", parser.finish())
        assertEquals("", parser.finish())
    }

    @Test
    fun partialCloseTagAtEndIsPreservedOnFinish() {
        val parser = ReasoningStreamParser()
        parser.consume("<think>abc</thi")
        assertEquals("<think>abc</thi", parser.finish())
    }

    @Test
    fun laterClosedBlockAfterRecoveredTextStillWorks() {
        assertEquals("a b", ReasoningStreamParser.extractAnswer("a <reasoning>r</reasoning>b"))
    }
}
