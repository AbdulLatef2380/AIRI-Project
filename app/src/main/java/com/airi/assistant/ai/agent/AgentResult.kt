package com.airi.assistant.ai.agent

data class AgentResult(
    val text: String,
    val agentTag: String? = null,
    val success: Boolean = true,
    val traceId: String? = null
)
