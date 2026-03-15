package com.airi.assistant.ui

data class ChatModel(
    var text: String,    // نص الرسالة
    val isUser: Boolean  // هل المرسل هو المستخدم أم AIRI؟
)
