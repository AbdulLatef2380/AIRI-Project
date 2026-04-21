package com.airi.assistant.ai

import android.content.Context
import android.util.Log
import com.airi.assistant.memory.entity.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Canonical prior history supplied by the caller (excludes the current turn). */
    private val chatHistory = mutableListOf<ChatMessage>()
    private val maxHistory = 6

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
        private const val TOKEN_BATCH_MS = 50L
        private const val TOKEN_BATCH_CHARS = 20
        private const val INACTIVITY_TIMEOUT_MS = 20_000L
        private const val DEFAULT_FIRST_TOKEN_TIMEOUT_MS = 60_000L

        const val ERR_FIRST_TOKEN_TIMEOUT = "ERR_FIRST_TOKEN_TIMEOUT"
        const val ERR_INACTIVITY_TIMEOUT  = "ERR_INACTIVITY_TIMEOUT"
        const val ERR_NATIVE              = "ERR_NATIVE"
    }

    fun cancelStream() {
        cancelRequested.set(true)
        runCatching { LlamaNative.cancel() }
        Log.d(TAG, "cancelStream requested")
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

    fun unloadModel() {
        isLoaded = false
        chatHistory.clear()
        invalidateSession()
        Log.d(TAG, "Model unloaded")
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
        onError: (String) -> Unit = {}
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
        scope.launch {
            while (!finished.get()) {
                delay(500L)
                val now = System.currentTimeMillis()
                val idle = now - lastTokenAtMs.get()
                val firstSeen = firstTokenLogged.get()
                val budget = if (firstSeen) INACTIVITY_TIMEOUT_MS else timeoutMs
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

            try {
                // Reconcile native KV with the requested system prompt + history.
                val replayedTurns = reconcileSession(model.path, model.type, systemPrompt)
                if (replayedTurns > 0) {
                    Log.d("AIRI_STREAM", "session_reconcile replayed_turns=$replayedTurns")
                }

                // Append the new user turn (logits=true on its last token).
                val isFirstUserOfSession = primedHistory.none { it.role == "user" }
                val userFragment = newUserTurnFragment(
                    type = model.type,
                    userText = prompt,
                    systemPrompt = systemPrompt,
                    embedSystem = isFirstUserOfSession
                )
                LlamaNative.appendUserTurn(userFragment)

                // Sample until EOG / max_tokens / cancel.
                LlamaNative.generateNextTokens(maxTokens) { token ->
                    if (finished.get() || cancelRequested.get()) return@generateNextTokens
                    response.append(token)
                    tokenBuffer.append(token)
                    nativeTokenCount++
                    lastTokenAtMs.set(System.currentTimeMillis())

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

    fun trimContext(messages: List<ChatMessage>, maxApproxTokens: Int = 1500): List<ChatMessage> {
        val recent = messages.takeLast(maxHistory)
        val trimmed = ArrayDeque<ChatMessage>()
        var approxTokens = 0
        for (msg in recent.asReversed()) {
            val count = estimateTokens(msg.content)
            if (trimmed.isNotEmpty() && approxTokens + count > maxApproxTokens) break
            trimmed.addFirst(msg)
            approxTokens += count
        }
        return trimmed.toList()
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
