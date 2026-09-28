package com.airi.assistant.ui.viewmodel

/** Ownership rules for the single in-flight response and the shared local KV context. */
internal object SessionGenerationPolicy {
    fun mayPublishToVisibleSession(ownerSessionId: String, visibleSessionId: String): Boolean =
        ownerSessionId.isNotBlank() && ownerSessionId == visibleSessionId

    fun mayReplaceNativeHistory(activeGenerationSessionId: String?): Boolean =
        activeGenerationSessionId.isNullOrBlank()
}
