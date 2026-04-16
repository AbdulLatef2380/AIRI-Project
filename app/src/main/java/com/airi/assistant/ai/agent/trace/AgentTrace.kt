package com.airi.assistant.ai.agent.trace

import java.util.UUID

data class AgentTrace(
    val id: String = UUID.randomUUID().toString(),
    val originalInput: String,
    val steps: List<AgentStep> = emptyList(),
    val finalResult: String = "",
    val success: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    val stepCount: Int get() = steps.size
    val successCount: Int get() = steps.count { it.success }
    val hasErrors: Boolean get() = steps.any { !it.success || it.error != null }
}
