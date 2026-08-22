package com.airi.assistant.vault

/**
 * Secure Vault and Agent Secret Broker for AIRI Core.
 * Ensures the agent never sees raw credentials unless explicitly authorized by policy.
 */
object SecretVault {
    private val encryptedStore = mutableMapOf<String, String>()

    fun storeSecret(keyName: String, secretValue: String) {
        encryptedStore[keyName] = secretValue
    }

    fun brokerSecret(agentId: String, keyName: String, authorizedByPolicy: Boolean): String? {
        if (!authorizedByPolicy) {
            recordDeniedAccess(agentId, keyName)
            return null
        }
        return encryptedStore[keyName]
    }

    fun clear() {
        encryptedStore.clear()
    }

    private fun recordDeniedAccess(agentId: String, keyName: String) {
        // Android's logger is unavailable in the JVM unit-test runtime. A denied
        // request must remain fail-closed even when telemetry cannot be emitted.
        runCatching {
            android.util.Log.w("AIRI_Vault", "Agent $agentId denied access to secret $keyName")
        }
    }
}
