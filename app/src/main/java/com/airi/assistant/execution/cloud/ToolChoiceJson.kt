package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.ExecutionRequest.ToolChoice

/**
 * Provider wire fragments for [ToolChoice]. Pure, so each provider's payload shape is unit-tested
 * without a network. Every function returns an empty string when there are no tools: a tool
 * choice without tools is rejected by the providers.
 *
 * SPECIFIC with a blank name degrades to REQUIRED (any tool) rather than producing an invalid
 * payload. Tool names are restricted to `[A-Za-z0-9_.-]`, so they never need JSON escaping.
 */
internal object ToolChoiceJson {
    private fun safeName(name: String): String = name.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }

    private fun effective(choice: ToolChoice, name: String): ToolChoice =
        if (choice == ToolChoice.SPECIFIC && safeName(name).isEmpty()) ToolChoice.REQUIRED else choice

    /** Value of OpenAI Chat `tool_choice`, e.g. `"auto"` (quotes included). */
    fun openAiValue(choice: ToolChoice, toolName: String = ""): String = when (effective(choice, toolName)) {
        ToolChoice.REQUIRED -> "\"required\""
        ToolChoice.NONE -> "\"none\""
        ToolChoice.SPECIFIC -> "{\"type\":\"function\",\"function\":{\"name\":\"${safeName(toolName)}\"}}"
        ToolChoice.AUTO -> "\"auto\""
    }

    /** Value of OpenAI Responses `tool_choice`: the specific-function shape is flat there. */
    fun openAiResponsesValue(choice: ToolChoice, toolName: String = ""): String =
        if (effective(choice, toolName) == ToolChoice.SPECIFIC)
            "{\"type\":\"function\",\"name\":\"${safeName(toolName)}\"}"
        else openAiValue(choice, toolName)

    /** Gemini top-level member, with trailing comma, placed before `"contents"`. */
    fun geminiMember(choice: ToolChoice, hasTools: Boolean, toolName: String = ""): String {
        if (!hasTools) return ""
        return when (effective(choice, toolName)) {
            ToolChoice.REQUIRED -> "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\"}},"
            ToolChoice.NONE -> "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"NONE\"}},"
            ToolChoice.SPECIFIC ->
                "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"ANY\",\"allowedFunctionNames\":[\"${safeName(toolName)}\"]}},"
            ToolChoice.AUTO -> ""
        }
    }

    /** Anthropic top-level member, with leading comma, placed after `"tools"`. */
    fun anthropicMember(choice: ToolChoice, hasTools: Boolean, toolName: String = ""): String {
        if (!hasTools) return ""
        return when (effective(choice, toolName)) {
            ToolChoice.REQUIRED -> ",\"tool_choice\":{\"type\":\"any\"}"
            ToolChoice.NONE -> ",\"tool_choice\":{\"type\":\"none\"}"
            ToolChoice.SPECIFIC -> ",\"tool_choice\":{\"type\":\"tool\",\"name\":\"${safeName(toolName)}\"}"
            ToolChoice.AUTO -> ""
        }
    }
}
