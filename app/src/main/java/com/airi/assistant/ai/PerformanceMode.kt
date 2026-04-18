package com.airi.assistant.ai

enum class PerformanceMode(
    val label: String,
    val description: String,
    val maxTokens: Int,
    val contextWindow: Int,
    val aggressiveTruncation: Boolean
) {
    FAST(
        label               = "Fast",
        description         = "Low context + aggressive truncation — fastest responses",
        maxTokens           = 256,
        contextWindow       = 1024,
        aggressiveTruncation = true
    ),
    BALANCED(
        label               = "Balanced",
        description         = "Default — good quality without sacrificing speed",
        maxTokens           = 512,
        contextWindow       = 4096,
        aggressiveTruncation = false
    ),
    QUALITY(
        label               = "Quality",
        description         = "Full context + slower — best accuracy",
        maxTokens           = 1024,
        contextWindow       = 8192,
        aggressiveTruncation = false
    )
}
