package com.airi.assistant.ai

enum class PerformanceMode(
    val label: String,
    val description: String,
    val maxTokens: Int,
    val contextWindow: Int,
    val aggressiveTruncation: Boolean,
    val temperature: Float
) {
    FAST(
        label               = "Fast",
        description         = "Low context + aggressive truncation — fastest responses",
        maxTokens           = 128,
        contextWindow       = 1500,
        aggressiveTruncation = true,
        temperature         = 0.7f
    ),
    BALANCED(
        label               = "Balanced",
        description         = "Default — good quality without sacrificing speed",
        maxTokens           = 256,
        contextWindow       = 3000,
        aggressiveTruncation = false,
        temperature         = 0.8f
    ),
    QUALITY(
        label               = "Quality",
        description         = "Full context + slower — best accuracy",
        maxTokens           = 512,
        contextWindow       = 6000,
        aggressiveTruncation = false,
        temperature         = 0.9f
    )
}
