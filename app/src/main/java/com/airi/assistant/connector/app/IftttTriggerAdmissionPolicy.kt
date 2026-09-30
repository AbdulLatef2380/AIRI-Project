package com.airi.assistant.connector.app

/** Fail-closed guard for IFTTT side effects, including direct UI callers. */
internal object IftttTriggerAdmissionPolicy {
    fun allows(stateConnected: Boolean, explicitlyDisconnected: Boolean, keyConfigured: Boolean): Boolean =
        stateConnected && !explicitlyDisconnected && keyConfigured
}
