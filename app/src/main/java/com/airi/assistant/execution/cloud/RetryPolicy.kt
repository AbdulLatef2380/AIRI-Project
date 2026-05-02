package com.airi.assistant.execution.cloud

import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential back-off with full jitter for cloud API retries.
 *
 * Formula:  delay = clamp(base × 2^attempt, MIN, MAX) × uniform(0.75, 1.25)
 *
 * The ±25% jitter breaks thundering-herd when many coroutines all fail
 * at the same instant (e.g. a brief provider outage clears and all callers
 * retry simultaneously).
 *
 * Default parameters match AWS/Google SDK recommendations:
 *  - base  = 1 000 ms
 *  - max   = 30 000 ms (never wait more than 30 s)
 *  - 3 total attempts (initial + 2 retries)
 *
 * ## Usage
 * ```kotlin
 * val result = RetryPolicy.withRetry(maxAttempts = 3) { attempt ->
 *     adapter.streamGenerate(request, onToken)
 * }
 * ```
 */
object RetryPolicy {

    private const val TAG           = "AIRI_RetryPolicy"
    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS  = 30_000L

    /**
     * Execute [block] up to [maxAttempts] times.
     *
     * Retries only when [block] returns [CloudProviderAdapter.AdapterResult.Failure]
     * with [CloudProviderAdapter.AdapterResult.Failure.retryable] == true.
     * A [maxAttempts] of 1 disables retries entirely.
     *
     * @param maxAttempts   Total number of tries including the first. Min 1.
     * @param block         The suspending block to execute. Receives 0-based attempt index.
     */
    suspend fun withRetry(
        maxAttempts: Int = 3,
        block: suspend (attempt: Int) -> CloudProviderAdapter.AdapterResult
    ): CloudProviderAdapter.AdapterResult {
        var lastResult: CloudProviderAdapter.AdapterResult =
            CloudProviderAdapter.AdapterResult.Failure(
                "No attempts made", CloudErrorType.UNKNOWN, false
            )

        for (attempt in 0 until maxAttempts.coerceAtLeast(1)) {
            lastResult = block(attempt)
            when {
                lastResult is CloudProviderAdapter.AdapterResult.Success -> return lastResult
                lastResult is CloudProviderAdapter.AdapterResult.Failure && !lastResult.retryable -> return lastResult
                attempt >= maxAttempts - 1 -> return lastResult   // exhausted
                else -> {
                    val delayMs = computeDelay(attempt)
                    Log.i(TAG, "attempt=${attempt + 1}/$maxAttempts " +
                        "error=${(lastResult as CloudProviderAdapter.AdapterResult.Failure).error.take(60)} " +
                        "retry_in=${delayMs}ms")
                    delay(delayMs)
                }
            }
        }
        return lastResult
    }

    /**
     * Compute the back-off delay for a given attempt index (0-based).
     * Returns milliseconds.
     */
    fun computeDelay(attempt: Int): Long {
        val uncapped = BASE_DELAY_MS * (1L shl min(attempt, 5))  // × 1, 2, 4, 8, 16, 32
        val capped   = min(uncapped, MAX_DELAY_MS)
        val jitter   = Random.nextDouble(0.75, 1.25)
        return (capped * jitter).toLong()
    }
}
