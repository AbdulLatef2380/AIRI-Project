package com.airi.assistant.ui.viewmodel

/**
 * Pure CSRF state comparison kept outside Android and logging APIs so it can
 * be tested without ever recording either the expected or received secret.
 */
internal object OAuthCallbackStateValidator {
    fun matches(expectedState: String, incomingState: String): Boolean =
        expectedState.isNotBlank() && incomingState.isNotBlank() &&
            expectedState == incomingState
}
