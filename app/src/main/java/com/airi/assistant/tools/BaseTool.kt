package com.airi.assistant.tools

interface BaseTool {
    val name: String
    suspend fun execute(input: String): String
}
