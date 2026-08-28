package com.airi.assistant.connector.app

/**
 * Guards Google resource mutations until AIRI has a durable approval workflow
 * that owns an exact task, run, and user decision for each write.
 */
internal object GoogleConnectorActionPolicy {

    private val writeActionsAwaitingDurableApproval = setOf(
        "gmail_send",
        "calendar_create"
    )

    fun blockedWriteAction(action: String): String? = when (action) {
        in writeActionsAwaitingDurableApproval ->
            "Google $action requires an explicit, durable approval flow and is not available yet."
        else -> null
    }
}
