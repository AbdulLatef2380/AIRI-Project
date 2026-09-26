package com.airi.assistant.ui.viewmodel

/**
 * Explicit lifecycle for the chat session projection.
 * Empty is a successful read with no rows; it is never a fallback for failure.
 */
sealed class SessionLoadState {
    data object NotStarted : SessionLoadState()
    data object Loading : SessionLoadState()
    data class Ready(val sessionId: String, val messageCount: Int) : SessionLoadState()
    data object Empty : SessionLoadState()
    data class Failed(val reason: String, val causeType: String? = null, val sessionId: String? = null) : SessionLoadState()
}

enum class InitializationReadiness {
    NOT_INITIALIZED,
    INITIALIZING,
    READY,
    DEGRADED,
    FAILED
}

/** Monotonic operation gate: an old completion can never commit over a newer one. */
internal class LatestOperationGate {
    private var generation = 0L
    @Synchronized fun begin(): Long = ++generation
    @Synchronized fun isCurrent(token: Long): Boolean = token == generation
}
