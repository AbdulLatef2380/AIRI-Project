package com.airi.assistant.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * InferenceManager — ViewModel-level queue, timeout, and observability wrapper
 * over [LlamaManager].
 *
 * ## What this adds over raw LlamaManager
 *
 * [LlamaManager] already serializes calls through a single-threaded
 * [Dispatchers.IO.limitedParallelism(1)] dispatcher + lifecycleLock Mutex.
 * InferenceManager adds the *ViewModel-visible* concerns on top:
 *
 *  1. **Observable queue depth** — [queueDepth] StateFlow. The UI can show
 *     "2 requests queued" while a long generation is in progress.
 *
 *  2. **Per-request wall-clock timeout** — [defaultTimeoutMs] wraps every
 *     queued request. If the native side stalls (KV corruption, infinite loop),
 *     the request is cancelled, cancelGeneration() is called, and [queueDepth]
 *     drops correctly.
 *
 *  3. **Request IDs** — every enqueue returns a [RequestId]. The caller can
 *     cancel a specific pending request before it starts executing, without
 *     interrupting the currently running request.
 *
 *  4. **Generation active flag** — [isGenerating] StateFlow. Separate from
 *     [queueDepth]: a request may be active with a queue depth of zero. The
 *     UI uses both signals.
 *
 *  5. **Proof logging** — every enqueue, start, complete, cancel, and timeout
 *     emits an AIRI_PROOF tag so the audit stream is complete.
 *
 * ## Threading contract
 *
 * [enqueue] is safe to call from any thread. It posts to a
 * [Channel.UNLIMITED] channel and returns immediately. The worker loop
 * on [workerScope] drains the channel sequentially, bridging each request
 * to [LlamaManager.generateStream] via [CompletableDeferred]. There is
 * therefore never more than one active LlamaManager call at any time.
 *
 * [LlamaManager.generateStream] is callback-based (not a suspend function).
 * InferenceManager wraps it with [CompletableDeferred] so the worker loop
 * can properly await completion and enforce the wall-clock timeout.
 *
 * ## Lifecycle
 *
 * Create once alongside [LlamaManager] in the ViewModel. Call [shutdown]
 * from ViewModel.onCleared(). Do NOT re-use after shutdown.
 */
