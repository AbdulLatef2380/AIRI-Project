package com.airi.assistant.ai

import android.content.Context
import android.util.Log
import com.airi.assistant.memory.entity.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Session-based wrapper around the native llama.cpp engine.
 *
 * The KV cache lives in the native context and is reused across messages.
 * This class is responsible for:
 *   1. Deciding when the native session needs a hard reset (model change,
 *      system-prompt change, or the supplied history diverges from what's
 *      currently primed in KV).
 *   2. Replaying only the *delta* — i.e. messages that aren't already in KV —
 *      via a single `appendAssistantTurn` block.
 *   3. Appending the new user turn (with the assistant opener tag) and
 *      sampling via `generateNextTokens`.
 *   4. Closing the assistant turn after generation so the next user turn
 *      aligns with the model's chat template.
 */
class LlamaManager(private val context: Context) {

    private var isLoaded = false
    private var lastLoadFailure: String? = null

    // ── Native-call serialization (Phase 2, finding D) ────────────────────────
    // The KV cache, sampler, and llama_context in LlamaBridge.cpp are file-scope
    // statics with NO internal mutex (by design — llama.cpp is not thread-safe
    // per context). Dispatchers.IO is a multi-threaded pool, so prior to this
    // change two coroutines could enter llama_decode() concurrently and corrupt
    // KV state. The official llama.cpp Android example uses the identical
    // pattern; see refs/llama.android InferenceEngineImpl.kt:123-125.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(llamaDispatcher + SupervisorJob())

    // Separate scope for the watchdog timer. It MUST NOT share the single
    // llamaDispatcher thread, otherwise it cannot wake while a native
    // generate() call is occupying that thread to time the call out.
    private val watchdogScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Canonical prior history supplied by the caller (excludes the current turn). */
    private val chatHistory = mutableListOf<ChatMessage>()
    // Bug B fix (was: 6). With 6 messages of typical Arabic content the
    // cold-restart replay (sys + 6 msgs ≈ 1500-2000 tokens) takes 30-90s on
    // mid-range CPUs and was tripping ERR_FIRST_TOKEN_TIMEOUT after a few
    // turns. 4 messages (= 2 full turns) keeps coherence + halves cold-start
    // time. The full repository chat history is independently persisted in
    // Room — this is just the in-memory window we keep in KV.
    private val maxHistory = 4

    private val cancelRequested = AtomicBoolean(false)

    // ── SPEC v3 — STRICT LIFECYCLE MUTEX ────────────────────────────────────
    // Defensive belt-and-braces mutex around the full
    //   fullReset() → generateStream() body
    // even though `llamaDispatcher` is single-threaded and already serializes
    // every coroutine that enters this manager. Rationale:
    //   1. The PocketPal AI / official llama.cpp Android example InferenceEngine
    //      pattern (see InferenceEngineImpl.kt:123-125) wraps the whole turn
    //      in a JVM mutex so that a context-destroying call CANNOT interleave
    //      with a decoding call even if a future refactor introduces a second
    //      dispatcher or a misplaced withContext.
    //   2. kotlinx.coroutines.sync.Mutex (NOT java.util.concurrent ReentrantLock)
    //      — the critical section contains `withContext(Dispatchers.Main)`
    //      suspensions inside `onComplete` / `onError` dispatch. ReentrantLock
    //      is thread-owned: when the coroutine suspends and a different
    //      coroutine is later scheduled on the same single-threaded
    //      llamaDispatcher thread, ReentrantLock would re-enter (same thread)
    //      and silently break mutual exclusion. Mutex is coroutine-owned
    //      (suspension-safe) and is the correct primitive here.
    //   3. The native side (LLAMA_LOCK in LlamaBridge.cpp) is a SEPARATE std::mutex
    //      that protects g_ctx at JNI granularity; this Kotlin mutex protects
    //      the *Kotlin-visible* lifecycle (sessionPrimed, primedHistory, the
    //      "fullReset then immediately re-prime then generate" sequence).
    //   4. cancelGeneration() and cancelOutsideGenerate() must NEVER take this
    //      mutex — they MUST be callable from the UI thread mid-generation; they
    //      only flip atomics and call the lock-free nativeCancel().
    private val lifecycleLock = Mutex()

    // ── Session bookkeeping ──────────────────────────────────────────────────
    /** Native KV is currently primed with a coherent session. */
    private var sessionPrimed = false
    /** Model path currently primed in KV. */
    private var primedModelPath: String? = null
    /** System prompt currently primed in KV. */
    private var primedSystemPrompt: String? = null
    /** Messages currently primed in KV (NOT including any in-flight user turn). */
    private val primedHistory = mutableListOf<ChatMessage>()

    companion object {
        private const val TAG = "AIRI_MODEL"
        // Per-token streaming: the native callback already fires once per
        // UTF-8-valid piece (i.e. per token for ASCII, per 1-2 tokens for
        // multi-byte Arabic / CJK clusters). We forward each native callback
        // to the UI immediately. Set TOKEN_BATCH_MS > 0 only if the UI thread
        // becomes a bottleneck — never as a default.
        private const val TOKEN_BATCH_MS = 0L
        private const val TOKEN_BATCH_CHARS = 1
        private const val INACTIVITY_TIMEOUT_MS = 20_000L
        // First-token budget bumped 60s -> 120s. On a 2B Q4 model with mmap,
        // the very first cold prefill of system prompt + history on CPU genuinely
        // takes 60-90s on mid-range Snapdragon devices; 60s was firing as a
        // false positive and surfacing as "النموذج لم يبدأ التوليد خلال المهلة".
        private const val DEFAULT_FIRST_TOKEN_TIMEOUT_MS = 120_000L
        // Stall warning fires (non-fatal) if no new token arrives for this long
        // *after* the first token. Surfaces as a UI hint, doesn't abort.
        private const val STALL_WARNING_MS = 5_000L

        const val ERR_FIRST_TOKEN_TIMEOUT = "ERR_FIRST_TOKEN_TIMEOUT"
        const val ERR_INACTIVITY_TIMEOUT  = "ERR_INACTIVITY_TIMEOUT"
        const val ERR_NATIVE              = "ERR_NATIVE"

        // ── TOKEN-BASED HISTORY BUDGET ──────────────────────────────────────
        // Hard ceiling on the CONTENT token count of chat history messages.
        // System prompt, template markers, user fragment, and generation tokens
        // are budgeted SEPARATELY by the preflight and are NOT counted here.
        //
        // Budget derivation for n_ctx = 1536 (AIRI_DEFAULT_N_CTX):
        //   system block          ~200 tok  (QWEN Arabic system prompt)
        //   history CONTENT        750 tok  ← this constant
        //   template overhead       80 tok  (~15 tok × 4 msgs × 2 roles)
        //   new user fragment       80 tok
        //   generation reserve     256 tok
        //   ─────────────────────────────
        //   total                 1366 tok  < 1536  (170 tok headroom)
        // B-02 FIX: Token budget derived dynamically from the active nCtx so
        // QUALITY mode (nCtx=2048) uses ~1432 history tokens instead of 750,
        // and FAST mode (nCtx=1024) uses a tighter ~408 tokens.
        //
        // Budget formula: nCtx - NON_HISTORY_OVERHEAD, where overhead = 616 tokens:
        //   system block  ~200  (persona + memory injection)
        //   template tags  ~80  (chat-ml roles + separators)
        //   user fragment  ~80  (current message estimate)
        //   generation reserve ~256 (maxTokens headroom)
        //
        // The floor of 256 prevents pathological over-trimming on tiny models.
        // The ceil of Int.MAX_VALUE is not needed — nCtx is always a sane value.
        private const val NON_HISTORY_OVERHEAD = 616
        private const val MIN_HISTORY_TOKENS   = 256
    }

