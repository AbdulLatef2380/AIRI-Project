package com.airi.assistant.ai

enum class PerformanceMode(
    val label: String,
    val description: String,
    val maxTokens: Int,
    val contextWindow: Int,
    val aggressiveTruncation: Boolean,
    val temperature: Float,
    /** Native llama_context n_ctx for this mode (hot-swappable). */
    val nCtx: Int,
    /** Native CPU thread count for this mode (hot-swappable). */
    val nThreads: Int
) {
    FAST(
        label                = "Fast",
        description          = "Low context + aggressive truncation — fastest responses",
        maxTokens            = 256,
        contextWindow        = 1024,   // matches nCtx exactly
        aggressiveTruncation = true,
        temperature          = 0.7f,
        nCtx                 = 1024,
        nThreads             = 4
    ),
    BALANCED(
        label                = "Balanced",
        description          = "Default — good quality without sacrificing speed",
        maxTokens            = 512,
        contextWindow        = 2048,   // matches nCtx exactly
        aggressiveTruncation = false,
        temperature          = 0.8f,
        nCtx                 = 2048,
        nThreads             = 4
    ),
    QUALITY(
        label                = "Quality",
        description          = "Full context + slower — best accuracy",
        maxTokens            = 1024,
        contextWindow        = 4096,   // matches nCtx exactly (doubled for complex tasks)
        aggressiveTruncation = false,
        temperature          = 0.9f,
        nCtx                 = 4096,
        nThreads             = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
    )
}
