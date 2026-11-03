package com.airi.assistant.execution

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Report 1, item 29 (stage 21). [HybridOrchestrator.executeStream] used to wait on a
 * plain [Mutex] with no limit and no trace, so a request queued behind a holder that
 * never finished looked like an endless "thinking" state. This wrapper keeps the same
 * one-at-a-time rule but:
 *  - records how long a caller waited (reported through [onWaited]), and
 *  - gives up after [waitLimitMs] with [onBusy] instead of waiting forever.
 *
 * It never cancels the current holder: a legitimate long generation must not be
 * killed by an unrelated caller. Cancelling a stale generation stays the call site's job.
 *
 * Cancellation of the waiting coroutine is safe: [Mutex.lock] releases the lock if it
 * was granted at the moment of cancellation.
 */
internal class BoundedExecutionLock(
    private val mutex: Mutex = Mutex(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    val isLocked: Boolean get() = mutex.isLocked

    suspend fun <T> withLock(
        waitLimitMs: Long,
        onWaited: (waitedMs: Long, acquired: Boolean) -> Unit = { _, _ -> },
        onBusy: suspend () -> T,
        block: suspend () -> T,
    ): T {
        if (!mutex.tryLock()) {
            val waitStart = clockMs()
            val acquired = withTimeoutOrNull(waitLimitMs) {
                mutex.lock()
                true
            } == true
            onWaited((clockMs() - waitStart).coerceAtLeast(0), acquired)
            if (!acquired) return onBusy()
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        /** Below the 120 s model stall timeout, so a stuck holder surfaces as an error first. */
        const val DEFAULT_WAIT_LIMIT_MS = 90_000L
    }
}
