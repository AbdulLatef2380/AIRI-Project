package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolCallProtocolTest {

    @Test
    fun plainTextIsNotMisclassifiedAsToolCall() {
        assertEquals(
            TextToolCallProtocol.ParseResult.NotAToolCall,
            TextToolCallProtocol.parse("The word tool_call may appear in prose."),
        )
    }

    @Test
    fun parsesWhitespaceCodeFenceAndScalarArguments() {
        val result = TextToolCallProtocol.parse(
            "```json\n { \"tool_call\" : { \"name\" : \"calendar_read\", \"args\" : { \"days\" : 7 } } }\n```",
        )

        assertEquals(
            TextToolCallProtocol.ParseResult.Call("calendar_read", mapOf("days" to "7")),
            result,
        )
    }

    @Test
    fun proseBeforeEnvelopeIsRejected() {
        val result = TextToolCallProtocol.parse(
            "I will call the tool now: {\"tool_call\":{\"name\":\"create_note\",\"args\":{\"content\":\"value\"}}}",
        )

        assertEquals(
            TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.MIXED_PROSE),
            result,
        )
    }

    @Test
    fun proseAfterEnvelopeIsRejected() {
        val result = TextToolCallProtocol.parse(
            "{\"tool_call\":{\"name\":\"create_note\",\"args\":{}}} Done.",
        )
        assertEquals(
            TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.MIXED_PROSE),
            result,
        )
    }

    @Test
    fun malformedCandidateIsTypedForControlledRetry() {
        val result = TextToolCallProtocol.parse("{\"tool_call\":{\"name\":\"web_search\",\"args\":{")

        assertEquals(
            TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.UNTERMINATED_JSON),
            result,
        )
    }

    @Test
    fun partialEnvelopeIsRejectedWithoutExtractingAUsablePrefix() {
        val result = TextToolCallProtocol.parse(
            "{\"tool_call\":{\"name\":\"web_search\",\"args\":{\"query\":\"AIRI\"}}",
        )
        assertEquals(
            TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.INVALID_JSON),
            result,
        )
    }

    @Test
    fun malformedEnvelopeIsRejected() {
        val result = TextToolCallProtocol.parse(
            "{\"tool_call\":{\"name\":\"web_search\",\"args\":{\"query\":\"AIRI}}}",
        )
        assertEquals(
            TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.INVALID_JSON),
            result,
        )
    }

    @Test
    fun invalidShapeDoesNotBecomeExecutableCall() {
        val result = TextToolCallProtocol.parse(
            "{\"tool_call\":{\"name\":\"web_search\",\"args\":\"query\"}}",
        )

        assertEquals(
            TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.INVALID_ARGS_OBJECT),
            result,
        )
    }

    @Test
    fun missingNameIsRejectedBeforeDispatch() {
        val result = TextToolCallProtocol.parse("{\"tool_call\":{\"args\":{}}}")

        assertEquals(
            TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.MISSING_TOOL_NAME),
            result,
        )
    }

    @Test
    fun parsesMultipleCallsInStableOrderWithIds() {
        val result = TextToolCallProtocol.parse(
            """{"tool_calls":[{"id":"call-1","name":"gmail_list","args":{"max":3}},{"id":"call-2","name":"gmail_read","args":{"id":"m1"}}]}""",
        )
        assertTrue(result is TextToolCallProtocol.ParseResult.Calls)
        val calls = (result as TextToolCallProtocol.ParseResult.Calls).calls
        assertEquals(listOf("call-1", "call-2"), calls.map { it.id })
        assertEquals(listOf("gmail_list", "gmail_read"), calls.map { it.toolName })
        assertEquals("3", calls.first().params["max"])
    }

    @Test
    fun encodeRoundTripKeepsMultipleCallsAndIds() {
        val calls = listOf(
            com.airi.assistant.ai.intent.ToolCall("gmail_list", mapOf("max" to "3"), "call-1"),
            com.airi.assistant.ai.intent.ToolCall("gmail_read", mapOf("id" to "m1"), "call-2"),
        )
        val result = TextToolCallProtocol.parse(TextToolCallProtocol.encode(calls))
        assertEquals(TextToolCallProtocol.ParseResult.Calls(calls), result)
    }

    @Test
    fun envelopeShownInsideACodeBlockIsNotExecuted() {
        val reply = "Here is how the protocol looks:\n```kotlin\nval s = \"\"\"{\"tool_call\":{\"name\":\"web_search\",\"args\":{}}}\"\"\"\n```\nThat is all."
        assertEquals(TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.MIXED_PROSE), TextToolCallProtocol.parse(reply))
    }

    @Test
    fun jsonBlockWithOtherContentFirstIsNotExecuted() {
        val reply = "```json\n{\"example\": true, \"note\": {\"tool_call\":{\"name\":\"x\"}}}\n```"
        assertEquals(TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.UNEXPECTED_ENVELOPE_CONTENT), TextToolCallProtocol.parse(reply))
    }

    @Test
    fun unrelatedTopLevelEnvelopeContentIsRejected() {
        val result = TextToolCallProtocol.parse(
            "{\"tool_call\":{\"name\":\"web_search\",\"args\":{}},\"note\":\"not executable\"}",
        )
        assertEquals(
            TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.UNEXPECTED_ENVELOPE_CONTENT),
            result,
        )
    }

    @Test
    fun untaggedFenceAroundTheEnvelopeStillParses() {
        val result = TextToolCallProtocol.parse("```\n{\"tool_call\":{\"name\":\"current_time\",\"args\":{}}}\n```")
        assertTrue(result is TextToolCallProtocol.ParseResult.Call)
    }
}
