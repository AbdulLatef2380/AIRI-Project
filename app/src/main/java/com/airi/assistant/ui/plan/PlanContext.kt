package com.airi.assistant.ui.plan

/** Context references only; contents remain in their owning stores. */
data class PlanContext(
    val sessionId: String = "",
    val projectId: String = "",
    val memoryQuery: String = "",
    val skillIds: List<String> = emptyList(),
    val connectorIds: List<String> = emptyList()
)
