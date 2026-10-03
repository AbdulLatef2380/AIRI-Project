package com.airi.assistant.core

import com.airi.assistant.ai.QueryType
import com.airi.assistant.agent.loop.tool.ToolSchema

/**
 * Request-scoped permission surface. This is deliberately not a grant of
 * authority: it is the upper bound of tools that may be exposed to the model
 * for one AgentLoop request. Execution still passes through sandbox, side
 * effect policy, connector health, and approval gates.
 */
data class AgentPermissionProfile(
    val profileId: String,
    val modelId: String,
    val providerId: String,
    val requestMode: String,
    val allowReadTools: Boolean,
    val allowSkills: Boolean,
    val allowConnectorRead: Boolean,
    val allowMemoryRead: Boolean,
    val allowApprovedProposals: Boolean,
    val allowAutomation: Boolean,
    val maxToolSteps: Int,
) {
    fun allows(tool: ToolSchema): Boolean {
        if (!allowReadTools) return false
        if (tool.dangerous && !(allowApprovedProposals && tool.name == "calendar_create")) return false
        if (tool.name.startsWith("skill_") && !allowSkills) return false
        if (tool.name.startsWith("connector_") && !allowConnectorRead) return false
        if (tool.name == "memory_recall" && !allowMemoryRead) return false
        if (tool.category == ToolSchema.Category.AUTOMATION && !allowAutomation) return false
        return true
    }

    fun filterTools(tools: List<ToolSchema>): List<ToolSchema> =
        tools.filter(::allows).distinctBy { it.name }

    fun summary(): Map<String, String> = mapOf(
        "profileId" to profileId,
        "requestMode" to requestMode,
        "allowReadTools" to allowReadTools.toString(),
        "allowSkills" to allowSkills.toString(),
        "allowConnectorRead" to allowConnectorRead.toString(),
        "allowMemoryRead" to allowMemoryRead.toString(),
        "allowApprovedProposals" to allowApprovedProposals.toString(),
        "allowAutomation" to allowAutomation.toString(),
        "maxToolSteps" to maxToolSteps.toString(),
    )

    companion object {
        fun resolve(
            queryType: QueryType,
            modelId: String,
            providerId: String,
            toolsRequested: Boolean = false,
        ): AgentPermissionProfile {
            val mode = queryType.name.lowercase()
            // A short request can still require current_time, memory, or a
            // connector read. Tool access must therefore follow the resolved
            // capability request, not QueryType alone.
            val interactive = toolsRequested || queryType == QueryType.ACTION || queryType == QueryType.ANALYTICAL || queryType == QueryType.UNKNOWN
            return AgentPermissionProfile(
                profileId = "agent_request_v1",
                modelId = modelId,
                providerId = providerId,
                requestMode = mode,
                allowReadTools = interactive,
                allowSkills = interactive,
                allowConnectorRead = interactive,
                allowMemoryRead = interactive,
                allowApprovedProposals = queryType == QueryType.ACTION || queryType == QueryType.ANALYTICAL,
                allowAutomation = queryType == QueryType.ACTION,
                maxToolSteps = if (interactive) 8 else 0,
            )
        }
    }
}
