package com.airi.assistant

import android.content.Context
import kotlinx.coroutines.*

class LlamaManager(private val context: Context) {
    private var isLoaded = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // ربط مدير الذاكرة الدائمة
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
            // 1. تحميل الموديل
            val result = LlamaNative.loadModel(modelFile.absolutePath)
            isLoaded = (result == "Success")
            
            // 2. إذا نجح التحميل، استرجع آخر المحادثات من قاعدة البيانات للذاكرة المؤقتة
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

        // حفظ سؤال المستخدم في قاعدة البيانات والذاكرة المؤقتة
        val userMsg = ChatMessage(role = "user", content = prompt)
        chatHistory.add(userMsg)
        memoryManager.recordInteraction(userMsg.role, userMsg.content)

        scope.launch {
            val fullPrompt = buildChatPrompt()
            val response = LlamaNative.generateResponse(fullPrompt)
            
            // حفظ رد AIRI في قاعدة البيانات والذاكرة المؤقتة
            val assistantMsg = ChatMessage(role = "assistant", content = response)
            chatHistory.add(assistantMsg)
            memoryManager.recordInteraction(assistantMsg.role, assistantMsg.content)

            // الحفاظ على حجم الذاكرة المؤقتة
            if (chatHistory.size > MAX_HISTORY) {
                chatHistory.removeAt(0)
                chatHistory.removeAt(0)
            }

            withContext(Dispatchers.Main) { onResult(response) }
        }
    }

    private fun buildChatPrompt(): String {
        val sb = StringBuilder()
        sb.append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n")
        sb.append("أنت AIRI، مساعد ذكي ومرح. أجب بالعربية.")
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
