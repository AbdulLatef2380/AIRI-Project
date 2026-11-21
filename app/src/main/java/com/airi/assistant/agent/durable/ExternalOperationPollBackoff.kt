package com.airi.assistant.agent.durable

import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min

/** Exponential full-jitter delay; the provider's Retry-After is always a lower bound. */
object ExternalOperationPollBackoff {
    fun nextDelayMs(
        operation: ExternalOperation,
        retryAfterMs: Long? = null,
        jitterUnit: Double = ThreadLocalRandom.current().nextDouble()
    ): Long {
        val maximum = operation.retryPolicy.maxBackoffMs.coerceAtLeast(0L)
        val initial = operation.retryPolicy.initialBackoffMs.coerceAtLeast(0L).coerceAtMost(maximum)
        var window = initial
        repeat((operation.pollAttemptCount - 1).coerceIn(0, 62)) {
            window = if (window >= maximum / 2L) maximum else window * 2L
        }
        val jitter = (window.toDouble() * jitterUnit.coerceIn(0.0, 1.0)).toLong()
        val minimumDelay = min(1_000L, maximum)
        return max(max(jitter, minimumDelay), retryAfterMs?.coerceAtLeast(0L) ?: 0L)
    }

    fun nextPollAtMs(nowMs: Long, delayMs: Long, deadlineAtMs: Long): Long {
        val proposed = if (Long.MAX_VALUE - nowMs < delayMs) Long.MAX_VALUE else nowMs + delayMs
        return min(proposed, deadlineAtMs)
    }
}
