package com.airi.assistant.core.intent

/**
 * Represents the outcome of processing an IntentEvent.
 */
data class IntentResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, Any> = emptyMap(),
    val nextIntent: AiriIntent? = null,
    val timestamp: Long = System.currentTimeMillis()
)
