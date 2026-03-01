package com.airi.assistant

import android.content.Context
import kotlinx.coroutines.*
import java.io.File

class LlamaManager(private val context: Context) {
    private var isLoaded = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // نستخدم القائمة من الكلاس الموحد
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

        chatHistory.add(ChatMessage("user", prompt))

        scope.launch {
            val fullPrompt = buildChatPrompt()
            val response = LlamaNative.generateResponse(fullPrompt)
            
            chatHistory.add(ChatMessage("assistant", response))

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
