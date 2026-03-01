package com.airi.assistant

import android.content.Context
import kotlinx.coroutines.*

class LlamaManager(private val context: Context) {
    private var isLoaded = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val memoryManager = MemoryManager(context)
    private val chatHistory = mutableListOf<ChatMessage>()
    private val MAX_HISTORY = 10 

    fun initializeModel(onReady: (Boolean) -> Unit) {
        val modelFile = ModelDownloadManager(context).getModelFile()
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
        if (!isLoaded) {
            onResult("المحرك غير مفعل")
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

    private fun buildChatPrompt(): String {
        val sb = StringBuilder()
        
        // --- بداية التوجيه الاحترافي (System Prompt) ---
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
        // --- نهاية التوجيه ---

        for (msg in chatHistory) {
            sb.append("<|start_header_id|>${msg.role}<|end_header_id|>\n")
            sb.append(msg.content)
            sb.append("<|eot_id|>\n")
        }
        
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n")
        return sb.toString()
    }
}
