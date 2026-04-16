package com.airi.assistant.ai.agent.trace

enum class AgentStepType {
    SKILL,
    TOOL,
    TASK_STEP
}

data class AgentStep(
    val stepIndex: Int,
    val type: AgentStepType,
    val name: String,
    val inputParams: Map<String, String> = emptyMap(),
    val outputSummary: String = "",
    val success: Boolean = false,
    val error: String? = null,
    val durationMs: Long = 0L
) {
    val displayName: String get() = name.replace("_", " ")
        .split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

    val typeLabel: String get() = when (type) {
        AgentStepType.SKILL     -> "Skill"
        AgentStepType.TOOL      -> "Tool"
        AgentStepType.TASK_STEP -> "Task Step"
    }
}
