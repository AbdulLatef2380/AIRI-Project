package com.airi.assistant.ai

import android.content.Context
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

    fun loadModel(path: String, onProgress: (Int) -> Unit = {}, onReady: (Boolean) -> Unit) {
        val modelFile = File(path)
        if (!modelFile.exists()) {
            onReady(false)
            return
        }

        scope.launch {
            isLoaded = false
            unloadModel()
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
                warmup()
                withContext(Dispatchers.Main) { onReady(true) }
            } catch (e: UnsatisfiedLinkError) {
                val result = runCatching { LlamaNative.loadModel(modelFile.absolutePath) }.getOrElse { "Error" }
                isLoaded = (result == "Success")
                if (isLoaded) warmup()
                withContext(Dispatchers.Main) { onReady(isLoaded) }
            } catch (e: Exception) {
                isLoaded = false
                withContext(Dispatchers.Main) { onReady(false) }
            }
        }
    }

    fun unloadModel() {
        isLoaded = false
        chatHistory.clear()
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
        timeoutMs: Long = 15_000L,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (!isLoaded || ModelManager.getCurrent() == null) {
            onToken("المحرك غير مفعل")
            onComplete("")
            return
        }

        chatHistory.add(ChatMessage(role = "user", content = prompt))
        trimHistory()
        val finished = AtomicBoolean(false)
        val firstTokenDelivered = AtomicBoolean(false)
        scope.launch {
            delay(timeoutMs)
            if (finished.compareAndSet(false, true)) {
                withContext(Dispatchers.Main) {
                    onError("Local model timed out after ${timeoutMs / 1000}s.")
                }
            }
        }
        scope.launch {
            val fullResponse = StringBuilder()
            runCatching {
                LlamaNative.generateStream(buildChatPrompt(systemPrompt, maxTokens, temperature)) { token ->
                    if (!finished.get()) {
                        fullResponse.append(token)
                        scope.launch(Dispatchers.Main) {
                            if (firstTokenDelivered.compareAndSet(false, true)) {
                                delay(0)
                            }
                            onToken(token)
                            delay(0)
                        }
                    }
                }
            }.onFailure { e ->
                if (finished.compareAndSet(false, true)) {
                    scope.launch(Dispatchers.Main) { onError(e.message ?: "Local generation failed.") }
                }
                return@launch
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

    private fun buildChatPrompt(systemPrompt: String, maxTokens: Int, temperature: Float): String {
        val tunedPrompt = buildString {
            append(systemPrompt.ifBlank { defaultSystemPrompt() })
            append("\nGeneration limits: answer in no more than $maxTokens tokens. Temperature target: $temperature.")
        }
        val model = ModelManager.getCurrent()
        return when (model?.type) {
            ModelType.GEMMA -> buildGemmaPrompt(tunedPrompt)
            ModelType.MISTRAL -> buildMistralPrompt(tunedPrompt)
            else -> buildQwenPrompt(tunedPrompt)
        }
    }

    private fun buildQwenPrompt(systemPrompt: String): String {
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

    private fun adjustContextForGemma(): Int {
        return minOf(maxHistory, 8)
    }

    private fun estimateTokens(text: String): Int =
        (text.length / 4).coerceAtLeast(1)

    private fun warmup() {
        scope.launch {
            runCatching {
                LlamaNative.generateResponse("AIRI warmup. Reply with one short token.")
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
