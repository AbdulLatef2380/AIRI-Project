package com.airi.assistant.agent.execution.runtime

data class ExecutionArtifact(
    val type: String,
    val value: String,
    val sourceNodeId: String,
    val metadata: Map<String, String> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis()
)