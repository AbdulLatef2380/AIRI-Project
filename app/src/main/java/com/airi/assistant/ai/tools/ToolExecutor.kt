package com.airi.assistant.ai.tools

import android.content.Context
import com.airi.assistant.ai.intent.ToolCall
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.error.AppError
import com.airi.assistant.domain.error.AppErrorHandler

class ToolExecutor(private val context: Context) {

    private val registry = ToolRegistry(context)

    suspend fun execute(toolCall: ToolCall): ToolResult {
        val networkService = runCatching { ServiceLocator.networkService }.getOrNull()
        val isOnline = networkService?.isOnline() ?: registry.isNetworkAvailable()

        if (!isOnline) {
            val error = AppError.NetworkUnavailable(
                "This feature requires a connection. Tool: ${toolCall.toolName.replace("_", " ")}"
            )
            AppErrorHandler.log(error)
            return ToolResult(success = false, data = "", error = AppErrorHandler.toUserMessage(error))
        }

        val tool = registry.getToolByName(toolCall.toolName)
            ?: return ToolResult(
                success = false,
                data    = "",
                error   = "Tool '${toolCall.toolName}' is not available. " +
                          "Make sure the required integration is connected."
            )

        return try {
            tool.execute(toolCall.params)
        } catch (e: Exception) {
            val error = AppErrorHandler.capture(e, "ToolExecutor[${toolCall.toolName}]")
            ToolResult(success = false, data = "", error = AppErrorHandler.toUserMessage(error))
        }
    }
}
