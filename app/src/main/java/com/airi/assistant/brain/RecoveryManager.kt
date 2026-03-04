package com.airi.assistant.brain

class RecoveryManager {

    private val maxRetries = 2

    fun shouldRetry(attempt: Int): Boolean {
        return attempt < maxRetries
    }

    fun diagnose(error: Throwable): RecoveryStrategy {
        return when (error) {
            is TimeoutException -> RecoveryStrategy.REDUCE_SCOPE
            is ValidationException -> RecoveryStrategy.REPLAN
            else -> RecoveryStrategy.ABORT
        }
    }
}

enum class RecoveryStrategy {
    REPLAN,
    REDUCE_SCOPE,
    ABORT
}
