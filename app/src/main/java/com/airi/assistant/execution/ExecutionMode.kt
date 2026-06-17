package com.airi.assistant.execution

/**
 * Top-level execution mode. Controls which runtime layer handles user requests.
 *
 * Values:
 *  - LOCAL_ONLY  — llama.cpp only, fully offline, privacy-first. No network
 *                  calls will be made even if a remote model is configured.
 *  - CLOUD_ONLY  — remote provider only. Local inference is skipped.
 *  - HYBRID      — intelligent routing layer decides execution target
 *                  dynamically based on device signals, task complexity, and
 *                  user preferences.
 */
enum class ExecutionMode {
    LOCAL_ONLY,
    CLOUD_ONLY,
    HYBRID;

    val displayName: String get() = when (this) {
        LOCAL_ONLY -> "Local Only"
        CLOUD_ONLY -> "Cloud Only"
        HYBRID     -> "Hybrid"
    }

    val description: String get() = when (this) {
        LOCAL_ONLY -> "Fully offline · llama.cpp only · privacy-first · no network access"
        CLOUD_ONLY -> "Remote APIs only · optimised for speed and long-context reasoning"
        HYBRID     -> "Intelligent routing — picks the best engine per request"
    }
}

/**
 * Privacy level for HYBRID and CLOUD modes.
 *
 *  - MAXIMUM     — never send data to cloud even when mode is HYBRID.
 *                  Equivalent to forcing LOCAL_ONLY at the privacy layer.
 *  - BALANCED    — send to cloud with sanitization: strip paths, secrets,
 *                  accessibility context; truncate to 4096 chars.
 *  - PERFORMANCE — send full context to cloud (user's explicit opt-in).
 */
enum class PrivacyLevel {
    MAXIMUM,
    BALANCED,
    PERFORMANCE;

    val displayName: String get() = when (this) {
        MAXIMUM     -> "Maximum Privacy"
        BALANCED    -> "Balanced"
        PERFORMANCE -> "Performance"
    }

    val description: String get() = when (this) {
        MAXIMUM     -> "Nothing leaves the device — cloud blocked even in Hybrid mode"
        BALANCED    -> "Cloud-bound prompts are sanitized before sending"
        PERFORMANCE -> "Full context sent to cloud — fastest and most capable"
    }
}

/**
 * Cloud provider preference. The router uses this when CLOUD or HYBRID mode
 * is active and multiple providers are configured.
 */
enum class CloudProvider {
    GEMINI,
    OPENAI,
    ANTHROPIC,
    OPENROUTER,
    KIMI,
    CUSTOM,
    BRAVE;     // Brave Search API — used by SearchTool for real web search

    val displayName: String get() = when (this) {
        GEMINI      -> "Google Gemini"
        OPENAI      -> "OpenAI"
        ANTHROPIC   -> "Anthropic Claude"
        OPENROUTER  -> "OpenRouter"
        KIMI        -> "Moonshot Kimi"
        CUSTOM      -> "Custom endpoint"
        BRAVE       -> "Brave Search"
    }
}

/**
 * Execution origin tag attached to every assistant response.
 * Displayed visibly in the chat UI so the user always knows which
 * runtime produced the answer — AIRI never hides execution origin.
 *
 *  - LOCAL   — llama.cpp local inference
 *  - CLOUD   — remote provider API
 *  - HYBRID  — local pre-processing + cloud reasoning (or vice versa)
 *  - NONE    — not yet determined / user message
 */
enum class ExecOrigin {
    LOCAL,
    CLOUD,
    HYBRID,
    NONE;

    val badge: String get() = when (this) {
        LOCAL  -> "LOCAL"
        CLOUD  -> "CLOUD"
        HYBRID -> "HYBRID"
        NONE   -> ""
    }

    val isVisible: Boolean get() = this != NONE
}
