package com.airi.assistant.domain.verification

data class VerificationEvent(
    val type: String,
    val latencyMs: Long,
    val tokens: Int,
    val wasCut: Boolean,
    val queryType: String = "UNKNOWN",
    val timestamp: Long = System.currentTimeMillis()
)
