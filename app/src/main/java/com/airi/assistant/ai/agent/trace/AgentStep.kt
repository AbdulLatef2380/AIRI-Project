package com.airi.assistant.ai.agent.trace

import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.core.ExecutionTraceStatus

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
    /** Stable source event identity; blank for legacy traces. */
    val eventId: String = "",
    /** Stable action identity, allowing request/start/output/completion to update one node. */
    val actionId: String? = null,
    /** Exact lifecycle state represented by this snapshot. */
    val traceStatus: ExecutionTraceStatus = ExecutionTraceStatus.COMPLETED,
    /** Exact source kinds folded into this action snapshot, oldest first. */
    val lifecycleKinds: List<ExecutionTraceKind> = emptyList(),
    /** True when the supplied parentId was not present in the execution snapshot. */
    val orphanedParent: Boolean = false,
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
