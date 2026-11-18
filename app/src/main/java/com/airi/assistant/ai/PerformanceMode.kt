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
    /** Safe fallback; use [effectiveThreads] when applying a runtime mode. */
    val nThreads: Int = 4,
) {
    FAST(
        label                = "Fast",
        description          = "Low context + aggressive truncation — fastest responses",
        maxTokens            = 256,
        contextWindow        = 1024,
        aggressiveTruncation = true,
        temperature          = 0.7f,
        nCtx                 = 1024,
    ),
    BALANCED(
        label                = "Balanced",
        description          = "Default — good quality without sacrificing speed",
        maxTokens            = 512,
        contextWindow        = 2048,
        aggressiveTruncation = false,
        temperature          = 0.8f,
        nCtx                 = 2048,
    ),
    QUALITY(
        label                = "Quality",
        description          = "Full context + slower — best accuracy",
        maxTokens            = 1024,
        contextWindow        = 4096,
        aggressiveTruncation = false,
        temperature          = 0.9f,
        nCtx                 = 4096,
    )
}

/** Resolved only when a runtime mode is applied, never during enum startup. */
val PerformanceMode.effectiveThreads: Int
    get() = CpuThreadPolicy.recommendedThreads()
