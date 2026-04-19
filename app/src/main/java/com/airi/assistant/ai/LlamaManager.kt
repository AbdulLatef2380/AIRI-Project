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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val chatHistory = mutableListOf<ChatMessage>()
    private val maxHistory = 6

    companion object {
        private const val TAG = "LlamaManager"
        private const val TOKEN_BATCH_MS = 35L
    }

    fun loadModel(path: String, onProgress: (Int) -> Unit = {}, onReady: (Boolean) -> Unit) {
        val modelFile = File(path)
        if (!modelFile.exists()) {
            Log.e(TAG, "loadModel FAILED — file does not exist: $path")
            onReady(false)
            return
        }
        Log.i(TAG, "loadModel → file=${modelFile.name} size=${modelFile.length() / (1024 * 1024)}MB")

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
                Log.i(TAG, "loadModel SUCCESS — starting warmup")
                warmup()
                withContext(Dispatchers.Main) { onReady(true) }
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "loadModelWithProgress not available — falling back to loadModel(): ${e.message}")
                val result = runCatching { LlamaNative.loadModel(modelFile.absolutePath) }.getOrElse { ex ->
                    Log.e(TAG, "loadModel fallback also failed: ${ex.message}")
                    "Error"
                }
                isLoaded = (result == "Success")
                Log.i(TAG, "loadModel fallback result=$result isLoaded=$isLoaded")
                if (isLoaded) warmup()
                withContext(Dispatchers.Main) { onReady(isLoaded) }
            } catch (e: Exception) {
                Log.e(TAG, "loadModel EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
                isLoaded = false
                withContext(Dispatchers.Main) { onReady(false) }
            }
        }
    }

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
        timeoutMs: Long = 15_000L,
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

        chatHistory.add(ChatMessage(role = "user", content = prompt))
        trimHistory()
        val finished = AtomicBoolean(false)

        // Timeout watchdog
        scope.launch {
            delay(timeoutMs)
            if (finished.compareAndSet(false, true)) {
                Log.w(TAG, "generateStream timed out after ${timeoutMs / 1000}s")
                withContext(Dispatchers.Main) {
                    onError("Local model timed out after ${timeoutMs / 1000}s.")
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
                        fullResponse.append(token)
                        tokenBuffer.append(token)

                        val now = System.currentTimeMillis()
                        if (now - lastFlushTime >= TOKEN_BATCH_MS) {
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
            if (remaining.isNotEmpty() && !finished.get()) {
                scope.launch(Dispatchers.Main) { onToken(remaining) }
            }

            if (!finished.compareAndSet(false, true)) {
                return@launch
            }

            val response = fullResponse.toString()
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
        scope.launch {
            runCatching {
                LlamaNative.generateResponse("Hi")
                Log.d(TAG, "Warmup complete")
            }.onFailure { e ->
                Log.w(TAG, "Warmup failed (non-critical): ${e.message}")
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
