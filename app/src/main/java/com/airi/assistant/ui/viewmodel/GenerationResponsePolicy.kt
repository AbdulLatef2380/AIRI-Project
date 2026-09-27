package com.airi.assistant.ui.viewmodel

/** Terminal classification for one user-visible generation. */
enum class GenerationResponseStatus {
    SUCCESS,
    EMPTY_RESPONSE,
    CANCELLED,
}

object GenerationResponsePolicy {
    fun classify(finalAnswer: String, streamedAnswer: String, cancelled: Boolean): GenerationResponseStatus {
        if (cancelled) return GenerationResponseStatus.CANCELLED
        return if (finalAnswer.trim().isNotEmpty() || streamedAnswer.trim().isNotEmpty()) {
            GenerationResponseStatus.SUCCESS
        } else {
            GenerationResponseStatus.EMPTY_RESPONSE
        }
    }
}
