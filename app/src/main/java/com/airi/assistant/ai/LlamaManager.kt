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

class LlamaManager(private val context: Context) {
    private var isLoaded = false
    private var lastLoadFailure: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val chatHistory = mutableListOf<ChatMessage>()
    private val maxHistory = 6

    private val cancelRequested = AtomicBoolean(false)

    companion object {
        private const val TAG = "AIRI_MODEL"
        private const val TOKEN_BATCH_MS = 50L
        private const val TOKEN_BATCH_CHARS = 20
        // Maximum idle time AFTER first token before we consider the stream dead.
        // First-token deadline is the caller-supplied timeoutMs (default 90 s).
        private const val INACTIVITY_TIMEOUT_MS = 20_000L
    }

    fun cancelStream() {
        cancelRequested.set(true)
        Log.d(TAG, "cancelStream requested")
    }

    fun loadModel(path: String, onProgress: (Int) -> Unit = {}, onReady: (Boolean) -> Unit) {
        val modelFile = File(path)
        lastLoadFailure = null
        if (!modelFile.exists()) {
            lastLoadFailure = "file does not exist: $path"
            Log.e(TAG, "LOAD FAILED: $lastLoadFailure")
            onReady(false)
            return
        }
        if (!modelFile.canRead()) {
            lastLoadFailure = "file is not readable by app/native layer: $path"
            Log.e(TAG, "LOAD FAILED: $lastLoadFailure")
            onReady(false)
            return
        }
        if (!LlamaNative.isAvailable()) {
            lastLoadFailure = "native backend unavailable: ${LlamaNative.loadFailureMessage() ?: "airi_native could not be loaded"}"
            Log.e(TAG, "LOAD FAILED: $lastLoadFailure")
            onReady(false)
            return
        }
        val inspection = ModelValidator.inspect(modelFile)
        Log.i(TAG, "LOAD START path=${modelFile.absolutePath} file=${modelFile.name} size=${modelFile.length() / (1024 * 1024)}MB ggufVersion=${inspection.ggufVersion} architecture=${inspection.architecture}")

        scope.launch {
            isLoaded = false
            unloadModel()
            try {
                Log.d(TAG, "Calling LlamaNative.loadModelWithProgress …")
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
                warmup()
                withContext(Dispatchers.Main) { onReady(true) }
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "loadModelWithProgress not available — falling back to loadModel(): ${e.message}", e)
                val result = runCatching { LlamaNative.loadModel(modelFile.absolutePath) }.getOrElse { ex ->
                    lastLoadFailure = "${ex.javaClass.simpleName}: ${ex.message}"
                    Log.e(TAG, "LOAD FAILED: $lastLoadFailure", ex)
                    "Error"
                }
                isLoaded = (result == "Success")
                if (isLoaded) {
                    Log.i(TAG, "LOAD SUCCESS path=${modelFile.absolutePath}")
                    com.airi.assistant.domain.verification.VerificationTracker.recordCheck("MODEL_LOAD", true, "path=${modelFile.absolutePath}")
                } else {
                    lastLoadFailure = "native loader returned $result for architecture=${inspection.architecture} ggufVersion=${inspection.ggufVersion}"
                    Log.e(TAG, "LOAD FAILED: $lastLoadFailure")
                    com.airi.assistant.domain.verification.VerificationTracker.recordCheck("MODEL_LOAD", false, lastLoadFailure ?: "unknown")
                }
                if (isLoaded) warmup()
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
        Log.d(TAG, "Model unloaded")
    }

    fun setHistory(messages: List<ChatMessage>) {
        chatHistory.clear()
        chatHistory.addAll(trimContext(messages))
    }

    fun generate(
        prompt: String,
        systemPrompt: String = defaultSystemPrompt(),
        maxTokens: Int = PerformanceMode.BALANCED.maxTokens,
        temperature: Float = PerformanceMode.BALANCED.temperature,
        onResult: (String) -> Unit
    ) {
        if (!isLoaded || ModelManager.getCurrent() == null) {
            onResult("Select and activate a local model before sending.")
            return
        }

        chatHistory.add(ChatMessage(role = "user", content = prompt))
        scope.launch {
            val response = LlamaNative.generateResponse(buildChatPrompt(systemPrompt, maxTokens, temperature))
            chatHistory.add(ChatMessage(role = "assistant", content = response))
            trimHistory()
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
        timeoutMs: Long = 90_000L,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (!isLoaded || ModelManager.getCurrent() == null) {
            scope.launch(Dispatchers.Main) {
                onToken("المحرك غير مفعل")
                onComplete("")
            }
            return
        }

        cancelRequested.set(false)
        chatHistory.add(ChatMessage(role = "user", content = prompt))
        trimHistory()
        Log.d(TAG, "generateStream params: maxTokens=$maxTokens temp=$temperature repeatPenalty=$repeatPenalty " +
                "topK=$topK topP=$topP minP=$minP presence=$presencePenalty frequency=$frequencyPenalty " +
                "timeout=${timeoutMs}ms (prompt-decode budget) prompt_len=${prompt.length}")
        val finished = AtomicBoolean(false)
        val firstTokenLogged = AtomicBoolean(false)
        // Inactivity watchdog: resets every time a token arrives.
        // - First-token budget = full timeoutMs (covers slow CPU prompt-decode).
        // - After first token, idle ≥ INACTIVITY_TIMEOUT_MS without any new token = abort.
        val lastTokenAtMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        // Timeout watchdog — inactivity-based, not total deadline.
        scope.launch {
            val inactivityWindowMs = INACTIVITY_TIMEOUT_MS
            while (!finished.get()) {
                delay(500L)
                val now = System.currentTimeMillis()
                val idleMs = now - lastTokenAtMs.get()
                val firstTokenSeen = firstTokenLogged.get()
                val budget = if (firstTokenSeen) inactivityWindowMs else timeoutMs
                if (idleMs >= budget) {
                    if (finished.compareAndSet(false, true)) {
                        val phase = if (firstTokenSeen) "post-first-token idle" else "no-first-token (prompt decode)"
                        Log.w(TAG, "generateStream timed out: $phase idle=${idleMs}ms budget=${budget}ms")
                        Log.i("AIRI_PROOF", "TIMEOUT phase=${if (firstTokenSeen) "INACTIVITY" else "FIRST_TOKEN"} idle_ms=$idleMs budget_ms=$budget")
                        cancelRequested.set(true)
                        runCatching { LlamaNative.cancel() }
                        withContext(Dispatchers.Main) {
                            onError("Local model timed out ($phase, ${idleMs / 1000}s).")
                        }
                    }
                    return@launch
                }
            }
        }

        scope.launch {
            val fullResponse = StringBuilder()
            // Token batch accumulator — flush every TOKEN_BATCH_MS
            val tokenBuffer = StringBuilder()
            var lastFlushTime = System.currentTimeMillis()

            runCatching {
                LlamaNative.generateStream(buildChatPrompt(systemPrompt, maxTokens, temperature, repeatPenalty, topK, topP, minP, presencePenalty, frequencyPenalty)) { token ->
                    if (!finished.get()) {
                        if (cancelRequested.get()) {
                            if (finished.compareAndSet(false, true)) {
                                val partial = fullResponse.toString()
                                chatHistory.add(ChatMessage(role = "assistant", content = partial))
                                trimHistory()
                                scope.launch(Dispatchers.Main) { onComplete(partial) }
                            }
                            return@generateStream
                        }
                        fullResponse.append(token)
                        tokenBuffer.append(token)
                        // Reset inactivity watchdog on every native token.
                        lastTokenAtMs.set(System.currentTimeMillis())
                        if (firstTokenLogged.compareAndSet(false, true)) {
                            com.airi.assistant.domain.verification.VerificationTracker.recordCheck("FIRST_TOKEN", true, "streaming token emitted")
                            Log.i("AIRI_PROOF", "FIRST_TOKEN token_emitted=true model=${ModelManager.getCurrent()?.name ?: "unknown"}")
                        }

                        val now = System.currentTimeMillis()
                        val shouldFlushByTime = now - lastFlushTime >= TOKEN_BATCH_MS
                        val shouldFlushBySize = tokenBuffer.length >= TOKEN_BATCH_CHARS
                        if (shouldFlushByTime || shouldFlushBySize) {
                            val batch = tokenBuffer.toString()
                            tokenBuffer.clear()
                            lastFlushTime = now
                            scope.launch(Dispatchers.Main) { onToken(batch) }
                        }
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "generateStream native error: ${e.javaClass.simpleName}: ${e.message}", e)
                if (finished.compareAndSet(false, true)) {
                    scope.launch(Dispatchers.Main) { onError(e.message ?: "Local generation failed.") }
                }
                return@launch
            }

            // Flush any remaining buffered tokens
            val remaining = tokenBuffer.toString()
            if (remaining.isNotEmpty() && !finished.get() && !cancelRequested.get()) {
                scope.launch(Dispatchers.Main) { onToken(remaining) }
            }

            if (!finished.compareAndSet(false, true)) {
                return@launch
            }

            val response = fullResponse.toString()
            if (response.isNotBlank()) {
                val approxTokens = response.length / 4 + 1
                com.airi.assistant.domain.verification.VerificationTracker.recordCheck("GENERATION", true, "tokensApprox=$approxTokens")
                Log.i("AIRI_PROOF", "GENERATION_SUCCESS tokens_approx=$approxTokens model=${ModelManager.getCurrent()?.name ?: "unknown"}")
            } else {
                com.airi.assistant.domain.verification.VerificationTracker.recordCheck("GENERATION", false, "empty_response")
                Log.w("AIRI_PROOF", "GENERATION_EMPTY model=${ModelManager.getCurrent()?.name ?: "unknown"}")
            }
            chatHistory.add(ChatMessage(role = "assistant", content = response))
            trimHistory()
            withContext(Dispatchers.Main) { onComplete(response) }
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
        chatHistory.clear()
        chatHistory.addAll(trimmed)
    }

    private fun buildChatPrompt(
        systemPrompt: String,
        maxTokens: Int,
        temperature: Float,
        repeatPenalty: Float = 1.1f,
        topK: Int = 40,
        topP: Float = 0.9f,
        minP: Float = 0.05f,
        presencePenalty: Float = 0.0f,
        frequencyPenalty: Float = 0.0f
    ): String {
        val tunedPrompt = buildString {
            append(systemPrompt.ifBlank { defaultSystemPrompt() })
            append("\nSampling: max_tokens=$maxTokens temperature=$temperature repeat_penalty=$repeatPenalty top_k=$topK top_p=$topP min_p=$minP")
            if (presencePenalty != 0.0f)  append(" presence_penalty=$presencePenalty")
            if (frequencyPenalty != 0.0f) append(" frequency_penalty=$frequencyPenalty")
        }
        val model = ModelManager.getCurrent()
        return when (model?.type) {
            ModelType.GEMMA   -> buildGemmaPrompt(tunedPrompt)
            ModelType.MISTRAL -> buildMistralPrompt(tunedPrompt)
            ModelType.LLAMA   -> buildLlamaPrompt(tunedPrompt)
            else              -> buildQwenChatMLPrompt(tunedPrompt)
        }
    }

    /**
     * ChatML format — correct for Qwen 2.5 and most modern instruction-tuned models.
     * Qwen models specifically use <|im_start|> / <|im_end|> control tokens.
     * Using the wrong format (e.g. LLaMA-3 tokens) causes garbage outputs.
     */
    private fun buildQwenChatMLPrompt(systemPrompt: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append(systemPrompt.ifBlank { defaultSystemPrompt() })
        sb.append("\n<|im_end|>\n")

        for (msg in chatHistory.takeLast(maxHistory)) {
            when (msg.role) {
                "user" -> {
                    sb.append("<|im_start|>user\n")
                    sb.append(msg.content)
                    sb.append("\n<|im_end|>\n")
                }
                "assistant" -> {
                    sb.append("<|im_start|>assistant\n")
                    sb.append(msg.content)
                    sb.append("\n<|im_end|>\n")
                }
            }
        }

        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /**
     * LLaMA-3 / Meta format — <|begin_of_text|> + header tokens.
     */
    private fun buildLlamaPrompt(systemPrompt: String): String {
        val sb = StringBuilder()
        sb.append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n")
        sb.append(systemPrompt.ifBlank { defaultSystemPrompt() })
        sb.append("<|eot_id|>\n")

        for (msg in chatHistory.takeLast(maxHistory)) {
            sb.append("<|start_header_id|>${msg.role}<|end_header_id|>\n")
            sb.append(msg.content)
            sb.append("<|eot_id|>\n")
        }

        sb.append("<|start_header_id|>assistant<|end_header_id|>\n")
        return sb.toString()
    }

    private fun buildGemmaPrompt(systemPrompt: String): String {
        val sb = StringBuilder()
        val effectiveSystemPrompt = systemPrompt.ifBlank { defaultSystemPrompt() }
        if (effectiveSystemPrompt.isNotBlank()) {
            sb.append("<start_of_turn>user\n")
            sb.append(effectiveSystemPrompt)
            sb.append("<end_of_turn>\n")
        }
        for (msg in chatHistory.takeLast(adjustContextForGemma())) {
            when (msg.role) {
                "user" -> {
                    sb.append("<start_of_turn>user\n")
                    sb.append(msg.content)
                    sb.append("<end_of_turn>\n")
                }
                "assistant" -> {
                    sb.append("<start_of_turn>model\n")
                    sb.append(msg.content)
                    sb.append("<end_of_turn>\n")
                }
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun buildMistralPrompt(systemPrompt: String): String {
        val sb = StringBuilder()
        val history = chatHistory.takeLast(maxHistory)
        val effectiveSystemPrompt = systemPrompt.ifBlank { defaultSystemPrompt() }
        var pendingUser: String? = null

        for ((index, msg) in history.withIndex()) {
            when (msg.role) {
                "user" -> {
                    pendingUser = if (index == 0 && effectiveSystemPrompt.isNotBlank()) {
                        "<<SYS>>\n$effectiveSystemPrompt\n<</SYS>>\n\n${msg.content}"
                    } else {
                        msg.content
                    }
                }
                "assistant" -> {
                    pendingUser?.let { userText ->
                        sb.append("[INST] ")
                        sb.append(userText)
                        sb.append(" [/INST] ")
                        sb.append(msg.content)
                        sb.append("</s>")
                        pendingUser = null
                    }
                }
            }
        }

        val finalUser = pendingUser ?: if (effectiveSystemPrompt.isNotBlank() && history.none { it.role == "user" }) {
            "<<SYS>>\n$effectiveSystemPrompt\n<</SYS>>"
        } else {
            ""
        }
        sb.append("[INST] ")
        sb.append(finalUser)
        sb.append(" [/INST]")
        return sb.toString()
    }

    private fun adjustContextForGemma(): Int = minOf(maxHistory, 8)

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun warmup() {
        // Warmup intentionally disabled.
        //
        // The previous implementation called `LlamaNative.generateResponse("Hi")`
        // immediately after model load. With the new strict-UTF-8 / KV-checked
        // bridge this still works, but it consumed several seconds of CPU on
        // the user's first interaction window and — more importantly — could
        // crash the load callback if the model file was corrupt in a way
        // metadata-validation missed.
        //
        // First-token latency is now measured directly from the user's first
        // real prompt (see AIRI_PROOF FIRST_TOKEN). A no-op here is safer.
        Log.d(TAG, "Warmup skipped (first-token latency now measured on first user prompt)")
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
