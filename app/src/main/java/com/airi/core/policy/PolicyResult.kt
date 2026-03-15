package com.airi.core.policy

data class PolicyResult(
    val allowed: Boolean,
    val reason: String?,
    val riskScore: Float = 0f
)
