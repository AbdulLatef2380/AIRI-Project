package com.airi.core.model

data class IntentData(
    val id: String,
    val type: IntentType,
    val rawInput: String,
    val timestamp: Long,
    val metadata: Map<String, String> = emptyMap()
)

enum class IntentType {
    CHAT,
    QUERY,
    COMMAND,
    SYSTEM,
    AUTOMATION
}
