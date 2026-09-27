package com.airi.assistant.execution

/**
 * Invariant shared by every streaming backend: whitespace-only output is not
 * a successful assistant response. Cancellation and transport failures must be
 * represented by their own terminal paths instead of an empty completion.
 */
object ResponseTerminalPolicy {
    const val EMPTY_RESPONSE_CODE = "EMPTY_RESPONSE"

    fun isSuccessful(text: String): Boolean = text.isNotBlank()
}
