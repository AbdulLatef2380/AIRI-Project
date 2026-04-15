package com.airi.assistant.ai.tools

interface Tool {
    val name: String
    val description: String
    val parameters: Map<String, String>
    suspend fun execute(params: Map<String, String>): ToolResult
}