class InferenceManager(
    private val llamaManager: LlamaManager,
    val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    // ── Public types ──────────────────────────────────────────────────────────

    @JvmInline
    value class RequestId(val value: String)

    /**
     * Number of requests waiting in the channel (does NOT include the
     * currently executing request).
     */
    private val _queueDepth = MutableStateFlow(0)
    val queueDepth: StateFlow<Int> = _queueDepth.asStateFlow()

    /** True while a request is being actively processed by [LlamaManager]. */
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /** Total requests processed in this session (completed + cancelled). */
    private val _totalProcessed = AtomicInteger(0)
    val totalProcessed: Int get() = _totalProcessed.get()

    // ── Internal request model ────────────────────────────────────────────────

    sealed class QueueItem {
        data class Request(
            val id:        RequestId,
            val prompt:    String,
            val timeoutMs: Long,
            val onToken:    (String) -> Unit,
            val onComplete: (InferenceResult) -> Unit,
            @Volatile var cancelled: Boolean = false,
        ) : QueueItem()

        data class CancelById(val id: RequestId) : QueueItem()
        object CancelAll : QueueItem()
        object Shutdown  : QueueItem()
    }

    sealed class InferenceResult {
        data class Success(val tokenCount: Int,  val durationMs: Long)  : InferenceResult()
        data class Cancelled(val id: RequestId)                          : InferenceResult()
        data class TimedOut(val id: RequestId,   val timeoutMs: Long)   : InferenceResult()
        data class Failed(val id: RequestId,     val cause: Throwable)  : InferenceResult()
    }

    // ── Worker infrastructure ─────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    private val workerScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(1) + SupervisorJob()
    )
    private val channel = Channel<QueueItem>(Channel.UNLIMITED)

    /** Set of pending (not-yet-started) request IDs to skip. */
    private val cancelledPendingIds = mutableSetOf<RequestId>()

    init {
        workerScope.launch { drain() }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Queue an inference request. Returns immediately with a [RequestId].
     *
     * @param prompt       Full formatted prompt string.
     * @param timeoutMs    Per-request wall-clock timeout. Defaults to [defaultTimeoutMs].
     * @param onToken      Called for every token chunk. Invoked on LlamaManager's internal
     *                     dispatcher (Dispatchers.Main per its scope.launch). Handle
     *                     state updates accordingly.
     * @param onComplete   Called exactly once: Success, Cancelled, TimedOut, or Failed.
     */
    fun enqueue(
        prompt:     String,
        timeoutMs:  Long           = defaultTimeoutMs,
        onToken:    (String) -> Unit,
        onComplete: (InferenceResult) -> Unit,
    ): RequestId {
        val id   = RequestId(UUID.randomUUID().toString().take(8))
        val item = QueueItem.Request(
            id        = id,
            prompt    = prompt,
            timeoutMs = timeoutMs,
            onToken   = onToken,
            onComplete = onComplete,
        )
        _queueDepth.update { it + 1 }
        channel.trySend(item)
        Log.i("AIRI_PROOF", "INF_ENQUEUE id=$id queue=${_queueDepth.value} timeout=${timeoutMs}ms")
        return id
    }

    /**
     * Cancel a specific pending request. If the request is already executing,
     * this is a no-op — use [cancelAll] to interrupt the running generation.
     */
    fun cancelById(id: RequestId) {
        Log.i("AIRI_PROOF", "INF_CANCEL_BY_ID id=$id")
        channel.trySend(QueueItem.CancelById(id))
    }

    /**
     * Cancel ALL pending requests AND interrupt the currently running
     * generation via [LlamaManager.cancelGeneration].
     */
    fun cancelAll() {
        Log.i("AIRI_PROOF", "INF_CANCEL_ALL active=${_isGenerating.value} queue=${_queueDepth.value}")
        channel.trySend(QueueItem.CancelAll)
        llamaManager.cancelStream()
    }

    /**
     * Tear down the worker. After shutdown the channel is closed and no
     * further requests will be processed.
     */
    fun shutdown() {
        channel.trySend(QueueItem.Shutdown)
        Log.i("AIRI_PROOF", "INF_SHUTDOWN total_processed=${_totalProcessed.get()}")
    }

    // ── Worker loop ───────────────────────────────────────────────────────────

    private suspend fun drain() {
        for (item in channel) {
            when (item) {
                is QueueItem.Shutdown -> {
                    channel.close()
                    _isGenerating.value = false
                    _queueDepth.value   = 0
                    Log.i("AIRI_PROOF", "INF_WORKER_STOPPED total=${_totalProcessed.get()}")
                    return
                }

                is QueueItem.CancelAll -> {
                    // Drain any pending Requests from the channel and mark them cancelled
                    var drained = 0
                    while (true) {
                        val result = channel.tryReceive()
                        if (!result.isSuccess) break
                        when (val pending = result.getOrNull()) {
                            is QueueItem.Request -> {
                                cancelledPendingIds.add(pending.id)
                                drained++
                            }
                            else -> { /* re-process control items */ }
                        }
                    }
                    _queueDepth.value = 0
                    Log.i("AIRI_PROOF", "INF_CANCEL_ALL_APPLIED drained=$drained")
                }

                is QueueItem.CancelById -> {
                    cancelledPendingIds.add(item.id)
                    Log.i("AIRI_PROOF", "INF_CANCEL_BY_ID_REGISTERED id=${item.id}")
                }

                is QueueItem.Request -> {
                    _queueDepth.update { maxOf(0, it - 1) }

                    if (item.id in cancelledPendingIds) {
                        cancelledPendingIds.remove(item.id)
                        Log.i("AIRI_PROOF", "INF_SKIP_CANCELLED id=${item.id}")
                        runCatching { item.onComplete(InferenceResult.Cancelled(item.id)) }
                        _totalProcessed.incrementAndGet()
                        continue
                    }

                    processRequest(item)
                }
            }
        }
    }

    private suspend fun processRequest(req: QueueItem.Request) {
        val startMs = System.currentTimeMillis()
        _isGenerating.value = true
        Log.i("AIRI_PROOF", "INF_START id=${req.id} timeout=${req.timeoutMs}ms queue=${_queueDepth.value}")

        var tokenCount = 0
        // Bridge the callback-based LlamaManager.generateStream() into a
        // suspendable Deferred so we can apply withTimeoutOrNull() on top.
        // IMPORTANT: generateStream() is NOT a suspend function — it launches
        // internally and fires callbacks from its own scope.
        val latch = CompletableDeferred<InferenceResult>()

        try {
            llamaManager.generateStream(
                prompt    = req.prompt,
                onToken   = { chunk ->
                    if (!req.cancelled) {
                        tokenCount++
                        runCatching { req.onToken(chunk) }
                    }
                },
                onComplete = { _ ->
                    val duration = System.currentTimeMillis() - startMs
                    if (latch.isActive) {
                        Log.i("AIRI_PROOF", "INF_COMPLETE id=${req.id} tokens=$tokenCount elapsed=${duration}ms")
                        latch.complete(InferenceResult.Success(tokenCount, duration))
                    }
                },
                onError = { error ->
                    val duration = System.currentTimeMillis() - startMs
                    if (latch.isActive) {
                        Log.w("AIRI_PROOF", "INF_ERROR id=${req.id} elapsed=${duration}ms msg=${error.take(80)}")
                        latch.complete(InferenceResult.Failed(req.id, RuntimeException(error)))
                    }
                },
            )
        } catch (t: Throwable) {
            if (latch.isActive) {
                latch.complete(InferenceResult.Failed(req.id, t))
            }
        }

        val result = try {
            withTimeoutOrNull(req.timeoutMs) {
                latch.await()
            } ?: run {
                // Timeout — signal the native layer to abort
                req.cancelled = true
                llamaManager.cancelStream()
                val elapsed = System.currentTimeMillis() - startMs
                Log.w("AIRI_PROOF", "INF_TIMEOUT id=${req.id} timeout=${req.timeoutMs}ms elapsed=${elapsed}ms tokens_so_far=$tokenCount")
                InferenceResult.TimedOut(req.id, req.timeoutMs)
            }
        } catch (e: CancellationException) {
            req.cancelled = true
            llamaManager.cancelStream()
            Log.i("AIRI_PROOF", "INF_CANCELLED id=${req.id} tokens=$tokenCount")
            InferenceResult.Cancelled(req.id)
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - startMs
            Log.e("AIRI_PROOF", "INF_FAILED id=${req.id} elapsed=${elapsed}ms cause=${t.message}")
            InferenceResult.Failed(req.id, t)
        } finally {
            _isGenerating.value = false
            _totalProcessed.incrementAndGet()
        }

        runCatching { req.onComplete(result) }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 180_000L
    }
}
