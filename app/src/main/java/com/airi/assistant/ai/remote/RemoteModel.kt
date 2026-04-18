package com.airi.assistant.ai.remote

data class RemoteModel(
    val id: String,
    val name: String,
    val serverUrl: String,
    val apiKey: String = "",
    val isActive: Boolean = false
)
