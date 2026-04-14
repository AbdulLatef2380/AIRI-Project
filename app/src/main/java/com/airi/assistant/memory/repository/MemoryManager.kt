package com.airi.assistant.memory.repository

import android.content.Context
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.entity.UserPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MemoryManager(context: Context) {
    private val db = AiriDatabase.getDatabase(context)
    private val dao = db.memoryDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun recordInteraction(role: String, content: String, emotion: String? = null) {
        scope.launch {
            dao.insertMessage(ChatMessage(role = role, content = content, emotionState = emotion))
        }
    }

    fun updatePreference(key: String, value: String, category: String = "personal") {
        scope.launch {
            dao.savePreference(UserPreference(key = key, value = value, category = category))
        }
    }

    suspend fun getConversationContext(limit: Int = 10): String {
        val messages = dao.getRecentMessages(limit).reversed()
        return messages.joinToString("\n") { "${it.role}: ${it.content}" }
    }

    suspend fun getRecentMessages(limit: Int = 10): List<ChatMessage> {
        return dao.getRecentMessages(limit)
    }

    suspend fun getMessageCount(): Int {
        return dao.getMessageCount()
    }

    suspend fun clearAll() {
        dao.clearAllMessages()
    }
}
