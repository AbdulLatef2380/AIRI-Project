package com.airi.assistant.ui.viewmodel

/**
 * Defines the state transitions after a persisted chat-session deletion.
 * Local composer state remains untouched unless the durable deletion succeeded.
 */
internal object SessionDeletionPolicy {
    fun shouldRemoveDraft(deleteSucceeded: Boolean): Boolean = deleteSucceeded

    fun replacementSessionId(
        activeSessionId: String,
        deletedSessionId: String,
        remainingSessionIds: List<String>,
    ): String? =
        if (activeSessionId == deletedSessionId) remainingSessionIds.firstOrNull() else null
}
