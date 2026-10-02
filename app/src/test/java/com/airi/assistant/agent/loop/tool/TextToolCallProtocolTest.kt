package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolCallProtocolTest {

    @Test
    fun plainTextIsNotMisclassifiedAsToolCall() {
        assertEquals(
            TextToolCallProtocol.ParseResult.NotAToolCall,
            TextToolCallProtocol.parse("The word tool_call may appear in prose.")
        )
    }

    @Test
    fun parsesWhitespaceCodeFenceAndScalarArguments() {
        val result = TextToolCallProtocol.parse(
            "```json\n { \"tool_call\" : { \"name\" : \"calendar_read\", \"args\" : { \"days\" : 7 } } }\n```"
        )

        assertEquals(
            TextToolCallProtocol.ParseResult.Call("calendar_read", mapOf("days" to "7")),
            result
        )
    }

    @Test
    fun matchingBracesInsideQuotedArgumentDoNotEndTheObjectEarly() {
        val result = TextToolCallProtocol.parse(
            "prefix {\"tool_call\":{\"name\":\"create_note\",\"args\":{\"content\":\"value } still text\"}}} suffix"
        )

        assertTrue(result is TextToolCallProtocol.ParseResult.Call)
        assertEquals(
            "value } still text",
            (result as TextToolCallProtocol.ParseResult.Call).args["content"]
        )
    }

    @Test
    fun malformedCandidateIsTypedForControlledRetry() {
        val result = TextToolCallProtocol.parse("{\"tool_call\":{\"name\":\"web_search\",\"args\":{")

        assertEquals(
            TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.UNTERMINATED_JSON),
            result
        )
    }

    @Test
    fun invalidShapeDoesNotBecomeExecutableCall() {
        val result = TextToolCallProtocol.parse(
            "{\"tool_call\":{\"name\":\"web_search\",\"args\":\"query\"}}"
        )

        assertEquals(
            TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.INVALID_ARGS_OBJECT),
            result
        )
    }

    @Test
    fun missingNameIsRejectedBeforeDispatch() {
        val result = TextToolCallProtocol.parse("{\"tool_call\":{\"args\":{}}}")

        assertEquals(
            TextToolCallProtocol.ParseResult.Invalid(TextToolCallProtocol.ParseFailureReason.MISSING_TOOL_NAME),
            result
        )
    }
}
