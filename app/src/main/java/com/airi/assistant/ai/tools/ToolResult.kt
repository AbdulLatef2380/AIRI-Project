package com.airi.assistant.ai.tools

data class ToolResult(
    val success: Boolean,
    val data: String,
    val error: String? = null
)
