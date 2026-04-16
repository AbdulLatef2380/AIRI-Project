package com.airi.assistant.ai.tools

import android.content.Context
import com.airi.assistant.ai.intent.ToolCall

class ToolExecutor(private val context: Context) {

    private val registry = ToolRegistry(context)

    suspend fun execute(toolCall: ToolCall): ToolResult {
        if (!registry.isNetworkAvailable()) {
            val toolDisplayName = toolCall.toolName.replace("_", " ")
            return ToolResult(
                success = false,
                data = "",
                error = "This feature requires a connection. Tool: $toolDisplayName"
            )
        }

        val tool = registry.getToolByName(toolCall.toolName)
            ?: return ToolResult(
                success = false,
                data = "",
                error = "Tool '${toolCall.toolName}' is not available. " +
                        "Make sure the required integration is connected."
            )

        return try {
            tool.execute(toolCall.params)
        } catch (e: Exception) {
            ToolResult(
                success = false,
                data = "",
                error = "Tool execution failed: ${e.message ?: "Unknown error"}"
            )
        }
    }
}
