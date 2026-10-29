package com.airi.assistant.execution.backend

import com.airi.assistant.execution.ExecutionRequest

/**
 * Canonical tool-call contract for local models.
 *
 * Cloud adapters translate [ExecutionRequest.toolDefinitions] to their native
 * provider wire format. The llama.cpp JNI boundary currently accepts text only,
 * so local models receive the same definitions and emit the same normalized JSON
 * envelope that AgentLoop parses before dispatching through ToolDispatcher.
 */
internal object LocalToolCallPromptContract {
    private const val HEADER = "LOCAL TOOL CALL CONTRACT:"

    fun render(definitions: List<ExecutionRequest.ToolDefinition>): String {
        if (definitions.isEmpty()) return ""
        return buildString {
            appendLine(HEADER)
            appendLine("When a tool is needed, output ONLY one JSON object with no markdown or prose:")
            appendLine("{\"tool_call\":{\"name\":\"<tool_name>\",\"args\":{\"<param>\":\"<value>\"}}}")
            appendLine("For independent tools, output one ordered envelope:")
            appendLine("{\"tool_calls\":[{\"id\":\"call-1\",\"name\":\"<tool_name>\",\"args\":{}}]}")
            appendLine("Never mix a tool envelope with prose. For a final answer, use plain text.")
            appendLine("Allowed tools:")
            definitions.forEach { definition ->
                append("- ").append(definition.name).append(": ").appendLine(definition.description)
                definition.parameters.forEach { parameter ->
                    append("  - ").append(parameter.name)
                        .append(" (").append(parameter.type)
                        .append(if (parameter.required) ", required" else ", optional")
                        .append(")")
                    if (parameter.description.isNotBlank()) append(": ").append(parameter.description)
                    if (parameter.enumValues.isNotEmpty()) {
                        append(" [oneOf=").append(parameter.enumValues.joinToString("|")).append("]")
                    }
                    appendLine()
                }
            }
        }.trimEnd()
    }

    fun appendIfMissing(systemPrompt: String, definitions: List<ExecutionRequest.ToolDefinition>): String {
        if (definitions.isEmpty() || systemPrompt.contains(HEADER)) return systemPrompt
        return systemPrompt.trimEnd() + "\n\n" + render(definitions)
    }
}
