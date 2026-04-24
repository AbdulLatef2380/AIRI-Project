package com.airi.assistant.ai.tools

import android.content.Context
import com.airi.assistant.ai.intent.ToolCall
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.customskill.CustomSkillRepository
import com.airi.assistant.domain.error.AppError
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.domain.skill.SkillService

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

        if (toolCall.toolName.startsWith("custom_skill_")) {
            val customSkill = CustomSkillRepository(context).getAllSkills()
                .firstOrNull { customSkillToolName(it) == toolCall.toolName }
                ?: return ToolResult(false, "", "Custom skill '${toolCall.toolName}' is not available.")
            return try {
                val result = SkillService(context).executeCustomSkill(customSkill, toolCall.params)
                ToolResult(result.success, result.data, result.error)
            } catch (e: Exception) {
                val error = AppErrorHandler.capture(e, "ToolExecutor[${toolCall.toolName}]")
                ToolResult(false, "", AppErrorHandler.toUserMessage(error))
            }
        }

        return try {
            tool.execute(toolCall.params)
        } catch (e: Exception) {
            val error = AppErrorHandler.capture(e, "ToolExecutor[${toolCall.toolName}]")
            ToolResult(success = false, data = "", error = AppErrorHandler.toUserMessage(error))
        }
    }

    fun getToolList(): List<Pair<String, String>> = registry.getToolInfos()
}
