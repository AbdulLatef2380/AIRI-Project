package com.airi.assistant.ai

import android.content.Context
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.repository.MemoryManager
import kotlinx.coroutines.*
import java.io.File

class LlamaManager(private val context: Context) {
    private var isLoaded = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val memoryManager = MemoryManager(context)
    private val chatHistory = mutableListOf<ChatMessage>()
    private val MAX_HISTORY = 10 

    fun loadModel(path: String, onReady: (Boolean) -> Unit) {
        val modelFile = File(path)
        if (!modelFile.exists()) {
            onReady(false)
            return
        }
        
        scope.launch {
            val result = LlamaNative.loadModel(modelFile.absolutePath)
            isLoaded = (result == "Success")
            
            if (isLoaded) {
                val lastMessages = memoryManager.getRecentMessages(MAX_HISTORY)
                chatHistory.clear()
                chatHistory.addAll(lastMessages.reversed()) 
            }

            withContext(Dispatchers.Main) { onReady(isLoaded) }
        }
    }

    fun generate(prompt: String, onResult: (String) -> Unit) {
        if (!isLoaded || ModelManager.getCurrent() == null) {
            onResult("المحرك غير مفعل أو لم يتم اختيار نموذج")
            return
        }

        val userMsg = ChatMessage(role = "user", content = prompt)
        chatHistory.add(userMsg)
        memoryManager.recordInteraction(userMsg.role, userMsg.content)

        scope.launch {
            val fullPrompt = buildChatPrompt()
            val response = LlamaNative.generateResponse(fullPrompt)
            
            val assistantMsg = ChatMessage(role = "assistant", content = response)
            chatHistory.add(assistantMsg)
            memoryManager.recordInteraction(assistantMsg.role, assistantMsg.content)

            if (chatHistory.size > MAX_HISTORY) {
                chatHistory.removeAt(0)
                chatHistory.removeAt(0)
            }

            withContext(Dispatchers.Main) { onResult(response) }
        }
    }

    /**
     * توليد الرد بشكل متدفق (Streaming)
     */
    fun generateStream(prompt: String, onToken: (String) -> Unit, onComplete: (String) -> Unit) {
        if (!isLoaded || ModelManager.getCurrent() == null) {
            onToken("المحرك غير مفعل")
            onComplete("")
            return
        }

        val userMsg = ChatMessage(role = "user", content = prompt)
        chatHistory.add(userMsg)
        memoryManager.recordInteraction(userMsg.role, userMsg.content)

        scope.launch {
            val fullPrompt = buildChatPrompt()
            val fullResponse = StringBuilder()
            
            LlamaNative.generateStream(fullPrompt) { token ->
                fullResponse.append(token)
                scope.launch(Dispatchers.Main) { onToken(token) }
            }
            
            val assistantMsg = ChatMessage(role = "assistant", content = fullResponse.toString())
            chatHistory.add(assistantMsg)
            memoryManager.recordInteraction(assistantMsg.role, assistantMsg.content)

            if (chatHistory.size > MAX_HISTORY) {
                chatHistory.removeAt(0)
                chatHistory.removeAt(0)
            }

            withContext(Dispatchers.Main) { onComplete(fullResponse.toString()) }
        }
    }

    private fun buildChatPrompt(): String {
        val sb = StringBuilder()
        
        sb.append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n")
        sb.append("""
            أنت AIRI، المساعد الذكي المتطور بنظام Android.
            هويتك: ذكي، مرح، ومفيد جداً.
            قواعد الرد:
            1. أجب دائماً باللغة العربية (لهجة بيضاء مفهومة أو فصحى بسيطة).
            2. اجعل ردودك قصيرة ومباشرة (إلا إذا طلب المستخدم تفاصيل).
            3. استخدم الرموز التعبيرية (Emojis) بشكل لطيف لتظهر شخصيتك الودودة.
            4. إذا لم تعرف الإجابة، قل ذلك بصدق ولا تخترع معلومات.
            5. تذكر دائماً أنك جزء من مشروع AIRI المفتوح المصدر.
        """.trimIndent())
        sb.append("<|eot_id|>\n")

        for (msg in chatHistory) {
            sb.append("<|start_header_id|>${msg.role}<|end_header_id|>\n")
            sb.append(msg.content)
            sb.append("<|eot_id|>\n")
        }
        
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n")
        return sb.toString()
    }
}
