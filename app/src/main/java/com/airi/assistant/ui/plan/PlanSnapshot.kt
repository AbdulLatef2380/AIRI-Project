package com.airi.assistant.ui.plan

/** Durable, non-secret representation of the plan panel state. */
data class PlanSnapshot(
    val executionId: String = "",
    val goal: String = "",
    val stage: String = "IDLE",
    val sessionId: String = "",
    val projectId: String = "",
    val memoryQuery: String = "",
    val skillIds: List<String> = emptyList(),
    val connectorIds: List<String> = emptyList(),
    val steps: List<PlanStepModel> = emptyList(),
    val savedAtMs: Long = System.currentTimeMillis()
)
