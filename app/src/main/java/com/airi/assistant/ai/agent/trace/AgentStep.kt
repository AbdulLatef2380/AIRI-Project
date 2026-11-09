package com.airi.assistant.ai.agent.trace

enum class AgentStepType {
    SKILL,
    TOOL,
    TASK_STEP,
    /** Report ج-5: what the loop decided about the request (query type, capabilities, tools offered). */
    INTENT,
    /** Requirements extracted before acting (code tasks). */
    PLAN,
    /** The verification gate's verdict on the result. */
    VERIFY,
    /** One model call of the run (Part 5 §3.4): provider/model, tool choice, finish reason, tokens. */
    MODEL_TURN,
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
    val durationMs: Long = 0L,
    /** Parent execution action used to render nested tool/task steps. */
    val parentId: String? = null,
    /** Original event timestamp; zero is retained for legacy traces. */
    val timestampMs: Long = 0L,
) {
    val displayName: String get() = name.replace("_", " ")
        .split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

    val typeLabel: String get() = when (type) {
        AgentStepType.SKILL     -> "Skill"
        AgentStepType.TOOL      -> "Tool"
        AgentStepType.TASK_STEP -> "Task Step"
        AgentStepType.INTENT    -> "Intent"
        AgentStepType.PLAN      -> "Plan"
        AgentStepType.VERIFY    -> "Verify"
        AgentStepType.MODEL_TURN -> "Model"
    }
}
