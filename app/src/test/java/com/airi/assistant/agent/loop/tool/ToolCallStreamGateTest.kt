package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallStreamGateTest {
    private fun run(tokens: List<String>): Pair<String, ToolCallStreamGate> {
        val gate = ToolCallStreamGate()
        val shown = StringBuilder()
        tokens.forEach { shown.append(gate.accept(it)) }
        return shown.toString() to gate
    }

    @Test
    fun plainTextStreamsUntouched() {
        val (shown, gate) = run(listOf("Hello", " wor", "ld"))
        assertEquals("Hello world", shown)
        assertFalse(gate.suppressing)
    }

    @Test
    fun markerSplitAcrossTokensIsDetected() {
        val (_, gate) = run(listOf("{\"to", "ol_ca", "ll\":{\"name\":\"x\"}}"))
        assertTrue(gate.suppressing)
    }

    @Test
    fun batchEnvelopeIsAlsoHidden() {
        // The old check looked for {"tool_call" with a closing quote and missed {"tool_calls".
        val (_, gate) = run(listOf("{\"tool_calls\":[{\"name\":\"a\"}]}"))
        assertTrue(gate.suppressing)
    }

    @Test
    fun realToolCallIsNotReleasedToTheUser() {
        val call = "{\"tool_call\":{\"name\":\"web_search\",\"args\":{\"q\":\"x\"}}}"
        val (_, gate) = run(listOf(call.take(10), call.drop(10)))
        assertEquals("", gate.releaseIfNotToolCall(call))
    }

    @Test
    fun malformedToolCallIsLeftToTheRetryPath() {
        val broken = "{\"tool_call\":{\"name\":"
        val (_, gate) = run(listOf(broken))
        assertEquals("", gate.releaseIfNotToolCall(broken))
    }

    @Test
    fun literalMarkerInProseIsReleasedSoTheStreamIsNotFrozen() {
        // Report item 19: a literal marker in ordinary text used to hide the rest of the reply.
        val tokens = listOf("The key is ", "{\"tool_calls_count\"", ": 3 in the log. ", "More text follows.")
        val (shown, gate) = run(tokens)
        assertTrue(gate.suppressing)
        val full = tokens.joinToString("")
        val released = gate.releaseIfNotToolCall(full)
        assertEquals(tokens.drop(1).joinToString(""), released)
        assertTrue((shown + released).endsWith("More text follows."))
    }

    @Test
    fun workPerTokenDoesNotGrowWithReplyLength() {
        // Report item 20: 200k single-char tokens must finish quickly (the old check was O(n^2)).
        val gate = ToolCallStreamGate()
        val start = System.nanoTime()
        repeat(200_000) { gate.accept("a") }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took ${ms}ms", ms < 2_000)
    }
}
