package com.airi.assistant

data class ChatMessage(
    val role: String,    
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
