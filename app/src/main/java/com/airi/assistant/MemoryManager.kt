package com.airi.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MemoryManager(context: Context) {
    private val db = AiriDatabase.getDatabase(context)
    private val dao = db.memoryDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * حفظ رسالة جديدة في الذاكرة
     */
    fun recordInteraction(sender: String, content: String, emotion: String? = null) {
        scope.launch {
            dao.insertMessage(ChatMessage(sender = sender, content = content, emotionState = emotion))
        }
    }

    /**
     * حفظ تفضيل للمستخدم
     */
    fun updatePreference(key: String, value: String, category: String = "personal") {
        scope.launch {
            dao.savePreference(UserPreference(key = key, value = value, category = category))
        }
    }

    /**
     * استرجاع السياق الأخير للمحادثة (لتحسين ردود AIRI)
     */
    suspend fun getConversationContext(): String {
        val messages = dao.getRecentMessages().reversed()
        return messages.joinToString("\n") { "${it.sender}: ${it.content}" }
    }
}
