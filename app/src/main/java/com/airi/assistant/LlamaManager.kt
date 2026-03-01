package com.airi.assistant

import android.content.Context
import kotlinx.coroutines.*
import java.io.File

// كلاس لتمثيل الرسالة في الذاكرة
data class ChatMessage(val role: String, val content: String)

class LlamaManager(private val context: Context) {
    private var isLoaded = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // قائمة الذاكرة (سنتحفظ بآخر 10 رسائل مثلاً لتجنب البطء)
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
            withContext(Dispatchers.Main) { onReady(isLoaded) }
        }
    }

    fun generate(prompt: String, onResult: (String) -> Unit) {
        if (!isLoaded) {
            onResult("المحرك غير مفعل")
            return
        }

        // 1. أضف سؤال المستخدم للذاكرة
        chatHistory.add(ChatMessage("user", prompt))

        scope.launch {
            // 2. قم ببناء الـ Full Prompt مع التاريخ
            val fullPrompt = buildChatPrompt()
            
            // 3. أرسل النص الكامل للمحرك
            val response = LlamaNative.generateResponse(fullPrompt)
            
            // 4. أضف إجابة المحرك للذاكرة
            chatHistory.add(ChatMessage("assistant", response))

            // 5. حافظ على حجم الذاكرة (احذف القديم إذا تجاوز الحد)
            if (chatHistory.size > MAX_HISTORY) {
                chatHistory.removeAt(0)
                chatHistory.removeAt(0)
            }

            withContext(Dispatchers.Main) {
                onResult(response)
            }
        }
    }

    // دالة بناء النص بنظام الأدوار (Llama 3 Template)
    private fun buildChatPrompt(): String {
        val stringBuilder = StringBuilder()
        
        // إضافة "توجيه النظام" (System Prompt) لضبط الشخصية
        stringBuilder.append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n")
        stringBuilder.append("أنت AIRI، مساعد ذكي ومرح. أجب باللغة العربية بأسلوب ودود وقصير.")
        stringBuilder.append("<|eot_id|>\n")

        // إضافة تاريخ المحادثة
        for (message in chatHistory) {
            stringBuilder.append("<|start_header_id|>${message.role}<|end_header_id|>\n")
            stringBuilder.append(message.content)
            stringBuilder.append("<|eot_id|>\n")
        }

        // إخبار المحرك أن الدور الآن على الـ Assistant ليرد
        stringBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n")
        
        return stringBuilder.toString()
    }

    // دالة لمسح الذاكرة (يمكن استدعاؤها لبدء محادثة جديدة)
    fun clearHistory() {
        chatHistory.clear()
    }
}