    /**
     * Full latency breakdown for the most recent generation, sourced directly
     * from the native bridge. Surfaces in the Generation Statistics screen.
     */
    data class LastInferenceMetrics(
        val loadMs: Long,
        val tokenizeMs: Long,
        val prefillMs: Long,
        val firstTokenMs: Long,
        val decodeMs: Long,
        val decodedTokens: Int,
        val nPast: Int,
        val nCtx: Int,
        val tokensPerSec: Float
    ) {
        val kvUsedPct: Int get() = if (nCtx > 0) (100L * nPast / nCtx).toInt() else 0
        companion object {
            val EMPTY = LastInferenceMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0f)
        }
    }

    @Volatile var lastMetrics: LastInferenceMetrics = LastInferenceMetrics.EMPTY
        private set

    // B-02: dynamic history budget — updated by applyRuntimeMode() when the user changes
    // performance mode. Defaults to BALANCED budget (1536 - 616 = 920, coerced to floor).
    @Volatile private var maxHistoryTokens: Int =
        (PerformanceMode.BALANCED.nCtx - NON_HISTORY_OVERHEAD).coerceAtLeast(MIN_HISTORY_TOKENS)

    /** Snapshot the native counters into [lastMetrics] (call after a gen). */
    private fun refreshMetrics() {
        runCatching {
            val a = LlamaNative.getLastTimings()
            if (a.size >= 8) {
                val decode = a[4].coerceAtLeast(1L)
                val tps = if (a[5] > 0) a[5] * 1000f / decode else 0f
                lastMetrics = LastInferenceMetrics(
                    loadMs        = a[0],
                    tokenizeMs    = a[1],
                    prefillMs     = a[2],
                    firstTokenMs  = a[3],
                    decodeMs      = a[4],
                    decodedTokens = a[5].toInt(),
                    nPast         = a[6].toInt(),
                    nCtx          = a[7].toInt(),
                    tokensPerSec  = tps
                )
                Log.i("AIRI_PERF",
                    "BREAKDOWN load=${a[0]}ms tok=${a[1]}ms prefill=${a[2]}ms " +
                    "first_tok=${a[3]}ms decode=${a[4]}ms toks=${a[5]} " +
                    "n_past=${a[6]}/${a[7]} kv_used=${lastMetrics.kvUsedPct}% tps=%.2f"
                        .format(tps))
            }
        }
    }

    /**
     * Hot-swap n_ctx + thread count without reloading model weights. Wipes the
     * native KV; we mark the session as invalidated so the next message
     * re-primes cleanly. Safe to call while a generation is NOT in flight.
     *
     * OMEGA CORE: wrapped in lifecycleLock so the KV teardown + session
     * invalidation is atomic from the Kotlin perspective, even if a future
     * refactor introduces a second dispatcher. The single-threaded
     * llamaDispatcher already serializes this behind any in-flight decode,
     * but the mutex is the belt-and-braces contract that holds regardless of
     * dispatcher topology.
     */
    fun applyRuntimeMode(mode: PerformanceMode) {
        if (!isLoaded) {
            if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "applyRuntimeMode skipped — model not loaded yet")
            return
        }
        scope.launch {
            lifecycleLock.withLock {
                try {
                    LlamaNative.setRuntimeMode(mode.nCtx, mode.nThreads)
                    // B-02: update dynamic history budget to match new nCtx
                    maxHistoryTokens = (mode.nCtx - NON_HISTORY_OVERHEAD).coerceAtLeast(MIN_HISTORY_TOKENS)
                    invalidateSession()
                    Log.i(TAG, "RUNTIME_MODE_APPLIED mode=${mode.name} n_ctx=${mode.nCtx} threads=${mode.nThreads} maxHistoryTokens=$maxHistoryTokens")
                } catch (e: Throwable) {
                    Log.e(TAG, "applyRuntimeMode failed: ${e.message}", e)
                }
            }
        }
    }

    fun cancelStream() {
        cancelRequested.set(true)
        // SPEC v2 — route cancellation through BOTH the legacy cancel() and
        // the new nativeCancel() entry points. They share the same underlying
        // atomic flag (g_cancel_requested is a reference to g_cancel) so the
        // double-call is free; calling both keeps the logging tags consistent
        // and means any future divergence between the two paths cannot leave
        // a cancellation request unacknowledged.
        runCatching { LlamaNative.cancel() }
        runCatching { LlamaNative.nativeCancel() }
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "cancelStream requested")
        Log.i("AIRI_PROOF", "GEN_CANCEL_REQUESTED tid=${Thread.currentThread().id}")
    }

    /**
     * SPEC v2 — CLEANUP step of the inference state machine.
     *
     * Tears the native llama_context down to the model and rebuilds it. Any
     * error during PREFLIGHT/PREFILL/GENERATE flows through here so the next
     * turn always starts from a known-good empty KV. After this call the
     * Kotlin session bookkeeping is also invalidated so the next turn will
     * re-prime cleanly via [reconcileSession].
     *
     * Safe to call while NO generation is in flight (callers run this on the
     * single-threaded llamaDispatcher, so it is automatically serialized
     * behind any in-flight generate()).
     */
    private fun fullReset(reason: String) {
        // SPEC v3 — canonical JVM-side reset marker. Pairs with the native
        // CONTEXT_RESET emitted from inside nativeFullReset(); the JVM marker
        // is logged FIRST so a crash inside nativeFullReset() can still be
        // attributed to the requesting reason from a single log scrape.
        val sidBefore = runCatching { LlamaNative.nativeGetSessionId() }.getOrNull() ?: -1L
        val genBefore = runCatching { LlamaNative.nativeGetGenerationId() }.getOrNull() ?: -1L
        Log.i("AIRI_PROOF",
            "CONTEXT_RESET origin=jvm reason=$reason " +
            "session_id_before=$sidBefore gen_id_before=$genBefore " +
            "primed_history=${primedHistory.size} session_primed=$sessionPrimed")
        Log.i("AIRI_PROOF", "FULL_RESET_REQUESTED reason=$reason")
        // 1. Raise the cancel flag in case anything is still mid-decode.
        runCatching { LlamaNative.nativeCancel() }
        // 2. Destroy + rebuild the native context with cached cparams.
        runCatching { LlamaNative.nativeFullReset() }
            .onSuccess  {
                val sidAfter = runCatching { LlamaNative.nativeGetSessionId() }.getOrNull() ?: -1L
                Log.i("AIRI_PROOF",
                    "FULL_RESET_OK reason=$reason session_id_after=$sidAfter")
            }
            .onFailure  { Log.e("AIRI_PROOF", "FULL_RESET_FAIL reason=$reason err=${it.message}") }
        // 3. Mark the JVM session as needing a fresh re-prime.
        invalidateSession()
        // 4. Clear the in-flight cancel latch — the next turn starts fresh.
        cancelRequested.set(false)
        // B-01: Notify UI that context was reset so user understands memory was cleared.
        runCatching {
            com.airi.assistant.core.debug.RuntimeEventLog.post(
                subsystem = "LlamaManager",
                severity  = com.airi.assistant.core.debug.EventSeverity.WARN,
                reason    = "CONTEXT_RESET reason=$reason"
            )
        }
        runCatching {
            com.airi.assistant.ui.activity.AgentActivityBus.emit(
                message  = "Context window full — older conversation history was cleared to continue.",
                category = com.airi.assistant.ui.activity.ActivityCategory.SYSTEM,
                severity = com.airi.assistant.ui.activity.ActivitySeverity.WARN
            )
        }
    }

    /**
     * SPEC v3 — TOKEN-BUDGET HISTORY TRIM
     *
     * Drops the OLDEST messages from `messages` until the running total of
     * `nativeCountTokens(content)` is ≤ maxHistoryTokens. Always preserves
     * the youngest message even if it alone exceeds the budget — the
     * preflight in `generateStream` is the safety net for that case (it will
     * fullReset on PREFLIGHT_OVERFLOW and re-prime from a clean slate).
     *
     * Pairs are NOT preserved deliberately: the model can handle a bare
     * `assistant` reply at the top of the window, and pair-preservation
     * either over-trims (drops two messages instead of one) or under-trims
     * (keeps a stale pair past budget). Token accuracy wins over chat-pair
     * symmetry — the alternative is a fixed-message-count budget which we
     * already ruled out (see maxHistoryTokens doc).
     *
     * Returns the trimmed list. Pure: never mutates `messages` in place.
     * Falls back to the input unchanged if the native tokenizer is
     * unavailable (no model loaded yet) — this path is only reachable from
     * the very first turn before loadModel completes, where there is no
     * history to trim anyway.
     */
    private fun trimHistoryByTokens(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val counts = IntArray(messages.size)
        var total = 0
        for (i in messages.indices) {
            val n = runCatching { LlamaNative.nativeCountTokens(messages[i].content) }
                .getOrDefault(-1)
            if (n < 0) {
                // Tokenizer not ready (no model loaded) or vocab failure.
                // The PREFLIGHT_OVERFLOW path will catch any actual overflow
                // downstream; do not invent counts.
                Log.w("AIRI_PROOF",
                    "TRIM_TOKENS_SKIP reason=tokenizer_unavailable status=$n " +
                    "messages=${messages.size}")
                return messages
            }
            counts[i] = n
            total += n
        }
        if (total <= maxHistoryTokens) {
            Log.i("AIRI_PROOF",
                "TRIM_TOKENS_NOOP total=$total budget=$maxHistoryTokens " +
                "messages=${messages.size}")
            return messages
        }
        // Drop oldest until under budget OR only the youngest remains.
        var firstKept = 0
        var running = total
        while (firstKept < messages.size - 1 && running > maxHistoryTokens) {
            running -= counts[firstKept]
            firstKept++
        }
        val kept = messages.subList(firstKept, messages.size).toList()
        Log.i("AIRI_PROOF",
            "TRIM_TOKENS_APPLIED dropped=$firstKept kept=${kept.size} " +
            "total_before=$total total_after=$running budget=$maxHistoryTokens")
        return kept
    }

    fun loadModel(path: String, onProgress: (Int) -> Unit = {}, onReady: (Boolean) -> Unit) {
        val modelFile = File(path)
        lastLoadFailure = null
        if (!modelFile.exists()) {
            lastLoadFailure = "file does not exist: $path"
            Log.e(TAG, "LOAD FAILED: $lastLoadFailure"); onReady(false); return
        }
        if (!modelFile.canRead()) {
            lastLoadFailure = "file is not readable: $path"
            Log.e(TAG, "LOAD FAILED: $lastLoadFailure"); onReady(false); return
        }
        if (!LlamaNative.isAvailable()) {
            lastLoadFailure = "native backend unavailable: ${LlamaNative.loadFailureMessage() ?: "airi_native could not be loaded"}"
            Log.e(TAG, "LOAD FAILED: $lastLoadFailure"); onReady(false); return
        }
        val inspection = ModelValidator.inspect(modelFile)
        Log.i(TAG, "LOAD START path=${modelFile.absolutePath} size=${modelFile.length() / (1024 * 1024)}MB ggufVersion=${inspection.ggufVersion} architecture=${inspection.architecture}")

        scope.launch {
            isLoaded = false
            invalidateSession()
            try {
                LlamaNative.loadModelWithProgress(
                    modelFile.absolutePath,
                    object : LlamaNative.ProgressCallback {
                        override fun onProgress(percent: Int) {
                            scope.launch(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                )
                isLoaded = true
                Log.i(TAG, "LOAD SUCCESS path=${modelFile.absolutePath}")
                com.airi.assistant.domain.verification.VerificationTracker.recordCheck("MODEL_LOAD", true, "path=${modelFile.absolutePath}")
                // PHASE 3: opportunistically attach the matching mmproj
                // sidecar so the unified attach flow can do real vision
                // inference without the user touching a separate button.
                maybeAutoLoadMmproj(modelFile.absolutePath)
                // PHASE 5: opportunistically attach a matching embedding
                // GGUF so the Memory pipeline produces real pooled vectors
                // instead of falling back to chat-context approximations.
                maybeAutoLoadEmbeddingModel(modelFile.absolutePath)
                withContext(Dispatchers.Main) { onReady(true) }
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "loadModelWithProgress unavailable — falling back: ${e.message}", e)
                val result = runCatching { LlamaNative.loadModel(modelFile.absolutePath) }
                    .getOrElse { ex ->
                        lastLoadFailure = "${ex.javaClass.simpleName}: ${ex.message}"
                        Log.e(TAG, "LOAD FAILED: $lastLoadFailure", ex); "Error"
                    }
                isLoaded = (result == "LOAD_SUCCESS" || result == "Success")
                if (isLoaded) {
                    Log.i(TAG, "LOAD SUCCESS path=${modelFile.absolutePath}")
                    com.airi.assistant.domain.verification.VerificationTracker.recordCheck("MODEL_LOAD", true, "path=${modelFile.absolutePath}")
                    // PHASE 3: same auto-mmproj wiring as the primary path.
                    maybeAutoLoadMmproj(modelFile.absolutePath)
                    // PHASE 5: same auto-embedding wiring as the primary path.
                    maybeAutoLoadEmbeddingModel(modelFile.absolutePath)
                } else {
                    lastLoadFailure = "native loader returned $result for architecture=${inspection.architecture}"
                    com.airi.assistant.domain.verification.VerificationTracker.recordCheck("MODEL_LOAD", false, lastLoadFailure ?: "unknown")
                }
                withContext(Dispatchers.Main) { onReady(isLoaded) }
            } catch (e: Exception) {
                lastLoadFailure = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "LOAD FAILED: $lastLoadFailure", e)
                com.airi.assistant.domain.verification.VerificationTracker.recordCheck("MODEL_LOAD", false, lastLoadFailure ?: "unknown")
                isLoaded = false
                withContext(Dispatchers.Main) { onReady(false) }
            }
        }
    }

    fun getLastLoadFailure(): String? = lastLoadFailure

    /**
     * Phase 2, finding E (revised after source audit):
     *
     * LlamaBridge.cpp exposes NO native unload-model entry point, and project
     * rules forbid modifying it. However, audit of LlamaBridge.cpp:647-648 and
     * :720-721 confirms that `loadModel()` ALREADY frees the previous g_ctx +
     * g_model before loading a new one — so model-swap is leak-free.
     *
     * The remaining true-leak surface is "user manually pressed Unload". For
     * that path, the most we can honestly do without touching the bridge is:
     *   1. Cancel any in-flight generation so we don't race the dispatcher.
     *   2. Run resetSession() (clears KV cache — frees several MB).
     *   3. Mark the manager as not-loaded so subsequent generate() calls reject.
     * The model weights themselves remain mmapped until the next loadModel()
     * call internally frees them. Because they are mmapped (not mlocked, see
     * LlamaBridge.cpp:655-656), the kernel can page them out under memory
     * pressure, so the practical leak is bounded.
     *
     * Every step is observable via AIRI_PROOF so the test on device can
     * confirm the sequence ran in the expected order.
     */
    fun unloadModel() {
        Log.i("AIRI_PROOF", "UNLOAD_REQUESTED was_loaded=$isLoaded")
        // Cancel BEFORE the dispatcher hop — the in-flight token loop reads
        // this flag every callback and will exit on the next tick.
        cancelRequested.set(true)
        runCatching { LlamaNative.cancel() }

        scope.launch {
            // We're now serialized behind any in-flight generate(), so it's
            // safe to touch native state.
            try {
                if (LlamaNative.isAvailable()) {
                    runCatching { LlamaNative.resetSession() }
                        .onSuccess { Log.i("AIRI_PROOF", "UNLOAD_KV_CLEARED") }
                        .onFailure { Log.w("AIRI_PROOF", "UNLOAD_KV_CLEAR_FAIL ${it.message}") }
                }
            } finally {
                isLoaded = false
                chatHistory.clear()
                invalidateSession()
                Log.i("AIRI_PROOF",
                    "UNLOAD_COMPLETE kv_cleared=true model_mmap_held=true " +
                    "note=loadModel_will_free_weights")
            }
        }
    }

    fun setHistory(messages: List<ChatMessage>) {
        chatHistory.clear()
        chatHistory.addAll(trimContext(messages))
    }

    /** Mark the native session as needing a fresh `beginSession()` next time. */
    private fun invalidateSession() {
        sessionPrimed = false
        primedModelPath = null
        primedSystemPrompt = null
        primedHistory.clear()
    }

    // =========================================================================
    // Per-model chat-template fragments
    // =========================================================================

    private fun systemBlock(type: ModelType, systemPrompt: String): String {
        if (systemPrompt.isBlank()) return ""
        return when (type) {
            ModelType.GEMMA   -> "<start_of_turn>user\n$systemPrompt<end_of_turn>\n"
            ModelType.LLAMA   -> "<|start_header_id|>system<|end_header_id|>\n$systemPrompt<|eot_id|>\n"
            ModelType.MISTRAL -> ""   // folded into the first user turn instead
            else              -> "<|im_start|>system\n$systemPrompt\n<|im_end|>\n"
        }
    }

    /** A *completed* historical user message — no assistant opener attached. */
    private fun userBody(type: ModelType, text: String): String = when (type) {
        ModelType.GEMMA   -> "<start_of_turn>user\n$text<end_of_turn>\n"
        ModelType.LLAMA   -> "<|start_header_id|>user<|end_header_id|>\n$text<|eot_id|>\n"
        ModelType.MISTRAL -> "[INST] $text [/INST] "
        else              -> "<|im_start|>user\n$text<|im_end|>\n"
    }

    /** A *completed* historical assistant message including its closing tag. */
    private fun assistantBody(type: ModelType, text: String): String = when (type) {
        ModelType.GEMMA   -> "<start_of_turn>model\n$text<end_of_turn>\n"
        ModelType.LLAMA   -> "<|start_header_id|>assistant<|end_header_id|>\n$text<|eot_id|>\n"
        ModelType.MISTRAL -> "$text</s>"
        else              -> "<|im_start|>assistant\n$text<|im_end|>\n"
    }

    /**
     * The *new* user turn fragment with the assistant opener appended so the
     * native engine is positioned to sample. `embedSystem` is used by Mistral
     * (which has no separate system role) when this is the very first user
     * turn of a fresh session.
     */
    private fun newUserTurnFragment(
        type: ModelType,
        userText: String,
        systemPrompt: String,
        embedSystem: Boolean
    ): String = when (type) {
        ModelType.GEMMA   -> "<start_of_turn>user\n$userText<end_of_turn>\n<start_of_turn>model\n"
        ModelType.LLAMA   -> "<|start_header_id|>user<|end_header_id|>\n$userText<|eot_id|>\n<|start_header_id|>assistant<|end_header_id|>\n"
        ModelType.MISTRAL -> if (embedSystem && systemPrompt.isNotBlank())
            "[INST] <<SYS>>\n$systemPrompt\n<</SYS>>\n\n$userText [/INST] "
            else "[INST] $userText [/INST] "
        else              -> "<|im_start|>user\n$userText<|im_end|>\n<|im_start|>assistant\n"
    }

    /** Closing tag fed into KV after the assistant finishes generating. */
    private fun assistantCloseTag(type: ModelType): String = when (type) {
        ModelType.GEMMA   -> "<end_of_turn>\n"
        ModelType.LLAMA   -> "<|eot_id|>\n"
        ModelType.MISTRAL -> "</s>"
        else              -> "<|im_end|>\n"
    }

    // =========================================================================
    // Session reconciliation
    // =========================================================================
    /**
     * Make sure the native KV state is consistent with the supplied
     * [systemPrompt] and the prior conversation in [chatHistory] (which does
     * NOT include the in-flight user message). Returns the list of historical
     * messages that still needed to be replayed (informational only).
     *
     * - If the model, system prompt, or any previously-primed message has
     *   changed, we hard-reset and replay everything.
     * - Otherwise we only feed the tail of history that isn't yet in KV.
     */
    private fun reconcileSession(modelPath: String, modelType: ModelType, systemPrompt: String): Int {
        val needsReset = !sessionPrimed
                || primedModelPath != modelPath
                || primedSystemPrompt != systemPrompt
                || !primedIsPrefixOfChatHistory()

        if (needsReset) {
            LlamaNative.beginSession()
            primedHistory.clear()

            // Prime system prompt as a non-logits append (Mistral's is empty by design).
            val sys = systemBlock(modelType, systemPrompt)
            if (sys.isNotEmpty()) {
                LlamaNative.appendAssistantTurn(sys)
            }
            // Replay historical turns ONE MESSAGE AT A TIME. Splitting prevents
            // a single massive tokenise+decode call that can OOM or overflow the
            // KV on long histories, and allows the native cancel check to fire
            // between messages instead of only within a 64-token chunk of a
            // giant combined string. If any individual message throws
            // CONTEXT_OVERFLOW the exception propagates to the outer catch,
            // which calls fullReset() and surfaces an error; the next turn then
            // re-primes from the trimmed chatHistory (which by then will be
            // shorter due to maxHistoryTokens trimming).
            for (msg in chatHistory) {
                // ── SPEC v3 — Kotlin-level cancel guard ──────────────────────
                // The native g_cancel_requested check fires at the start of the
                // first 64-token chunk of the next appendAssistantTurn call,
                // but checking the Kotlin-side AtomicBoolean here avoids a JNI
                // hop entirely when cancelStream() is called mid-replay. The
                // thrown exception propagates to generateStream's outer catch,
                // which routes through fullReset() → invalidateSession() so the
                // next turn re-primes cleanly. This bounds cancellation latency
                // to O(1 message boundary) rather than O(1 token-chunk).
                if (cancelRequested.get()) {
                    Log.i("AIRI_PROOF",
                        "RECONCILE_CANCELLED phase=hard_reset_replay role=${msg.role}")
                    throw RuntimeException("RECONCILE_CANCELLED")
                }
                val fragment = when (msg.role) {
                    "user"      -> userBody(modelType, msg.content)
                    "assistant" -> assistantBody(modelType, msg.content)
                    else        -> null
                }
                if (fragment != null) LlamaNative.appendAssistantTurn(fragment)
            }

            primedHistory.addAll(chatHistory)
            primedModelPath = modelPath
            primedSystemPrompt = systemPrompt
            sessionPrimed = true

            Log.i("AIRI_PROOF",
                "SESSION_REPRIMED model=${primedModelPath} history=${primedHistory.size} kv=${LlamaNative.getKvPosition()}/${LlamaNative.getNCtx()}")
            return chatHistory.size
        }

        // Incremental: replay any new turns that arrived since last time.
        val newTurns = chatHistory.subList(primedHistory.size, chatHistory.size)
        if (newTurns.isNotEmpty()) {
            // Per-message replay — same rationale as the hard-reset path above:
            // avoids one giant tokenise call and allows inter-message cancel checks.
            for (msg in newTurns) {
                // ── SPEC v3 — Kotlin-level cancel guard (incremental path) ───
                // Same rationale as the hard-reset path above: check the Kotlin
                // AtomicBoolean before every JNI hop so a cancelStream() during
                // incremental replay exits at a clean message boundary.
                if (cancelRequested.get()) {
                    Log.i("AIRI_PROOF",
                        "RECONCILE_CANCELLED phase=incremental_replay role=${msg.role}")
                    throw RuntimeException("RECONCILE_CANCELLED")
                }
                val fragment = when (msg.role) {
                    "user"      -> userBody(modelType, msg.content)
                    "assistant" -> assistantBody(modelType, msg.content)
                    else        -> null
                }
                if (fragment != null) LlamaNative.appendAssistantTurn(fragment)
            }
            primedHistory.addAll(newTurns)
            Log.i("AIRI_PROOF",
                "SESSION_INCREMENT delta=${newTurns.size} kv=${LlamaNative.getKvPosition()}/${LlamaNative.getNCtx()}")
        }
        return newTurns.size
    }

    private fun primedIsPrefixOfChatHistory(): Boolean {
        if (primedHistory.size > chatHistory.size) return false
        for (i in primedHistory.indices) {
            val a = primedHistory[i]
            val b = chatHistory[i]
            if (a.role != b.role || a.content != b.content) return false
        }
        return true
    }

    // =========================================================================
    // Generation entry points
    // =========================================================================

    /**
     * Blocking single-shot generation. Used by tool-call follow-up flows.
     * Note: this routes through the legacy native one-shot which RESETS the
     * session; we mark our session as invalidated so the next streaming call
     * re-primes.
     */
    fun generate(
        prompt: String,
        systemPrompt: String = defaultSystemPrompt(),
        maxTokens: Int = PerformanceMode.BALANCED.maxTokens,
        temperature: Float = PerformanceMode.BALANCED.temperature,
        onResult: (String) -> Unit
    ) {
        if (!isLoaded || ModelManager.getCurrent() == null) {
            onResult("Select and activate a local model before sending."); return
        }
        scope.launch {
            // Build a one-shot full prompt using the same template helpers.
            val type = ModelManager.getCurrent()?.type ?: ModelType.QWEN
            val sys = systemBlock(type, systemPrompt)
            val user = newUserTurnFragment(type, prompt, systemPrompt, embedSystem = true)
            val response = LlamaNative.generateResponse(sys + user)
            invalidateSession()
            withContext(Dispatchers.Main) { onResult(response) }
        }
    }

    fun generateStream(
        prompt: String,
        systemPrompt: String = defaultSystemPrompt(),
        maxTokens: Int = PerformanceMode.BALANCED.maxTokens,
        temperature: Float = PerformanceMode.BALANCED.temperature,
        repeatPenalty: Float = 1.1f,
        topK: Int = 40,
        topP: Float = 0.9f,
        minP: Float = 0.05f,
        presencePenalty: Float = 0.0f,
        frequencyPenalty: Float = 0.0f,
        penaltyLastN: Int = 64,
        timeoutMs: Long = DEFAULT_FIRST_TOKEN_TIMEOUT_MS,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit = {},
        onStallWarning: () -> Unit = {}
    ) {
        val model = ModelManager.getCurrent()
        if (!isLoaded || model == null) {
            // PHASE 6: even the early "engine not loaded" path must
            // contain user-supplied callbacks so an exception inside
            // onToken/onComplete cannot crash the app.
            scope.launch(Dispatchers.Main) {
                try { onToken("المحرك غير مفعل") }
                catch (t: Throwable) {
                    Log.w(TAG, "onToken(early) threw (swallowed): ${t.message}", t)
                }
                try { onComplete("") }
                catch (t: Throwable) {
                    Log.w(TAG, "onComplete(early) threw (swallowed): ${t.message}", t)
                }
            }
            return
        }

        cancelRequested.set(false)
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "generateStream params: maxTokens=$maxTokens temp=$temperature " +
                "first_token_budget=${timeoutMs}ms inactivity_budget=${INACTIVITY_TIMEOUT_MS}ms " +
                "prompt_len=${prompt.length}")

        val finished = AtomicBoolean(false)
        val firstTokenLogged = AtomicBoolean(false)
        val lastTokenAtMs = AtomicLong(System.currentTimeMillis())

        // Inactivity-based watchdog (never a wall-clock total deadline).
        // Also fires a non-fatal stall warning at STALL_WARNING_MS.
        // Phase 2, finding D: must run on watchdogScope (Dispatchers.Default),
        // NOT on `scope` (single-threaded llamaDispatcher), or it will be
        // starved while the native generate() call holds the only worker.
        val stallWarned = AtomicBoolean(false)
        watchdogScope.launch {
            while (!finished.get()) {
                delay(500L)
                val now = System.currentTimeMillis()
                val idle = now - lastTokenAtMs.get()
                val firstSeen = firstTokenLogged.get()
                val budget = if (firstSeen) INACTIVITY_TIMEOUT_MS else timeoutMs

                // Non-fatal stall warning (only AFTER first token has flowed).
                if (firstSeen && idle >= STALL_WARNING_MS && stallWarned.compareAndSet(false, true)) {
                    Log.w(TAG, "STALL_DETECTED idle=${idle}ms — decode is slow but still alive")
                    Log.i("AIRI_PROOF", "STALL idle_ms=$idle threshold_ms=$STALL_WARNING_MS")
                    withContext(Dispatchers.Main) { onStallWarning() }
                }

                if (idle >= budget) {
                    if (finished.compareAndSet(false, true)) {
                        val errCode = if (firstSeen) ERR_INACTIVITY_TIMEOUT else ERR_FIRST_TOKEN_TIMEOUT
                        Log.w(TAG, "generateStream timed out: code=$errCode idle=${idle}ms budget=${budget}ms")
                        Log.i("AIRI_PROOF", "TIMEOUT phase=${if (firstSeen) "INACTIVITY" else "FIRST_TOKEN"} idle_ms=$idle budget_ms=$budget")
                        cancelRequested.set(true)
                        // Call BOTH cancel routes for belt-and-suspenders parity
                        // with cancelStream(). cancel() and nativeCancel() write
                        // the same g_cancel atomic, but calling both means any
                        // future divergence between the two entry points cannot
                        // silently leave a cancel request unacknowledged.
                        runCatching { LlamaNative.cancel() }
                        runCatching { LlamaNative.nativeCancel() }
                        // PHASE 6: contain watchdog onError too.
                        withContext(Dispatchers.Main) {
                            try { onError("$errCode idle_ms=$idle budget_ms=$budget") }
                            catch (t: Throwable) {
                                Log.w(TAG, "watchdog onError threw: ${t.message}", t)
                            }
                        }
                    }
                    return@launch
                }
            }
        }

        scope.launch {
            val response = StringBuilder()
            val tokenBuffer = StringBuilder()
            var lastFlushTime = System.currentTimeMillis()
            val firstTokenStart = System.currentTimeMillis()
            var firstTokenMs = -1L
            var nativeTokenCount = 0

            // PHASE 1 instrumentation (per spec): one tag per lifecycle stage
            // so logcat can prove WHERE the pipeline stops if it ever does.
            // adb logcat | grep AIRI_PROOF should show this exact sequence
            // for every successful turn:
            //   GEN_START → CONTEXT_READY → PROMPT_TOKENIZED → FIRST_TOKEN
            //     → GENERATION_SUCCESS → GEN_END
            Log.i("AIRI_PROOF",
                "GEN_START model=${model.name} prompt_len=${prompt.length} " +
                "primed_history=${primedHistory.size} chat_history=${chatHistory.size}")

            // ── SPEC v3 — STRICT LIFECYCLE LOCK ─────────────────────────────
            // The entire turn (reconcile + prefill + decode + cleanup +
            // exception-recovery fullReset) is wrapped in lifecycleLock.
            // While this body runs, the parallel fullReset path in any
            // OTHER coroutine that enters this manager (e.g. from a future
            // multi-dispatcher refactor) cannot tear down the context
            // mid-decode. The native-side LLAMA_LOCK provides the same
            // guarantee at the JNI boundary; this is the JVM-level mirror
            // so the invariant holds even if a refactor moves work off
            // llamaDispatcher.
            //
            // cancelGeneration() / cancelOutsideGenerate() must NOT touch
            // this lock — they are intentionally lock-free, only flip
            // atomics + call lock-free nativeCancel().
            lifecycleLock.withLock {
            try {
                // ── SPEC v3 — STATE MACHINE: PREFLIGHT ───────────────────────
                // STATE_PREFLIGHT: session bookkeeping invalidation + token-based
                // history trim + KV reconcile + preflight overflow check.
                // On any exception from this block the catch route is taken and
                // STATE_ERROR is emitted before fullReset.
                Log.i("AIRI_PROOF", "STATE_PREFLIGHT session_primed=$sessionPrimed " +
                    "primed_history=${primedHistory.size} chat_history=${chatHistory.size}")

                // ── CANCEL FLAG SANITISE ──────────────────────────────────────
                // Clear any stale native cancel from the previous turn. This is
                // the FIRST native call in every generation cycle. Without it a
                // cancel flag left by (a) a user cancel, (b) a watchdog timeout,
                // or (c) the generate-entry early-exit in airi_generate_next
                // (which returns status=-2 WITHOUT reaching the store(false) that
                // would clear the flag) survives into the INCREMENTAL session
                // path (sessionPrimed=true → beginSession() NOT called → native
                // cancel never cleared) and immediately throws PREFILL_CANCELLED
                // on the very next appendUserTurn — freezing the conversation
                // after 1–3 messages.
                runCatching { LlamaNative.nativeClearCancel() }
                    .onFailure { t ->
                        Log.w("AIRI_PROOF", "CLEAR_CANCEL_FAIL reason=${t.message}")
                    }
                Log.i("AIRI_PROOF",
                    "CANCEL_SANITISED session_primed=$sessionPrimed")

                // ── TOKEN-BASED HISTORY BUDGET ────────────────────────────────
                // Trim chatHistory so the raw content tokens stay within
                // maxHistoryTokens. This accounts for the fact that a single
                // Arabic / CJK message can be 200-400 tokens, so a fixed
                // message-count cap (maxHistory=4) is insufficient on its own.
                // Template overhead (~15 tok/msg) and system + user + generate
                // headroom are NOT counted by nativeCountTokens — maxHistoryTokens
                // is set conservatively (750) to leave room for all of those.
                // If trimming removes any messages the KV is now a SUPERSET of
                // the new chatHistory; invalidate so reconcileSession hard-resets.
                val historyBeforeTrim = chatHistory.size
                val trimmedByTokens = trimHistoryByTokens(chatHistory.toList())
                if (trimmedByTokens.size != historyBeforeTrim) {
                    chatHistory.clear()
                    chatHistory.addAll(trimmedByTokens)
                    Log.i("AIRI_PROOF",
                        "HISTORY_TOKEN_TRIM dropped=${historyBeforeTrim - chatHistory.size} " +
                        "kept=${chatHistory.size} budget_tokens=$maxHistoryTokens " +
                        "→ invalidating session (KV superset of new history)")
                    invalidateSession()
                }

                // Reconcile native KV with the requested system prompt + history.
                val reconcileStart = System.currentTimeMillis()
                val replayedTurns = reconcileSession(model.path, model.type, systemPrompt)
                val reconcileMs = System.currentTimeMillis() - reconcileStart
                if (replayedTurns > 0 && com.airi.assistant.BuildConfig.DEBUG) {
                    Log.d("AIRI_STREAM", "session_reconcile replayed_turns=$replayedTurns ms=$reconcileMs")
                }
                Log.i("AIRI_PROOF",
                    "CONTEXT_READY replayed_turns=$replayedTurns reconcile_ms=$reconcileMs " +
                    "kv=${runCatching { LlamaNative.getKvPosition() }.getOrDefault(-1)}/" +
                    "${runCatching { LlamaNative.getNCtx() }.getOrDefault(-1)}")

                // Append the new user turn (logits=true on its last token).
                val isFirstUserOfSession = primedHistory.none { it.role == "user" }
                val userFragment = newUserTurnFragment(
                    type = model.type,
                    userText = prompt,
                    systemPrompt = systemPrompt,
                    embedSystem = isFirstUserOfSession
                )

                // ── SPEC v2 — PREFLIGHT (JVM side) ────────────────────────
                // Per the state-machine spec the preflight check is:
                //   IF (n_past + input_tokens + 128 >= n_ctx) → fullReset()
                // i.e. instead of trimming history pairs, we tear down the
                // native context completely and rebuild from the last 3-4
                // messages already capped by maxHistory. This is the only way
                // to guarantee KV is never left in a half-trimmed state.
                //
                // Note: the +128 reserve covers max_new sampling headroom plus
                // template overhead and matches the native overflow check
                // (which uses n_past + n_tokens >= n_ctx with no reserve, so
                // the JVM check fires FIRST and routes through fullReset).
                val nPastBefore = runCatching { LlamaNative.getKvPosition() }.getOrDefault(-1)
                val nCtxNow     = runCatching { LlamaNative.getNCtx() }.getOrDefault(0)
                val estUserNew  = estimateTokens(userFragment)
                val estNeeded   = estUserNew + 128
                val freeRoom    = if (nCtxNow > 0 && nPastBefore >= 0)
                                      (nCtxNow - nPastBefore).coerceAtLeast(0)
                                  else Int.MAX_VALUE

                if (nCtxNow > 0 && estNeeded >= freeRoom) {
                    Log.i("AIRI_PROOF",
                        "PREFLIGHT_OVERFLOW n_past=$nPastBefore n_ctx=$nCtxNow " +
                        "user_est=$estUserNew reserve=128 needed=$estNeeded " +
                        "free=$freeRoom → fullReset")
                    fullReset("PREFLIGHT_OVERFLOW")
                    // After a full reset, KV is empty. Re-prime from the last
                    // 3-4 messages (already capped by maxHistory) via the
                    // standard reconcile path. This rebuilds prompt cleanly
                    // per Phase 3 spec ("rebuild prompt every turn using last
                    // 3-4 messages ONLY; discard older history").
                    val replayed2 = reconcileSession(model.path, model.type, systemPrompt)
                    Log.i("AIRI_PROOF",
                        "PREFLIGHT_REPRIMED replayed=$replayed2 " +
                        "kv=${runCatching { LlamaNative.getKvPosition() }.getOrDefault(-1)}/" +
                        "${runCatching { LlamaNative.getNCtx() }.getOrDefault(-1)}")
                } else {
                    if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_PROOF",
                        "PREFLIGHT_OK n_past=$nPastBefore n_ctx=$nCtxNow " +
                        "user_est=$estUserNew needed=$estNeeded free=$freeRoom")
                }

                // ── SPEC v3 — STATE MACHINE: PREFILL ────────────────────────
                // STATE_PREFILL: user turn is being tokenised + fed into the
                // native KV. runAppendWithSafeHandler blocks until the last
                // token's logit is ready. Any cancel raised between this log
                // and STATE_GENERATE will surface as a -2 CANCELLED status
                // or as a thrown exception routed to STATE_ERROR.
                Log.i("AIRI_PROOF", "STATE_PREFILL fragment_chars=${userFragment.length} " +
                    "kv=${runCatching { LlamaNative.getKvPosition() }.getOrDefault(-1)}/" +
                    "${runCatching { LlamaNative.getNCtx() }.getOrDefault(-1)}")

                // ── SPEC v2 — APPEND with status-driven recovery ────────────
                // The new airi_append_text returns one of:
                //    0 = ok, -1 = decode error, -2 = cancelled, -3 = overflow
                // (via nativeGetLastStatus()) and throws on the same -1/-2/-3
                // exit paths. -3 OVERFLOW is recoverable ONCE: fullReset and
                // re-prime with system + last user only. -1 ERROR is a hard
                // failure. -2 CANCELLED is propagated cleanly.
                runAppendWithSafeHandler(
                    fragment = userFragment,
                    model = model,
                    systemPrompt = systemPrompt
                )
                Log.i("AIRI_PROOF",
                    "PROMPT_TOKENIZED user_fragment_chars=${userFragment.length} " +
                    "kv=${runCatching { LlamaNative.getKvPosition() }.getOrDefault(-1)}/" +
                    "${runCatching { LlamaNative.getNCtx() }.getOrDefault(-1)}")

                // Bug B fix: reset the watchdog clock NOW, after all prefill
                // work (reconcile + appendUserTurn) has finished. Prior to
                // this fix lastTokenAtMs was set before reconcile started, so
                // a 60-90s cold-reset replay counted against the 120s
                // first-token budget and tripped ERR_FIRST_TOKEN_TIMEOUT
                // during legitimate slow prefill on mid-range CPUs.
                // From here, the watchdog only times the actual decode loop.
                lastTokenAtMs.set(System.currentTimeMillis())

                // Decide whether to use speculative decoding for this generation.
                // If the user has the feature OFF, OR the draft model is not
                // currently loaded on the native side, we use the standard
                // single-token path. The speculative native fn is also internally
                // self-fallbacking, but checking here lets us avoid a needless
                // JNI hop and keeps logging clean.
                val specMgr = SpeculativeManager(context)
                val useSpec = specMgr.isEnabled() &&
                              runCatching { LlamaNative.isDraftLoaded() }.getOrDefault(false)
                if (useSpec) {
                    Log.i("AIRI_SPEC", "generate via=speculative draftN=${specMgr.getDraftDraftN()}")
                }

                // ── SPEC v4 — push sampling params BEFORE every generate ─────
                // The native sampler chain is built fresh inside airi_generate_next()
                // on every call. Without this push, the chain always used the old
                // hardcoded defaults (temp=0.7, top_k=40, top_p=0.9, no penalties),
                // silently ignoring every user change in Generation Settings.
                //
                // We call nativeSetSamplingParams here — inside lifecycleLock and
                // on the single-threaded llamaDispatcher — so it is guaranteed to
                // be serialised with the following generateNextTokens call. The
                // native side additionally takes LLAMA_LOCK as a belt-and-braces
                // guard. runCatching wraps the call so a missing native symbol (e.g.
                // device running an older APK) degrades gracefully to the defaults.
                runCatching {
                    LlamaNative.nativeSetSamplingParams(
                        temperature       = temperature,
                        topK              = topK,
                        topP              = topP,
                        minP              = minP,
                        repeatPenalty     = repeatPenalty,
                        presencePenalty   = presencePenalty,
                        frequencyPenalty  = frequencyPenalty,
                        penaltyLastN      = penaltyLastN
                    )
                }.onSuccess {
                    Log.i("AIRI_PROOF",
                        "SAMPLING_PARAMS_PUSHED temp=$temperature top_k=$topK " +
                        "top_p=$topP min_p=$minP repeat=$repeatPenalty " +
                        "pres=$presencePenalty freq=$frequencyPenalty " +
                        "penalty_last_n=$penaltyLastN")
                }.onFailure { t ->
                    Log.w("AIRI_PROOF",
                        "SAMPLING_PARAMS_PUSH_FAILED ${t.javaClass.simpleName}: ${t.message} " +
                        "— falling back to native defaults")
                }

                // ── SPEC v3 — STATE MACHINE: GENERATE ───────────────────────
                // STATE_GENERATE: the decode loop is about to start. From
                // this point the native thread is running llama_decode in a
                // tight loop. Cancellation via nativeCancel() is the only
                // async-safe way to interrupt it; all other paths wait for
                // generateNextTokens to return before taking action.
                //
                // SESSION-ID CAPTURE: snapshot the native session id RIGHT
                // BEFORE the decode call. Any callback delivered after a
                // session bump (fullReset, setRuntimeMode, …) is stale and
                // must be dropped — see tokenCallback body below.
                val sessionIdAtStart = runCatching { LlamaNative.nativeGetSessionId() }
                    .getOrDefault(-1L)
                val genIdAtStart = runCatching { LlamaNative.nativeGetGenerationId() }
                    .getOrDefault(-1L)
                Log.i("AIRI_PROOF",
                    "STATE_GENERATE session_id=$sessionIdAtStart gen_id=$genIdAtStart " +
                    "max_tokens=$maxTokens " +
                    "kv=${runCatching { LlamaNative.getKvPosition() }.getOrDefault(-1)}/" +
                    "${runCatching { LlamaNative.getNCtx() }.getOrDefault(-1)}")

                val tokenCallback: (String) -> Unit = tokenCallback@ { token ->
                    // SPEC v3 — STALE-CALLBACK DROP. If the native session
                    // was replaced (fullReset, setRuntimeMode, …) between
                    // when we captured sessionIdAtStart and now, this
                    // callback is from a destroyed context. Drop silently;
                    // do NOT mutate response/tokenBuffer/nativeTokenCount,
                    // do NOT advance lastTokenAtMs (let watchdog fire),
                    // do NOT dispatch to Main. Log once per drop so an
                    // operator can grep STALE_TOKEN_DROPPED.
                    val sidNow = runCatching { LlamaNative.nativeGetSessionId() }
                        .getOrDefault(sessionIdAtStart)
                    if (sidNow != sessionIdAtStart) {
                        Log.w("AIRI_PROOF",
                            "STALE_TOKEN_DROPPED phase=native_callback " +
                            "captured_session=$sessionIdAtStart " +
                            "current_session=$sidNow " +
                            "tokens_so_far=$nativeTokenCount " +
                            "bytes=${token.length}")
                        return@tokenCallback
                    }

                    if (cancelRequested.get()) {
                        // Log the honored-cancellation exactly once per stream.
                        if (firstTokenLogged.get() && !finished.get()) {
                            Log.i("AIRI_PROOF",
                                "GEN_CANCEL_HONORED tokens_emitted=$nativeTokenCount")
                        }
                        return@tokenCallback
                    }
                    if (finished.get()) return@tokenCallback
                    response.append(token)
                    tokenBuffer.append(token)
                    nativeTokenCount++
                    lastTokenAtMs.set(System.currentTimeMillis())
                    // Per-token trace — gated to AIRI_TOKEN tag and BuildConfig.DEBUG.
                    // adb shell setprop log.tag.AIRI_TOKEN VERBOSE
                    if (com.airi.assistant.BuildConfig.DEBUG) Log.v("AIRI_TOKEN", "n=$nativeTokenCount bytes=${token.length}")

                    val isFirst = firstTokenLogged.compareAndSet(false, true)
                    if (isFirst) {
                        firstTokenMs = System.currentTimeMillis() - firstTokenStart
                        com.airi.assistant.domain.verification.VerificationTracker.recordCheck(
                            "FIRST_TOKEN", true, "streaming token emitted"
                        )
                        Log.i("AIRI_PROOF", "FIRST_TOKEN token_emitted=true model=${model.name} first_token_ms=$firstTokenMs session_id=$sessionIdAtStart gen_id=$genIdAtStart")
                    }

                    val now = System.currentTimeMillis()
                    val shouldFlush = isFirst ||
                            (now - lastFlushTime >= TOKEN_BATCH_MS) ||
                            (tokenBuffer.length >= TOKEN_BATCH_CHARS)
                    if (shouldFlush) {
                        val batch = tokenBuffer.toString()
                        tokenBuffer.clear()
                        lastFlushTime = now
                        // PHASE 6: harden the Main-dispatched callback. A UI
                        // exception (Compose state update after disposal,
                        // unexpected substring index from a downstream
                        // listener, etc.) must NOT escape and tear down the
                        // process — it would also leave the native KV in a
                        // half-decoded state on the next request.
                        scope.launch(Dispatchers.Main) {
                            // SPEC v3 — re-check BOTH session id AND generation
                            // id on the Main dispatch boundary.
                            //
                            // Session-id alone is insufficient: for incremental
                            // sessions (no beginSession() between turns) the
                            // session id stays constant, so a stale Main-dispatch
                            // from generation N arrives at generation N+1 with
                            // an identical sessionIdAtStart and would NOT be
                            // dropped by the session-id check alone.
                            //
                            // Generation id: g_generation_id is bumped at entry
                            // of every airi_generate_next() call.  The value we
                            // captured before calling generateNextTokens() is
                            // genIdAtStart; the active generation's id is
                            // genIdAtStart+1.  A callback from an older
                            // generation will read genIdAtStart+2 or higher and
                            // must be dropped.
                            val sidOnMain = runCatching { LlamaNative.nativeGetSessionId() }
                                .getOrDefault(sessionIdAtStart)
                            val genIdExpected = genIdAtStart + 1L
                            val genIdOnMain   = runCatching { LlamaNative.nativeGetGenerationId() }
                                .getOrDefault(genIdExpected)
                            if (sidOnMain != sessionIdAtStart || genIdOnMain != genIdExpected) {
                                Log.w("AIRI_PROOF",
                                    "STALE_TOKEN_DROPPED phase=main_dispatch " +
                                    "captured_session=$sessionIdAtStart " +
                                    "current_session=$sidOnMain " +
                                    "gen_expected=$genIdExpected " +
                                    "gen_current=$genIdOnMain " +
                                    "batch_chars=${batch.length}")
                                return@launch
                            }
                            try { onToken(batch) }
                            catch (t: Throwable) {
                                Log.w(TAG, "onToken threw (swallowed): ${t.message}", t)
                            }
                        }
                    }
                }

                if (useSpec) {
                    LlamaNative.generateNextTokensSpeculative(
                        maxTokens, specMgr.getDraftDraftN(), tokenCallback
                    )
                } else {
                    LlamaNative.generateNextTokens(maxTokens, tokenCallback)
                }

                // ── SPEC v3 — STRICT STATUS-DRIVEN STATE MACHINE ────────────
                //
                // generateNextTokens has returned. g_last_gen_status holds the
                // exit code; the JVM must now route through the correct state
                // and, critically, MUST NOT touch the native context or the
                // streaming buffers in ways that are invalid for that state.
                //
                // AUDIT FINDINGS (fixed here):
                //   BUG-A: fullReset() sets cancelRequested=false; the old tail-
                //           flush guard (!cancelRequested) was therefore TRUE
                //           after ERROR/OVERFLOW, causing stale tokens to be
                //           dispatched to the UI even after a context tear-down.
                //   BUG-B: appendAssistantTurn() was called unconditionally,
                //           including after fullReset() which already wiped the
                //           native ctx — this corrupted the freshly-rebuilt ctx.
                //   BUG-C: finished.compareAndSet(false,true) was reachable from
                //           ERROR and OVERFLOW paths so onComplete(partial) fired
                //           instead of onError.
                //   BUG-D: tokenBuffer and response were never explicitly cleared
                //           in non-success paths — a future refactor could read
                //           stale state from them.
                //   BUG-F: the tail flush had no session-id guard, unlike the
                //           per-batch callback.
                //
                // The fix: each status routes through its own branch. Non-success
                // branches hard-clear tokenBuffer, set finished=true, do NOT
                // flush the tail, do NOT call appendAssistantTurn, and call
                // onError (for ERROR/OVERFLOW) or onComplete-with-partial (for
                // CANCELLED). Only the OK (status==0) branch reaches the tail-
                // flush, appendAssistantTurn, and onComplete calls below.
                val genStatus = runCatching { LlamaNative.nativeGetLastStatus() }
                    .getOrDefault(0)

                when (genStatus) {
                    -1 -> {
                        // ── STATE_ERROR ────────────────────────────────────────
                        // The decode loop hit an unrecoverable native error. KV
                        // is potentially torn. Hard-clear all streaming state,
                        // full-reset the native context, then surface onError.
                        // MUST NOT fall through to tail flush / appendAssistantTurn
                        // / onComplete — the context no longer holds valid state.
                        Log.e("AIRI_PROOF",
                            "STATE_ERROR status=-1 emitted=$nativeTokenCount → fullReset+onError")
                        Log.i("AIRI_PROOF",
                            "STATE_CLEANUP reason=gen_error " +
                            "clearing: tokenBuffer(${tokenBuffer.length}B) " +
                            "nativeTokenCount=$nativeTokenCount")
                        tokenBuffer.clear()
                        response.setLength(0)
                        nativeTokenCount = 0
                        fullReset("GEN_STATUS_ERROR")
                        if (finished.compareAndSet(false, true)) {
                            withContext(Dispatchers.Main) {
                                try { onError("$ERR_NATIVE decode_error status=-1") }
                                catch (t: Throwable) {
                                    Log.w(TAG, "onError(gen_error) threw: ${t.message}", t)
                                }
                            }
                        }
                        Log.i("AIRI_PROOF", "STATE_IDLE after=gen_error")
                    }

                    -3 -> {
                        // ── STATE_ERROR (OVERFLOW) ─────────────────────────────
                        // KV overflow during decode — ran out of context slots
                        // mid-generation. Partial output was already streamed
                        // to the UI. Hard-clear streaming state, full-reset,
                        // then surface onError so the caller can show a recovery
                        // message. The next turn will re-prime from trimmed
                        // chatHistory (which Phase 3 rebuilds every turn).
                        // MUST NOT call appendAssistantTurn on the just-reset ctx.
                        Log.w("AIRI_PROOF",
                            "STATE_ERROR status=-3 overflow emitted=$nativeTokenCount → fullReset+onError")
                        Log.i("AIRI_PROOF",
                            "STATE_CLEANUP reason=gen_overflow " +
                            "clearing: tokenBuffer(${tokenBuffer.length}B) " +
                            "nativeTokenCount=$nativeTokenCount")
                        tokenBuffer.clear()
                        response.setLength(0)
                        nativeTokenCount = 0
                        fullReset("GEN_STATUS_OVERFLOW")
                        if (finished.compareAndSet(false, true)) {
                            withContext(Dispatchers.Main) {
                                try { onError("$ERR_NATIVE context_overflow status=-3") }
                                catch (t: Throwable) {
                                    Log.w(TAG, "onError(overflow) threw: ${t.message}", t)
                                }
                            }
                        }
                        Log.i("AIRI_PROOF", "STATE_IDLE after=gen_overflow")
                    }

                    -2 -> {
                        // ── STATE_CANCELLED ────────────────────────────────────
                        // User or watchdog triggered cancellation. The decode loop
                        // exited cleanly (no KV tear, no llama_decode in flight).
                        // Partial tokens streamed so far are the visible response.
                        // Hard-clear the token buffer (anything buffered but not
                        // yet dispatched is stale — the Main dispatch may already
                        // be in the queue; the session-id guard in the dispatch
                        // block will drop it). Do NOT call appendAssistantTurn —
                        // the cancelled context is not at a well-defined KV
                        // position; Phase 3 will rebuild from scratch next turn.
                        val partialResponse = response.toString()
                        Log.i("AIRI_PROOF",
                            "STATE_CANCELLED status=-2 emitted=$nativeTokenCount " +
                            "partial_chars=${partialResponse.length}")
                        Log.i("AIRI_PROOF",
                            "STATE_CLEANUP reason=cancelled " +
                            "clearing: tokenBuffer(${tokenBuffer.length}B)")
                        tokenBuffer.clear()
                        // Invalidate the session: the decode was stopped mid-stream,
                        // so g_n_past is at an arbitrary position and the KV tail
                        // is incomplete. Without this, a restored incremental path
                        // would call appendUserTurn on top of the dangling state,
                        // corrupting the next turn's context. beginSession() on the
                        // next reconcileSession clears and resets everything.
                        invalidateSession()
                        // Belt-and-suspenders: clear the native cancel flag NOW so
                        // it does not persist until the next generation's
                        // nativeClearCancel() call. Since we are inside
                        // lifecycleLock and on the single-threaded llamaDispatcher,
                        // no generate can race this write.
                        runCatching { LlamaNative.nativeClearCancel() }
                            .onFailure { t ->
                                Log.w("AIRI_PROOF",
                                    "STATE_CANCELLED clear_cancel_fail=${t.message}")
                            }
                        // response is preserved — the UI already rendered it.
                        // Surface it via onComplete so the chat bubble closes.
                        if (finished.compareAndSet(false, true)) {
                            withContext(Dispatchers.Main) {
                                try { onComplete(partialResponse) }
                                catch (t: Throwable) {
                                    Log.w(TAG, "onComplete(cancelled) threw: ${t.message}", t)
                                }
                            }
                        }
                        Log.i("AIRI_PROOF", "STATE_IDLE after=cancelled")
                    }

                    else -> {
                        // ── STATE_COMPLETE ─────────────────────────────────────
                        // Normal completion: EOS token reached or max_new_tokens
                        // exhausted. The decode loop exited cleanly.
                        //
                        // Flush any trailing bytes still in tokenBuffer. The tail
                        // may exist when TOKEN_BATCH_MS > 0 (batched mode) or
                        // when a multi-byte UTF-8 cluster was being assembled at
                        // the moment the loop ended. Apply the session-id guard
                        // (same as the per-batch dispatch) to prevent a tail from
                        // a generation that raced a reset from reaching the UI.
                        val tail = tokenBuffer.toString()
                        if (tail.isNotEmpty()) {
                            val sidForTail    = runCatching { LlamaNative.nativeGetSessionId() }
                                .getOrDefault(sessionIdAtStart)
                            val genIdExpectedTail = genIdAtStart + 1L
                            val genIdForTail  = runCatching { LlamaNative.nativeGetGenerationId() }
                                .getOrDefault(genIdExpectedTail)
                            val tailStale = sidForTail != sessionIdAtStart ||
                                            genIdForTail != genIdExpectedTail
                            if (!tailStale) {
                                scope.launch(Dispatchers.Main) {
                                    val sidOnMain    = runCatching { LlamaNative.nativeGetSessionId() }
                                        .getOrDefault(sessionIdAtStart)
                                    val genIdOnMain2 = runCatching { LlamaNative.nativeGetGenerationId() }
                                        .getOrDefault(genIdExpectedTail)
                                    if (sidOnMain != sessionIdAtStart ||
                                        genIdOnMain2 != genIdExpectedTail) {
                                        Log.w("AIRI_PROOF",
                                            "STALE_TOKEN_DROPPED phase=tail_dispatch " +
                                            "captured_session=$sessionIdAtStart " +
                                            "current_session=$sidOnMain " +
                                            "gen_expected=$genIdExpectedTail " +
                                            "gen_current=$genIdOnMain2 " +
                                            "tail_chars=${tail.length}")
                                        return@launch
                                    }
                                    try { onToken(tail) }
                                    catch (t: Throwable) {
                                        Log.w(TAG, "onToken(tail) threw: ${t.message}", t)
                                    }
                                }
                            } else {
                                Log.w("AIRI_PROOF",
                                    "STALE_TOKEN_DROPPED phase=tail_pre_dispatch " +
                                    "captured_session=$sessionIdAtStart " +
                                    "current_session=$sidForTail " +
                                    "gen_expected=$genIdExpectedTail " +
                                    "gen_current=$genIdForTail " +
                                    "tail_chars=${tail.length}")
                            }
                        }
                        tokenBuffer.clear()

                        // Close the assistant turn in KV so the next user turn
                        // aligns. Safe here because status==0 means the native
                        // context is intact (no fullReset was called above).
                        runCatching {
                            LlamaNative.appendAssistantTurn(assistantCloseTag(model.type))
                        }

                        if (finished.compareAndSet(false, true)) {
                            val full = response.toString()

                            // Update primed history with both the new user turn AND
                            // the generated assistant turn.
                            primedHistory.add(ChatMessage(role = "user",      content = prompt))
                            primedHistory.add(ChatMessage(role = "assistant", content = full))
                            chatHistory.add(ChatMessage(role = "user",      content = prompt))
                            chatHistory.add(ChatMessage(role = "assistant", content = full))
                            trimHistory()
                            if (primedHistory.size > chatHistory.size) {
                                // trimHistory() removed messages that are still
                                // in KV: the incremental path would falsely think
                                // nothing new needs to be appended and call
                                // appendUserTurn on stale KV. Force a hard-reset
                                // on the next turn so reconcileSession rebuilds
                                // from the trimmed chatHistory.
                                invalidateSession()
                                Log.i("AIRI_PROOF",
                                    "PRIMED_DRIFT primed_was=${primedHistory.size} " +
                                    "chat=${chatHistory.size} " +
                                    "→ invalidated (KV has trimmed-out messages)")
                            }

                            val nPast = runCatching { LlamaNative.getKvPosition() }.getOrDefault(-1)
                            val nCtx  = runCatching { LlamaNative.getNCtx() }.getOrDefault(-1)
                            val totalElapsed = System.currentTimeMillis() - firstTokenStart
                            val tps = if (totalElapsed > 0 && nativeTokenCount > 0)
                                nativeTokenCount * 1000f / totalElapsed else 0f
                            Log.i("AIRI_STREAM",
                                "n_past=$nPast n_ctx=$nCtx tokens=$nativeTokenCount " +
                                "tps=%.2f first_token_ms=$firstTokenMs " +
                                "elapsed_ms=$totalElapsed model=${model.name}".format(tps))

                            if (full.isNotBlank()) {
                                com.airi.assistant.domain.verification.VerificationTracker
                                    .recordCheck("GENERATION", true,
                                        "tokens=$nativeTokenCount tps=%.2f".format(tps))
                                Log.i("AIRI_PROOF",
                                    "GENERATION_SUCCESS tokens=$nativeTokenCount model=${model.name}")
                            } else {
                                com.airi.assistant.domain.verification.VerificationTracker
                                    .recordCheck("GENERATION", false, "empty_response")
                                Log.w("AIRI_PROOF", "GENERATION_EMPTY model=${model.name}")
                            }
                            refreshMetrics()
                            Log.i("AIRI_PROOF",
                                "STATE_COMPLETE tokens=$nativeTokenCount " +
                                "elapsed_ms=$totalElapsed " +
                                "first_token_ms=$firstTokenMs " +
                                "tps=%.2f".format(tps))
                            Log.i("AIRI_PROOF",
                                "GEN_END tokens=$nativeTokenCount elapsed_ms=$totalElapsed " +
                                "first_token_ms=$firstTokenMs " +
                                "tps=%.2f cancelled=${cancelRequested.get()}".format(tps))
                            withContext(Dispatchers.Main) {
                                try { onComplete(full) }
                                catch (t: Throwable) {
                                    Log.w(TAG, "onComplete threw: ${t.message}", t)
                                }
                            }
                        }
                        Log.i("AIRI_PROOF", "STATE_IDLE after=complete")
                    }
                }
            } catch (e: Throwable) {
                // ── STATE_ERROR / STATE_CANCELLED (exception) ─────────────────
                //
                // Any Kotlin exception routes here. There are two distinct cases:
                //
                //   CASE A — CANCEL EXCEPTION
                //     Thrown by:
                //       • reconcileSession — our new Kotlin-level cancel guard
                //         (RECONCILE_CANCELLED, msg contains "CANCELLED")
                //       • airi_append_text native — PREFILL_CANCELLED,
                //         rethrown by runAppendWithSafeHandler without fullReset
                //       • any other path that checks cancelRequested first
                //     The KV is unknown but cancelRequested was TRUE before
                //     fullReset cleared it. We must route to onComplete(partial),
                //     NOT onError, so the UI closes the stream cleanly (same as
                //     the status=-2 branch from generate).
                //     Note: check cancelRequested BEFORE fullReset because
                //     fullReset calls cancelRequested.set(false).
                //
                //   CASE B — HARD ERROR
                //     Thrown by all other paths (overflow, decode failure, OOM,
                //     etc.). Route to onError as before.
                //
                // The distinction is made from `cancelRequested.get()` (set by
                // cancelStream / watchdog) OR the exception message keyword
                // "CANCELLED" (belt-and-suspenders for any path that throws
                // before cancelRequested is set but after g_cancel_requested).
                val nativeStatus = runCatching { LlamaNative.nativeGetLastStatus() }
                    .getOrDefault(0)
                val exMsg           = e.message ?: ""
                val isCancelException = cancelRequested.get() ||
                                        nativeStatus == -2    ||
                                        exMsg.contains("CANCELLED")
                val logTag = if (isCancelException) "STATE_CANCELLED" else "STATE_ERROR"
                Log.i("AIRI_PROOF",
                    "$logTag origin=exception exc=${e.javaClass.simpleName} " +
                    "msg=$exMsg native_status=$nativeStatus " +
                    "is_cancel=$isCancelException emitted=$nativeTokenCount")
                Log.i("AIRI_PROOF",
                    "STATE_CLEANUP reason=${if (isCancelException) "cancel_exception" else "exception"} " +
                    "clearing: tokenBuffer(${tokenBuffer.length}B) " +
                    "response(${response.length}B) nativeTokenCount=$nativeTokenCount")
                val partialOnCancel = response.toString()   // preserve before clear
                tokenBuffer.clear()
                response.setLength(0)
                nativeTokenCount = 0
                fullReset("GEN_EXCEPTION:${e.javaClass.simpleName}")
                // For cancel exceptions, invalidate session explicitly (KV is at
                // an arbitrary position — same rationale as the status=-2 branch).
                if (isCancelException) {
                    invalidateSession()
                    runCatching { LlamaNative.nativeClearCancel() }
                        .onFailure { t ->
                            Log.w("AIRI_PROOF",
                                "CANCEL_EXCEPTION clear_cancel_fail=${t.message}")
                        }
                }
                Log.i("AIRI_PROOF", "STATE_IDLE after=${if (isCancelException) "cancel_exception" else "exception_reset"}")
                if (finished.compareAndSet(false, true)) {
                    withContext(Dispatchers.Main) {
                        if (isCancelException) {
                            // Surface partial response (may be empty if cancel fired
                            // during prefill before any token was generated).
                            try { onComplete(partialOnCancel) }
                            catch (t: Throwable) {
                                Log.w(TAG, "onComplete(cancel_exc) threw (swallowed): ${t.message}", t)
                            }
                        } else {
                            val msg = "$ERR_NATIVE ${e.javaClass.simpleName}: $exMsg"
                            try { onError(msg) }
                            catch (t: Throwable) {
                                Log.w(TAG, "onError threw (swallowed): ${t.message}", t)
                            }
                        }
                    }
                }
            }
            } // ← closes lifecycleLock.withLock { ... }
        }
    }

    /**
     * SPEC v2 — APPEND with status-driven recovery.
     *
     * Wraps [LlamaNative.appendUserTurn] so the JVM layer can react to the
     * native return codes:
     *
     *   -3 CONTEXT_OVERFLOW → fullReset + drop history + retry ONCE with
     *                         system + user only. Per spec: "Retry generation
     *                         after reset (max 1 retry per turn)."
     *   -1 ERROR             → fullReset + propagate the exception so the
     *                         outer catch logs ERR_NATIVE.
     *   -2 CANCELLED         → propagate the exception (the outer try/catch
     *                         logs the clean stop). fullReset is still safe
     *                         because the next turn will re-prime.
     *
     * The native side throws on all three error paths AND sets
     * [LlamaNative.nativeGetLastStatus] before throwing, so we can
     * disambiguate purely from the status code (more reliable than parsing
     * exception messages, which is what the legacy retry path did).
     */
    private fun runAppendWithSafeHandler(
        fragment: String,
        model: ModelInfo,
        systemPrompt: String
    ) {
        try {
            LlamaNative.appendUserTurn(fragment)
            // Defensive: if the native side ever returns without throwing
            // but with a non-zero status, treat it as a hard failure.
            val s = runCatching { LlamaNative.nativeGetLastStatus() }.getOrDefault(0)
            if (s != 0) {
                Log.w("AIRI_PROOF",
                    "APPEND_STATUS_NONZERO status=$s (treating as overflow if -3, error otherwise)")
                throw RuntimeException(
                    if (s == -3) "CONTEXT_OVERFLOW"
                    else "APPEND_NONZERO_STATUS=$s"
                )
            }
        } catch (e: Throwable) {
            val status = runCatching { LlamaNative.nativeGetLastStatus() }
                .getOrDefault(0)
            val msg = e.message ?: ""
            val isOverflow = status == -3 ||
                msg.contains("CONTEXT_OVERFLOW") ||
                msg.contains("KV_OVERFLOW")
            val isCancelled = status == -2 || msg.contains("CANCELLED")

            if (isCancelled) {
                Log.i("AIRI_PROOF", "APPEND_CANCELLED status=$status — propagating clean stop")
                throw e
            }

            if (!isOverflow) {
                Log.e("AIRI_PROOF",
                    "APPEND_ERROR status=$status exc=${e.javaClass.simpleName}: $msg → fullReset+stop")
                fullReset("APPEND_ERROR")
                throw e
            }

            // ─── SPEC v2 — single retry on overflow ─────────────────────────
            // Tear down the context, drop ALL history, re-prime with system +
            // current user only, retry exactly ONCE. If the second attempt
            // also fails we surface the original exception so the outer
            // handler can run its CLEANUP path.
            Log.w("AIRI_PROOF",
                "APPEND_OVERFLOW status=$status — fullReset+retry (1/1) " +
                "first_failure=${e.javaClass.simpleName}: $msg")
            fullReset("APPEND_OVERFLOW")
            chatHistory.clear()
            // Re-prime: beginSession + system block only.
            try {
                LlamaNative.beginSession()
                primedHistory.clear()
                val sys = systemBlock(model.type, systemPrompt)
                if (sys.isNotEmpty()) LlamaNative.appendAssistantTurn(sys)
                primedModelPath = model.path
                primedSystemPrompt = systemPrompt
                sessionPrimed = true
                LlamaNative.appendUserTurn(fragment)
                val s2 = runCatching { LlamaNative.nativeGetLastStatus() }.getOrDefault(0)
                if (s2 != 0) {
                    throw RuntimeException("APPEND_RETRY_STATUS=$s2")
                }
                Log.i("AIRI_PROOF",
                    "APPEND_OVERFLOW_RECOVERED via=fullReset+reprime " +
                    "kv=${runCatching { LlamaNative.getKvPosition() }.getOrDefault(-1)}/" +
                    "${runCatching { LlamaNative.getNCtx() }.getOrDefault(-1)}")
            } catch (e2: Throwable) {
                Log.e("AIRI_PROOF",
                    "APPEND_OVERFLOW_RETRY_FAILED exc=${e2.javaClass.simpleName}: ${e2.message}")
                fullReset("APPEND_OVERFLOW_RETRY_FAILED")
                throw e2
            }
        }
    }

    /**
     * Smart history trim: drops oldest *complete user/assistant pairs* until
     * the projected token usage (history + reserve) fits inside the available
     * KV budget. Never breaks a pair, never truncates mid-message, and always
     * preserves the most recent turn so the conversation stays coherent.
     *
     * The system prompt is NOT part of [messages] (it's prepended separately
     * during reconcileSession), so this never drops the system prompt.
     */
    fun trimContext(messages: List<ChatMessage>, maxApproxTokens: Int = 1500): List<ChatMessage> {
        // Pull the live n_ctx from native if we can — this lets the trim react
        // to runtime mode changes (FAST=1024, BALANCED=1536, QUALITY=2048).
        val nCtx = runCatching { LlamaNative.getNCtx() }.getOrDefault(0)
        // Reserve = system prompt headroom + max generation budget. We don't
        // know either exactly here, so pick a safe constant.
        val reserve = 512
        val budget = if (nCtx > reserve) (nCtx - reserve).coerceAtMost(maxApproxTokens)
                     else maxApproxTokens

        val recent = messages.takeLast(maxHistory)
        if (recent.isEmpty()) return emptyList()

        // Walk backwards in *pairs* so we never split a user without its reply.
        val kept = ArrayDeque<ChatMessage>()
        var approx = 0
        var i = recent.size - 1
        while (i >= 0) {
            // Try to consume the (user, assistant) pair ending at i.
            val tail = recent[i]
            val head = if (i - 1 >= 0) recent[i - 1] else null
            val pairCost = estimateTokens(tail.content) +
                           (head?.let { estimateTokens(it.content) } ?: 0)
            if (kept.isNotEmpty() && approx + pairCost > budget) break
            kept.addFirst(tail)
            if (head != null) kept.addFirst(head)
            approx += pairCost
            i -= if (head != null) 2 else 1
        }
        return kept.toList()
    }

    private fun trimHistory() {
        val trimmed = trimContext(chatHistory)
        if (trimmed.size != chatHistory.size) {
            // We dropped the oldest turns from our logical view. The KV still
            // contains them, but the next reconcile will detect divergence and
            // re-prime cleanly.
            chatHistory.clear()
            chatHistory.addAll(trimmed)
        }
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    /**
     * PHASE 1 helper: approximate token cost of replaying [history] under the
     * given chat template. Conservative — over-estimates rather than under,
     * so the pre-flight stays on the safe side of n_ctx.
     */
    private fun estimateHistoryTokens(history: List<ChatMessage>, type: ModelType): Int {
        var total = 0
        for (msg in history) {
            val rendered = when (msg.role) {
                "user"      -> userBody(type, msg.content)
                "assistant" -> assistantBody(type, msg.content)
                else        -> msg.content
            }
            total += estimateTokens(rendered)
        }
        return total
    }

    // ── Vision: load / unload mmproj projector on the serialized dispatcher ──
    //
    // CRITICAL: the projector touches the SAME native llama_model that
    // generateStream uses. It MUST therefore go through `scope` so we can
    // never race with an in-flight decode (which would corrupt the KV
    // cache and crash inside ggml). We do NOT cache the loaded path here —
    // ModelCapabilities re-detects via LlamaNative.isMmprojLoaded() so
    // there is exactly one source of truth.
    /**
     * PHASE 3: auto-discover and load an mmproj sidecar living next to the
     * just-loaded GGUF. Convention: any file in the same directory whose
     * name contains "mmproj" or "mm-proj" (case-insensitive) and ends in
     * .gguf is treated as the projector for the active model.
     *
     * Safe to call after [loadModel] succeeds. No-op if:
     *   • the active model is not on the VISION_TAGS list (don't waste
     *     RAM loading a projector for a text-only model), or
     *   • a projector is already loaded (idempotent).
     *
     * Always emits AIRI_PROOF MMPROJ_AUTOLOAD_* tags so the decision is
     * visible from logcat without enabling verbose logs.
     */
    fun maybeAutoLoadMmproj(modelPath: String) {
        if (!isLoaded) {
            Log.i("AIRI_PROOF", "MMPROJ_AUTOLOAD_SKIPPED reason=model_not_loaded")
            return
        }
        scope.launch {
            try {
                if (runCatching { LlamaNative.isMmprojLoaded() }.getOrDefault(false)) {
                    Log.i("AIRI_PROOF", "MMPROJ_AUTOLOAD_SKIPPED reason=already_loaded")
                    return@launch
                }
                val parent = File(modelPath).parentFile ?: run {
                    Log.i("AIRI_PROOF", "MMPROJ_AUTOLOAD_SKIPPED reason=no_parent_dir")
                    return@launch
                }
                val candidates = parent.listFiles { f ->
                    val n = f.name.lowercase()
                    f.isFile && n.endsWith(".gguf") &&
                        (n.contains("mmproj") || n.contains("mm-proj") || n.contains("projector"))
                }?.toList().orEmpty()
                if (candidates.isEmpty()) {
                    Log.i("AIRI_PROOF",
                        "MMPROJ_AUTOLOAD_SKIPPED reason=no_sidecar_in dir=${parent.absolutePath}")
                    return@launch
                }
                // Prefer f16 over q4 if multiple are present.
                val pick = candidates.sortedByDescending { f ->
                    val n = f.name.lowercase(); when {
                        "f16" in n  -> 3
                        "f32" in n  -> 2
                        "q8" in n   -> 1
                        else        -> 0
                    }
                }.first()
                Log.i("AIRI_PROOF",
                    "MMPROJ_AUTOLOAD_REQUESTED path=${pick.absolutePath} " +
                    "candidates=${candidates.size}")
                val ok = runCatching { LlamaNative.loadMmproj(pick.absolutePath) }
                    .getOrElse { e ->
                        Log.e(TAG, "MMPROJ_AUTOLOAD threw: ${e.message}", e); false
                    }
                Log.i("AIRI_PROOF", "MMPROJ_AUTOLOAD_RESULT ok=$ok")
            } catch (e: Throwable) {
                Log.w(TAG, "maybeAutoLoadMmproj failed: ${e.message}")
                Log.i("AIRI_PROOF", "MMPROJ_AUTOLOAD_FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * PHASE 5: auto-discover and load a dedicated embedding GGUF living in
     * the same directory as the active chat model (or any of its sub-dirs).
     * The embedding context is independent from the chat context — both can
     * be loaded simultaneously — so the Memory pipeline can produce real
     * pooled vectors without forcing the user to manually pick a second
     * model.
     *
     * Selection rules:
     *  • file is .gguf
     *  • lowercase name contains any of [ModelCapabilities.EMBEDDING_TAGS]
     *    (bge-, e5-, gte-, nomic-embed, all-minilm, snowflake-arctic-embed,
     *    mxbai-embed, jina-embed)
     *  • not the chat model itself
     *
     * Idempotent: re-checks LlamaNative.loadEmbeddingModel which itself
     * unloads any previously-loaded embedding model before loading the new
     * one (see native impl). We still skip if the same path was already
     * loaded successfully in this process.
     *
     * Emits AIRI_PROOF EMBEDDING_AUTOLOAD_* tags so the decision is
     * observable from logcat without verbose logging.
     */
    @Volatile private var loadedEmbeddingPath: String? = null

    /**
     * B-23: Load an embedding model from an explicit file path chosen by the user.
     * Returns true on success, false on failure.
     * Unlike [maybeAutoLoadEmbeddingModel] this accepts any GGUF path —
     * it does not require a chat model to be loaded first.
     */
    suspend fun loadEmbeddingFromPath(path: String): Boolean = withContext(llamaDispatcher) {
        if (path == loadedEmbeddingPath) {
            Log.i(TAG, "EMBEDDING_LOAD_SKIPPED reason=already_loaded path=$path")
            return@withContext true
        }
        return@withContext try {
            Log.i(TAG, "AIRI_PROOF EMBEDDING_LOAD_REQUESTED path=$path")
            val result = LlamaNative.loadEmbeddingModel(path)
            val ok = result == "LOAD_SUCCESS" || result == "Success"
            if (ok) loadedEmbeddingPath = path
            Log.i(TAG, "AIRI_PROOF EMBEDDING_LOAD_RESULT ok=$ok native=$result")
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "loadEmbeddingFromPath threw: ${e.message}", e)
            false
        }
    }

    fun maybeAutoLoadEmbeddingModel(modelPath: String) {
        if (!isLoaded) {
            Log.i("AIRI_PROOF", "EMBEDDING_AUTOLOAD_SKIPPED reason=model_not_loaded")
            return
        }
        scope.launch {
            try {
                val parent = File(modelPath).parentFile ?: run {
                    Log.i("AIRI_PROOF", "EMBEDDING_AUTOLOAD_SKIPPED reason=no_parent_dir")
                    return@launch
                }
                // Conservative tag list mirrored from ModelCapabilities so
                // both the auto-loader and the capability detector agree
                // about what counts as an "embedding model".
                val tags = listOf(
                    "bge-", "e5-", "gte-", "nomic-embed", "all-minilm",
                    "snowflake-arctic-embed", "mxbai-embed", "jina-embed"
                )
                val chatName = File(modelPath).name.lowercase()
                // Walk one level deep so a "models/embeddings/" sub-dir
                // also gets picked up — some users organise that way.
                val pool = (parent.listFiles().orEmpty().toList() +
                    parent.listFiles { f -> f.isDirectory }
                        .orEmpty().flatMap { it.listFiles().orEmpty().toList() })
                val candidates = pool.filter { f ->
                    val n = f.name.lowercase()
                    f.isFile && n.endsWith(".gguf") &&
                        n != chatName &&
                        tags.any { it in n }
                }
                if (candidates.isEmpty()) {
                    Log.i("AIRI_PROOF",
                        "EMBEDDING_AUTOLOAD_SKIPPED reason=no_candidate_in dir=${parent.absolutePath}")
                    return@launch
                }
                // Prefer higher-fidelity quantisations: f16 > f32 > q8 > q5 > q4.
                val pick = candidates.sortedByDescending { f ->
                    val n = f.name.lowercase(); when {
                        "f16" in n -> 5
                        "f32" in n -> 4
                        "q8"  in n -> 3
                        "q5"  in n -> 2
                        "q4"  in n -> 1
                        else       -> 0
                    }
                }.first()
                if (pick.absolutePath == loadedEmbeddingPath) {
                    Log.i("AIRI_PROOF",
                        "EMBEDDING_AUTOLOAD_SKIPPED reason=already_loaded path=${pick.absolutePath}")
                    return@launch
                }
                Log.i("AIRI_PROOF",
                    "EMBEDDING_AUTOLOAD_REQUESTED path=${pick.absolutePath} " +
                    "candidates=${candidates.size}")
                val result = runCatching { LlamaNative.loadEmbeddingModel(pick.absolutePath) }
                    .getOrElse { e ->
                        Log.e(TAG, "EMBEDDING_AUTOLOAD threw: ${e.message}", e)
                        "EXCEPTION:${e.javaClass.simpleName}"
                    }
                val ok = result == "LOAD_SUCCESS" || result == "Success"
                if (ok) loadedEmbeddingPath = pick.absolutePath
                Log.i("AIRI_PROOF", "EMBEDDING_AUTOLOAD_RESULT ok=$ok native_result=$result")
            } catch (e: Throwable) {
                Log.w(TAG, "maybeAutoLoadEmbeddingModel failed: ${e.message}")
                Log.i("AIRI_PROOF",
                    "EMBEDDING_AUTOLOAD_FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    suspend fun loadMmprojSerialized(mmprojPath: String): Boolean = withContext(llamaDispatcher) {
        Log.i("AIRI_PROOF", "MMPROJ_LOAD_REQUESTED path=$mmprojPath")
        val ok = runCatching { LlamaNative.loadMmproj(mmprojPath) }.getOrElse { e ->
            Log.e(TAG, "loadMmproj threw: ${e.message}", e)
            false
        }
        Log.i("AIRI_PROOF", "MMPROJ_LOAD_RESULT ok=$ok")
        ok
    }

    suspend fun unloadMmprojSerialized() = withContext(llamaDispatcher) {
        runCatching { LlamaNative.unloadMmproj() }
        Log.i("AIRI_PROOF", "MMPROJ_UNLOADED via=manager")
    }

    /**
     * Vision-only generation. The native side does NOT stream tokens for
     * `evalImageAndGenerate` — it returns the full reply string when done.
     * We therefore expose `onComplete`/`onError` only (no token callback)
     * and the UI shows an "Analyzing image…" stage hint while we wait.
     *
     * Serialization, watchdog, and AIRI_PROOF logging mirror generateStream
     * so on-device debugging is identical between the two pipelines.
     *
     * @param prompt      The user's text question about the image.
     * @param rgb888      Packed RGB byte array (width*height*3, row-major
     *                    top-down). Caller is responsible for downscale.
     * @param width       Image width in pixels (must match rgb888 length).
     * @param height      Image height in pixels (must match rgb888 length).
     * @param maxTokens   Hard cap on generated tokens. Recommended ≤ 256
     *                    for vision because vision prefill already eats
     *                    most of the latency budget.
     * @param timeoutMs   Wall-clock deadline for the whole call. Vision
     *                    prefill is much slower than text on CPU (typically
     *                    8-30s on mid-range), so the default 180s is
     *                    intentionally larger than the text path's 120s.
     */
    fun generateWithImage(
        prompt: String,
        rgb888: ByteArray,
        width: Int,
        height: Int,
        maxTokens: Int,
        timeoutMs: Long = 180_000L,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        // Defensive contract: refuse degenerate inputs at the boundary so
        // the native side never sees them.
        val expectedBytes = width * height * 3
        if (width <= 0 || height <= 0 || rgb888.size != expectedBytes) {
            val msg = "ERR_VISION_BAD_INPUT w=$width h=$height bytes=${rgb888.size} expected=$expectedBytes"
            Log.e(TAG, msg)
            onError(msg)
            return
        }
        if (maxTokens <= 0) {
            onError("ERR_VISION_BAD_INPUT maxTokens=$maxTokens")
            return
        }

        val finished = AtomicBoolean(false)

        // Wall-clock deadline (vision has no streaming → there is no
        // "first-token" milestone to gate inactivity on). If native call
        // runs past timeoutMs we still cancel and report ERR_VISION_TIMEOUT.
        // Note: the underlying llama_decode honors LlamaNative.cancel()
        // through cancelRequested — same path text generation uses.
        watchdogScope.launch {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!finished.get()) {
                delay(500L)
                if (System.currentTimeMillis() >= deadline) {
                    if (finished.compareAndSet(false, true)) {
                        Log.w(TAG, "generateWithImage timed out after ${timeoutMs}ms")
                        Log.i("AIRI_PROOF", "VISION_TIMEOUT timeout_ms=$timeoutMs")
                        cancelRequested.set(true)
                        runCatching { LlamaNative.cancel() }
                        // PHASE 6: contain user error callback.
                        withContext(Dispatchers.Main) {
                            try { onError("ERR_VISION_TIMEOUT timeout_ms=$timeoutMs") }
                            catch (t: Throwable) {
                                Log.w(TAG, "vision onError(timeout) threw: ${t.message}", t)
                            }
                        }
                    }
                    return@launch
                }
            }
        }

        scope.launch {
            cancelRequested.set(false)
            val start = System.currentTimeMillis()
            Log.i(
                "AIRI_PROOF",
                "VISION_GEN_START prompt_len=${prompt.length} w=$width h=$height " +
                    "bytes=${rgb888.size} max_tokens=$maxTokens"
            )
            try {
                val full = LlamaNative.evalImageAndGenerate(
                    prompt, rgb888, width, height, maxTokens
                )
                if (finished.compareAndSet(false, true)) {
                    val elapsed = System.currentTimeMillis() - start
                    val cancelled = cancelRequested.get()
                    Log.i(
                        "AIRI_PROOF",
                        "VISION_GEN_END elapsed_ms=$elapsed reply_len=${full.length} cancelled=$cancelled"
                    )
                    // Native side returns "" on internal failure; surface
                    // that as an explicit error so the UI doesn't silently
                    // show a blank assistant turn.
                    if (full.isBlank()) {
                        // PHASE 6: contain user error callback.
                        withContext(Dispatchers.Main) {
                            try { onError("ERR_VISION_EMPTY native returned blank reply") }
                            catch (t: Throwable) {
                                Log.w(TAG, "vision onError(empty) threw: ${t.message}", t)
                            }
                        }
                    } else {
                        // PHASE 6: contain user completion callback.
                        withContext(Dispatchers.Main) {
                            try { onComplete(full) }
                            catch (t: Throwable) {
                                Log.w(TAG, "vision onComplete threw: ${t.message}", t)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "generateWithImage native error: ${e.javaClass.simpleName}: ${e.message}", e)
                // Vision call probably left llama_context KV in a torn
                // state — force re-prime on the next text turn.
                invalidateSession()
                if (finished.compareAndSet(false, true)) {
                    val msg = "$ERR_NATIVE ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                    // PHASE 6: contain user error callback.
                    withContext(Dispatchers.Main) {
                        try { onError(msg) }
                        catch (t: Throwable) {
                            Log.w(TAG, "vision onError(native) threw: ${t.message}", t)
                        }
                    }
                }
            }
        }
    }

    private fun defaultSystemPrompt(): String = """
        أنت AIRI، المساعد الذكي المتطور بنظام Android.
        هويتك: ذكي، مرح، ومفيد جداً.
        قواعد الرد:
        1. أجب دائماً باللغة العربية (لهجة بيضاء مفهومة أو فصحى بسيطة).
        2. اجعل ردودك قصيرة ومباشرة (إلا إذا طلب المستخدم تفاصيل).
        3. إذا لم تعرف الإجابة، قل ذلك بصدق ولا تخترع معلومات.
        4. تذكر دائماً أنك جزء من مشروع AIRI المفتوح المصدر.
    """.trimIndent()
}
