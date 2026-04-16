package com.airi.assistant.ai

import android.content.Context
import com.airi.assistant.memory.entity.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LlamaManager(private val context: Context) {
    private var isLoaded = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val chatHistory = mutableListOf<ChatMessage>()
    private val maxHistory = 12

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
                withContext(Dispatchers.Main) { onReady(true) }
            } catch (e: UnsatisfiedLinkError) {
                val result = runCatching { LlamaNative.loadModel(modelFile.absolutePath) }.getOrElse { "Error" }
                isLoaded = (result == "Success")
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
        chatHistory.addAll(messages.takeLast(maxHistory))
    }

    fun generate(prompt: String, systemPrompt: String = defaultSystemPrompt(), onResult: (String) -> Unit) {
        if (!isLoaded || ModelManager.getCurrent() == null) {
            onResult("Select and activate a local model before sending.")
            return
        }

        chatHistory.add(ChatMessage(role = "user", content = prompt))
        scope.launch {
            val response = LlamaNative.generateResponse(buildChatPrompt(systemPrompt))
            chatHistory.add(ChatMessage(role = "assistant", content = response))
            trimHistory()
            withContext(Dispatchers.Main) { onResult(response) }
        }
    }

    fun generateStream(
        prompt: String,
        systemPrompt: String = defaultSystemPrompt(),
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        if (!isLoaded || ModelManager.getCurrent() == null) {
            onToken("المحرك غير مفعل")
            onComplete("")
            return
        }

        chatHistory.add(ChatMessage(role = "user", content = prompt))
        scope.launch {
            val fullResponse = StringBuilder()
            LlamaNative.generateStream(buildChatPrompt(systemPrompt)) { token ->
                fullResponse.append(token)
                scope.launch(Dispatchers.Main) { onToken(token) }
            }
            val response = fullResponse.toString()
            chatHistory.add(ChatMessage(role = "assistant", content = response))
            trimHistory()
            withContext(Dispatchers.Main) { onComplete(response) }
        }
    }

    private fun trimHistory() {
        while (chatHistory.size > maxHistory) {
            chatHistory.removeAt(0)
        }
    }

    private fun buildChatPrompt(systemPrompt: String): String {
        val model = ModelManager.getCurrent()
        return when (model?.type) {
            ModelType.GEMMA -> buildGemmaPrompt(systemPrompt)
            ModelType.MISTRAL -> buildMistralPrompt(systemPrompt)
            else -> buildQwenPrompt(systemPrompt)
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
