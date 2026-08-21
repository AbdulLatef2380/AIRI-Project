package com.airi.desktop

import com.airi.core.models.ModelAvailability
import com.airi.core.models.ModelDescriptor
import com.airi.core.models.ModelExecutionMode
import com.airi.core.skills.AiriPlatform
import com.airi.core.skills.SkillAvailability
import com.airi.core.skills.SkillDescriptor

object DesktopCapabilities {

    val models: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "android-local-llama",
            displayName = "Android local model",
            executionMode = ModelExecutionMode.LOCAL,
            availability = ModelAvailability.UNAVAILABLE,
            unavailableReason = "Requires the Android native model runtime."
        ),
        ModelDescriptor(
            id = "remote-provider",
            displayName = "Remote provider",
            executionMode = ModelExecutionMode.REMOTE,
            availability = ModelAvailability.REQUIRES_CONFIGURATION,
            unavailableReason = "A Desktop provider adapter and credentials are not configured."
        )
    )

    val skills: List<SkillDescriptor> = listOf(
        desktopSkill("code_assistant", "Code Assistant", setOf("code", "debug", "review")),
        desktopSkill("document_reader", "Document Reader", setOf("document", "read", "file")),
        desktopSkill("memory_manager", "Memory Manager", setOf("memory", "remember", "recall")),
        desktopSkill("task_planner", "Task Planner", setOf("plan", "roadmap", "task")),
        desktopSkill("web_search", "Web Search", setOf("search", "web", "research"))
    )

    private fun desktopSkill(
        id: String,
        displayName: String,
        intentTags: Set<String>
    ) = SkillDescriptor(
        id = id,
        displayName = displayName,
        intentTags = intentTags,
        supportedPlatforms = setOf(AiriPlatform.DESKTOP),
        availability = SkillAvailability.UNAVAILABLE,
        unavailableReason = "A Desktop skill adapter is not available."
    )
}
