package com.airi.assistant.core.intent

/**
 * Represents an event triggered by user input or system action, carrying an Intent.
 */
data class IntentEvent(
    val intent: AiriIntent,
    val payload: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)
