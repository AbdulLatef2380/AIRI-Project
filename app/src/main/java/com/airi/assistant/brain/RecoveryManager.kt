package com.airi.assistant.brain

class RecoveryManager {

    private val maxRetries = 2

    fun shouldRetry(attempt: Int): Boolean {
        return attempt < maxRetries
    }

    fun diagnose(error: Throwable): RecoveryStrategy {
        return when (error) {

            is IllegalArgumentException -> {
                RecoveryStrategy.REDUCE_SCOPE
            }

            is IllegalStateException -> {
                RecoveryStrategy.REPLAN
            }

            is TimeoutException -> {
                RecoveryStrategy.REPLAN
            }

            else -> {
                RecoveryStrategy.ABORT
            }
        }
    }
}

enum class RecoveryStrategy {
    REPLAN,
    REDUCE_SCOPE,
    ABORT
}

class TimeoutException(message: String? = null) : Exception(message)
