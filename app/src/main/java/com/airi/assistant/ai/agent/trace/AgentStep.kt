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
    // Default is true: a writer that records only an error (error != null) is
    // still reported as failed by AgentTrace.hasErrors, while a writer that
    // forgets to set this flag no longer shows a healthy step as failed.
    val success: Boolean = true,
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
