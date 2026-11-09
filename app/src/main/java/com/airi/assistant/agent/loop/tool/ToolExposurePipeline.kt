package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.QueryType
import com.airi.assistant.core.AgentPermissionProfile

/**
 * Report ب-2: tool exposure was decided twice, in ChatViewModel and again in AgentLoop.run, with
 * slightly different inputs (the loop ignored `codeTask` and the required-tool list), so the two
 * could disagree about which tools the model may see. Both now call this one function:
 * permission profile -> optional runtime admission -> prompt-budget selection.
 */
internal object ToolExposurePipeline {
    data class Result(
        val profile: AgentPermissionProfile,
        /** Tools that passed the permission profile and the runtime admission filter. */
        val permitted: List<ToolSchema>,
        /** Tools that also fit the prompt budget; this is what the model sees. */
        val selection: ToolSchemaPromptBudget.Selection,
    )

    fun resolve(
        candidates: List<ToolSchema>,
        queryType: QueryType,
        modelId: String,
        providerId: String,
        codeTask: Boolean,
        promptBudgetChars: Int,
        /** Null keeps the budget's own default (tools flagged requiredForRequest). */
        requiredToolNames: Set<String>? = null,
        /** Runtime-only admission (durable context, calendar runtime, terminal); the loop supplies it. */
        admission: (List<ToolSchema>) -> List<ToolSchema> = { it },
    ): Result {
        val profile = AgentPermissionProfile.resolve(
            queryType = queryType,
            modelId = modelId,
            providerId = providerId,
            toolsRequested = candidates.isNotEmpty(),
            codeTask = codeTask,
        )
        val permitted = admission(profile.filterTools(candidates))
        val selection = if (requiredToolNames != null) {
            ToolSchemaPromptBudget.select(permitted, promptBudgetChars, requiredToolNames)
        } else {
            ToolSchemaPromptBudget.select(permitted, promptBudgetChars)
        }
        return Result(profile, permitted, selection)
    }
}
