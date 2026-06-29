package com.airi.assistant.crash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * CoroutineHealthBridge — extension functions that automatically register and
 * unregister long-lived coroutines with [RuntimeHealthMonitor].
 *
 * ## Usage
 *
 * Replace plain `viewModelScope.launch { ... }` with:
 *
 *   ```kotlin
 *   viewModelScope.launchTracked(runtimeHealthMonitor, "ChatViewModel_sendMessage") {
 *       // your coroutine body
 *   }
 *   ```
 *
 * The key (`"ChatViewModel_sendMessage"`) appears in [RuntimeHealthMonitor.HealthReport.orphanKeys]
 * when the coroutine exceeds the monitor's orphan threshold.
 *
 * ## When to use
 *
 * Use [launchTracked] for coroutines that:
 *  - Perform I/O or long-running computation
 *  - Are expected to complete within a bounded time (30 seconds or less)
 *  - Would be a bug to run indefinitely (generation, model-load, etc.)
 *
 * Do NOT use [launchTracked] for infinite observation loops that are
 * intentionally long-lived (e.g. `collectAsState`). Those are expected
 * to run until scope cancellation and would produce false orphan alerts.
 *
 * ## Thread safety
 * [RuntimeHealthMonitor.registerCoroutine] / [unregisterCoroutine] are both
 * thread-safe (ConcurrentHashMap). Safe to call from any dispatcher.
 */

/**
 * Launch a coroutine that is tracked in [monitor] for orphan detection.
 *
 * The coroutine registers itself with [key] on launch and unregisters when it
 * completes (normally, with exception, or by cancellation).
 *
 * @param monitor   The [RuntimeHealthMonitor] instance to register with.
 * @param key       A human-readable identifier shown in orphan warnings.
 *                  Append a unique suffix (e.g. request ID) for concurrent launches:
 *                  `"ChatViewModel_generation_$genId"`
 * @param context   Optional [CoroutineContext] override.
 * @param block     The coroutine body.
 */
fun CoroutineScope.launchTracked(
    monitor: RuntimeHealthMonitor,
    key:     String,
    context: CoroutineContext = EmptyCoroutineContext,
    block:   suspend CoroutineScope.() -> Unit
): Job {
    val uniqueKey = "$key@${System.nanoTime()}"
    monitor.registerCoroutine(uniqueKey)
    val job = launch(context) {
        try {
            block()
        } finally {
            monitor.unregisterCoroutine(uniqueKey)
        }
    }
    // Belt-and-suspenders: if the job is immediately cancelled before the
    // coroutine body even runs, unregister here too.
    job.invokeOnCompletion { monitor.unregisterCoroutine(uniqueKey) }
    return job
}
