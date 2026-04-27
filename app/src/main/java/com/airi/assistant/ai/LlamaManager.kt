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
     */
    fun applyRuntimeMode(mode: PerformanceMode) {
        if (!isLoaded) {
            Log.d(TAG, "applyRuntimeMode skipped — model not loaded yet")
            return
        }
        scope.launch {
            try {
                LlamaNative.setRuntimeMode(mode.nCtx, mode.nThreads)
                invalidateSession()
                Log.i(TAG, "RUNTIME_MODE_APPLIED mode=${mode.name} n_ctx=${mode.nCtx} threads=${mode.nThreads}")
            } catch (e: Throwable) {
                Log.e(TAG, "applyRuntimeMode failed: ${e.message}", e)
            }
        }
    }

    /** Last-known callback for stall warnings (set per generateStream call). */
    @Volatile private var stallCallback: (() -> Unit)? = null

    fun cancelStream() {
        cancelRequested.set(true)
        runCatching { LlamaNative.cancel() }
        Log.d(TAG, "cancelStream requested")
        Log.i("AIRI_PROOF", "GEN_CANCEL_REQUESTED tid=${Thread.currentThread().id}")
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
            // Replay all historical turns as one big non-logits block.
            val replay = StringBuilder()
            for (msg in chatHistory) {
                when (msg.role) {
                    "user"      -> replay.append(userBody(modelType, msg.content))
                    "assistant" -> replay.append(assistantBody(modelType, msg.content))
                }
            }
            if (replay.isNotEmpty()) {
                LlamaNative.appendAssistantTurn(replay.toString())
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
            val replay = StringBuilder()
            for (msg in newTurns) {
                when (msg.role) {
                    "user"      -> replay.append(userBody(modelType, msg.content))
                    "assistant" -> replay.append(assistantBody(modelType, msg.content))
                }
            }
            LlamaNative.appendAssistantTurn(replay.toString())
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
        timeoutMs: Long = DEFAULT_FIRST_TOKEN_TIMEOUT_MS,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit = {},
        onStallWarning: () -> Unit = {}
    ) {
        val model = ModelManager.getCurrent()
        if (!isLoaded || model == null) {
            scope.launch(Dispatchers.Main) {
                onToken("المحرك غير مفعل")
                onComplete("")
            }
            return
        }

        cancelRequested.set(false)
        Log.d(TAG, "generateStream params: maxTokens=$maxTokens temp=$temperature " +
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
                        runCatching { LlamaNative.cancel() }
                        withContext(Dispatchers.Main) {
                            onError("$errCode idle_ms=$idle budget_ms=$budget")
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

            try {
                // Reconcile native KV with the requested system prompt + history.
                val reconcileStart = System.currentTimeMillis()
                val replayedTurns = reconcileSession(model.path, model.type, systemPrompt)
                val reconcileMs = System.currentTimeMillis() - reconcileStart
                if (replayedTurns > 0) {
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
                LlamaNative.appendUserTurn(userFragment)
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

                val tokenCallback: (String) -> Unit = tokenCallback@ { token ->
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
                    // Per-token trace — gated to AIRI_TOKEN tag.
                    // adb shell setprop log.tag.AIRI_TOKEN VERBOSE
                    Log.v("AIRI_TOKEN", "n=$nativeTokenCount bytes=${token.length}")

                    val isFirst = firstTokenLogged.compareAndSet(false, true)
                    if (isFirst) {
                        firstTokenMs = System.currentTimeMillis() - firstTokenStart
                        com.airi.assistant.domain.verification.VerificationTracker.recordCheck(
                            "FIRST_TOKEN", true, "streaming token emitted"
                        )
                        Log.i("AIRI_PROOF", "FIRST_TOKEN token_emitted=true model=${model.name} first_token_ms=$firstTokenMs")
                    }

                    val now = System.currentTimeMillis()
                    val shouldFlush = isFirst ||
                            (now - lastFlushTime >= TOKEN_BATCH_MS) ||
                            (tokenBuffer.length >= TOKEN_BATCH_CHARS)
                    if (shouldFlush) {
                        val batch = tokenBuffer.toString()
                        tokenBuffer.clear()
                        lastFlushTime = now
                        scope.launch(Dispatchers.Main) { onToken(batch) }
                    }
                }

                if (useSpec) {
                    LlamaNative.generateNextTokensSpeculative(
                        maxTokens, specMgr.getDraftDraftN(), tokenCallback
                    )
                } else {
                    LlamaNative.generateNextTokens(maxTokens, tokenCallback)
                }

                // Flush any trailing buffered tokens.
                val tail = tokenBuffer.toString()
                if (tail.isNotEmpty() && !cancelRequested.get()) {
                    scope.launch(Dispatchers.Main) { onToken(tail) }
                }

                // Close the assistant turn in KV so the next user turn aligns.
                runCatching { LlamaNative.appendAssistantTurn(assistantCloseTag(model.type)) }

                if (finished.compareAndSet(false, true)) {
                    val full = response.toString()

                    // Update primed history with both the new user turn AND the
                    // generated assistant turn, so future calls don't replay them.
                    primedHistory.add(ChatMessage(role = "user",      content = prompt))
                    primedHistory.add(ChatMessage(role = "assistant", content = full))
                    chatHistory.add(ChatMessage(role = "user",      content = prompt))
                    chatHistory.add(ChatMessage(role = "assistant", content = full))
                    trimHistory()
                    // Bug B fix part 2: keep primedHistory size aligned with
                    // chatHistory after a trim. If chatHistory just dropped
                    // its oldest pair, primedHistory is now LONGER than
                    // chatHistory and the next reconcile will hard-reset
                    // (which is correct, but invisible to ops). Surface it.
                    if (primedHistory.size > chatHistory.size) {
                        Log.i("AIRI_PROOF",
                            "PRIMED_DRIFT primed=${primedHistory.size} chat=${chatHistory.size} " +
                            "next_turn_will_hard_reset=true")
                    }

                    // ── Hard logging line per spec ───────────────────────────
                    val nPast = runCatching { LlamaNative.getKvPosition() }.getOrDefault(-1)
                    val nCtx  = runCatching { LlamaNative.getNCtx() }.getOrDefault(-1)
                    val totalElapsed = System.currentTimeMillis() - firstTokenStart
                    val tps = if (totalElapsed > 0 && nativeTokenCount > 0)
                        nativeTokenCount * 1000f / totalElapsed else 0f
                    Log.i("AIRI_STREAM",
                        "n_past=$nPast n_ctx=$nCtx tokens=$nativeTokenCount tps=%.2f first_token_ms=$firstTokenMs elapsed_ms=$totalElapsed model=${model.name}"
                            .format(tps))

                    if (full.isNotBlank()) {
                        com.airi.assistant.domain.verification.VerificationTracker.recordCheck(
                            "GENERATION", true, "tokens=$nativeTokenCount tps=%.2f".format(tps))
                        Log.i("AIRI_PROOF", "GENERATION_SUCCESS tokens=$nativeTokenCount model=${model.name}")
                    } else {
                        com.airi.assistant.domain.verification.VerificationTracker.recordCheck(
                            "GENERATION", false, "empty_response")
                        Log.w("AIRI_PROOF", "GENERATION_EMPTY model=${model.name}")
                    }
                    // Snapshot the full latency breakdown from the native side
                    // so the Generation Statistics screen can render it.
                    refreshMetrics()
                    Log.i("AIRI_PROOF",
                        "GEN_END tokens=$nativeTokenCount elapsed_ms=$totalElapsed " +
                        "first_token_ms=$firstTokenMs tps=%.2f cancelled=${cancelRequested.get()}".format(tps))
                    withContext(Dispatchers.Main) { onComplete(full) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "generateStream native error: ${e.javaClass.simpleName}: ${e.message}", e)
                // Native error → KV state is unknown; force re-prime next time.
                invalidateSession()
                if (finished.compareAndSet(false, true)) {
                    val msg = "$ERR_NATIVE ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                    withContext(Dispatchers.Main) { onError(msg) }
                }
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

    // ── Vision: load / unload mmproj projector on the serialized dispatcher ──
    //
    // CRITICAL: the projector touches the SAME native llama_model that
    // generateStream uses. It MUST therefore go through `scope` so we can
    // never race with an in-flight decode (which would corrupt the KV
    // cache and crash inside ggml). We do NOT cache the loaded path here —
    // ModelCapabilities re-detects via LlamaNative.isMmprojLoaded() so
    // there is exactly one source of truth.
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
                        withContext(Dispatchers.Main) {
                            onError("ERR_VISION_TIMEOUT timeout_ms=$timeoutMs")
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
                        withContext(Dispatchers.Main) {
                            onError("ERR_VISION_EMPTY native returned blank reply")
                        }
                    } else {
                        withContext(Dispatchers.Main) { onComplete(full) }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "generateWithImage native error: ${e.javaClass.simpleName}: ${e.message}", e)
                // Vision call probably left llama_context KV in a torn
                // state — force re-prime on the next text turn.
                invalidateSession()
                if (finished.compareAndSet(false, true)) {
                    val msg = "$ERR_NATIVE ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                    withContext(Dispatchers.Main) { onError(msg) }
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
