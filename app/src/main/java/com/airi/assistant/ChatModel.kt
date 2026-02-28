package com.airi.assistant

data class ChatModel(
    var text: String,    // نص الرسالة
    val isUser: Boolean  // هل المرسل هو المستخدم أم AIRI؟
)
