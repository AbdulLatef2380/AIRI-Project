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
            android.util.Log.w("AIRI_Vault", "Agent $agentId denied access to secret $keyName")
            return null
        }
        return encryptedStore[keyName]
    }

    fun clear() {
        encryptedStore.clear()
    }
}
