package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.ExecutionRequest.ToolChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolChoiceJsonTest {
    @Test
    fun openAiUsesRequiredOnlyWhenForced() {
        assertEquals("\"auto\"", ToolChoiceJson.openAiValue(ToolChoice.AUTO))
        assertEquals("\"required\"", ToolChoiceJson.openAiValue(ToolChoice.REQUIRED))
    }

    @Test
    fun geminiForcesAnyFunctionCallOnlyWithToolsAndWhenRequired() {
        assertEquals(
            "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\"}},",
            ToolChoiceJson.geminiMember(ToolChoice.REQUIRED, hasTools = true),
        )
        assertEquals("", ToolChoiceJson.geminiMember(ToolChoice.AUTO, hasTools = true))
        assertEquals("", ToolChoiceJson.geminiMember(ToolChoice.REQUIRED, hasTools = false))
    }

    @Test
    fun anthropicForcesAnyToolOnlyWithToolsAndWhenRequired() {
        assertEquals(
            ",\"tool_choice\":{\"type\":\"any\"}",
            ToolChoiceJson.anthropicMember(ToolChoice.REQUIRED, hasTools = true),
        )
        assertEquals("", ToolChoiceJson.anthropicMember(ToolChoice.AUTO, hasTools = true))
        assertEquals("", ToolChoiceJson.anthropicMember(ToolChoice.REQUIRED, hasTools = false))
    }

    @Test
    fun noneForbidsToolsOnEveryProvider() {
        assertEquals("\"none\"", ToolChoiceJson.openAiValue(ToolChoice.NONE))
        assertEquals(
            "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"NONE\"}},",
            ToolChoiceJson.geminiMember(ToolChoice.NONE, hasTools = true),
        )
        assertEquals(",\"tool_choice\":{\"type\":\"none\"}", ToolChoiceJson.anthropicMember(ToolChoice.NONE, hasTools = true))
    }

    @Test
    fun specificToolIsNamedOnEveryProvider() {
        assertEquals(
            "{\"type\":\"function\",\"function\":{\"name\":\"write_file\"}}",
            ToolChoiceJson.openAiValue(ToolChoice.SPECIFIC, "write_file"),
        )
        assertEquals(
            "{\"type\":\"function\",\"name\":\"write_file\"}",
            ToolChoiceJson.openAiResponsesValue(ToolChoice.SPECIFIC, "write_file"),
        )
        assertEquals(
            "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"write_file\"]}},",
            ToolChoiceJson.geminiMember(ToolChoice.SPECIFIC, hasTools = true, toolName = "write_file"),
        )
        assertEquals(
            ",\"tool_choice\":{\"type\":\"tool\",\"name\":\"write_file\"}",
            ToolChoiceJson.anthropicMember(ToolChoice.SPECIFIC, hasTools = true, toolName = "write_file"),
        )
    }

    @Test
    fun specificWithoutNameDegradesToRequiredAndNameIsSanitised() {
        assertEquals("\"required\"", ToolChoiceJson.openAiValue(ToolChoice.SPECIFIC, ""))
        assertEquals(
            "{\"type\":\"function\",\"function\":{\"name\":\"bad_name\"}}",
            ToolChoiceJson.openAiValue(ToolChoice.SPECIFIC, "bad\"_name"),
        )
        assertEquals("", ToolChoiceJson.anthropicMember(ToolChoice.SPECIFIC, hasTools = false, toolName = "x"))
    }
}
