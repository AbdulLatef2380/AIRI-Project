package com.airi.assistant.vault

import android.content.Context
import com.airi.assistant.auth.SecureStorage
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Secret broker for agent-facing credentials.
 *
 * Agents receive [SecretCapability] metadata only. A raw secret can be used
 * solely inside [useCapability] by a trusted provider adapter, after the
 * capability has passed identity, operation, expiry, and one-use checks.
 */
object SecretVault {
    enum class CapabilityStatus { ACTIVE, EXPIRED, CONSUMED, REVOKED, DENIED, MISSING_SECRET }

    data class SecretCapability(
        val token: String,
        val agentId: String,
        val keyName: String,
        val operation: String,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
        val remainingUses: Int,
        val taskId: String? = null
    )

    data class CapabilityResult<T>(
        val status: CapabilityStatus,
        val value: T? = null
    )

    private interface SecretStore {
        fun put(keyName: String, value: String)
        fun get(keyName: String): String?
        fun remove(keyName: String)
    }

    private class InMemorySecretStore : SecretStore {
        private val values = ConcurrentHashMap<String, String>()
        override fun put(keyName: String, value: String) { values[keyName] = value }
        override fun get(keyName: String): String? = values[keyName]
        override fun remove(keyName: String) { values.remove(keyName) }
    }

    private class KeystoreSecretStore(context: Context) : SecretStore {
        private val storage = SecureStorage(context.applicationContext)
        override fun put(keyName: String, value: String) = storage.saveLlmKey(storageKey(keyName), value)
        override fun get(keyName: String): String? = storage.getLlmKey(storageKey(keyName))
        override fun remove(keyName: String) = storage.clearLlmKey(storageKey(keyName))
    }

    private val capabilities = ConcurrentHashMap<String, SecretCapability>()
    private val knownSecretNames = ConcurrentHashMap.newKeySet<String>()
    private val secureRandom = SecureRandom()

    @Volatile
    private var store: SecretStore = InMemorySecretStore()

    /** Activates Keystore-backed storage. Tests may deliberately use the in-memory fallback. */
    fun initialize(context: Context) {
        store = KeystoreSecretStore(context)
    }

    fun storeSecret(keyName: String, secretValue: String) {
        val normalizedKey = normalizeKeyName(keyName) ?: return
        if (secretValue.isBlank()) {
            store.remove(normalizedKey)
            knownSecretNames.remove(normalizedKey)
            revokeForSecret(normalizedKey)
            return
        }
        store.put(normalizedKey, secretValue.trim())
        knownSecretNames += normalizedKey
        revokeForSecret(normalizedKey)
    }

    /**
     * Issues a capability token; it never returns the secret value. The default
     * is a single use and five minute expiry to minimise delegated authority.
     */
    fun issueCapability(
        agentId: String,
        keyName: String,
        operation: String,
        authorizedByPolicy: Boolean,
        taskId: String? = null,
        ttlMs: Long = DEFAULT_CAPABILITY_TTL_MS,
        uses: Int = 1
    ): SecretCapability? {
        val normalizedKey = normalizeKeyName(keyName)
        val normalizedAgent = agentId.trim().takeIf(String::isNotBlank)
        val normalizedOperation = operation.trim().takeIf(String::isNotBlank)
        if (!authorizedByPolicy || normalizedKey == null || normalizedAgent == null || normalizedOperation == null) {
            recordDeniedAccess(agentId, keyName)
            return null
        }
        if (store.get(normalizedKey).isNullOrBlank()) return null

        val now = System.currentTimeMillis()
        val capability = SecretCapability(
            token = randomToken(),
            agentId = normalizedAgent,
            keyName = normalizedKey,
            operation = normalizedOperation,
            issuedAtMs = now,
            expiresAtMs = now + ttlMs.coerceIn(MIN_CAPABILITY_TTL_MS, MAX_CAPABILITY_TTL_MS),
            remainingUses = uses.coerceIn(1, MAX_CAPABILITY_USES),
            taskId = taskId?.trim()?.takeIf(String::isNotBlank)
        )
        capabilities[capability.token] = capability
        return capability
    }

    /**
     * Compatibility entry point. Its return type is now a capability, never a
     * raw credential. New callers should specify the intended operation.
     */
    fun brokerSecret(agentId: String, keyName: String, authorizedByPolicy: Boolean): SecretCapability? =
        issueCapability(
            agentId = agentId,
            keyName = keyName,
            operation = DEFAULT_OPERATION,
            authorizedByPolicy = authorizedByPolicy
        )

    /**
     * Allows a trusted provider adapter to use a secret once. The secret is not
     * returned from this API and is never logged or stored in the capability.
     */
    fun <T> useCapability(
        token: String,
        agentId: String,
        operation: String,
        consumer: (String) -> T
    ): CapabilityResult<T> {
        val capability = capabilities[token] ?: return CapabilityResult(CapabilityStatus.REVOKED)
        val now = System.currentTimeMillis()
        if (capability.expiresAtMs <= now) {
            capabilities.remove(token)
            return CapabilityResult(CapabilityStatus.EXPIRED)
        }
        if (capability.agentId != agentId || capability.operation != operation) {
            recordDeniedAccess(agentId, capability.keyName)
            return CapabilityResult(CapabilityStatus.DENIED)
        }
        val secret = store.get(capability.keyName)
        if (secret.isNullOrBlank()) {
            capabilities.remove(token)
            return CapabilityResult(CapabilityStatus.MISSING_SECRET)
        }

        val updatedUses = capability.remainingUses - 1
        if (updatedUses <= 0) capabilities.remove(token)
        else capabilities[token] = capability.copy(remainingUses = updatedUses)

        return runCatching { CapabilityResult(CapabilityStatus.CONSUMED, consumer(secret)) }
            .getOrElse { CapabilityResult(CapabilityStatus.CONSUMED) }
    }

    fun revokeCapability(token: String): Boolean = capabilities.remove(token) != null

    fun capabilityStatus(token: String): CapabilityStatus? {
        val capability = capabilities[token] ?: return null
        return if (capability.expiresAtMs <= System.currentTimeMillis()) {
            capabilities.remove(token)
            CapabilityStatus.EXPIRED
        } else {
            CapabilityStatus.ACTIVE
        }
    }

    fun clear() {
        capabilities.clear()
        knownSecretNames.forEach { keyName -> store.remove(keyName) }
        knownSecretNames.clear()
        // Reset test state to a non-persistent backend after explicit clear.
        store = InMemorySecretStore()
    }

    private fun revokeForSecret(keyName: String) {
        capabilities.entries.removeIf { (_, capability) -> capability.keyName == keyName }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(CAPABILITY_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun normalizeKeyName(value: String): String? =
        value.trim().uppercase().takeIf { KEY_NAME_PATTERN.matches(it) }

    private fun recordDeniedAccess(agentId: String, keyName: String) {
        runCatching {
            android.util.Log.w("AIRI_Vault", "Agent ${agentId.take(48)} denied access to secret ${keyName.take(64)}")
        }
    }

    private fun storageKey(keyName: String): String = "secret_broker_$keyName"

    private const val DEFAULT_OPERATION = "provider_request"
    private const val DEFAULT_CAPABILITY_TTL_MS = 5 * 60_000L
    private const val MIN_CAPABILITY_TTL_MS = 10_000L
    private const val MAX_CAPABILITY_TTL_MS = 60 * 60_000L
    private const val MAX_CAPABILITY_USES = 5
    private const val CAPABILITY_TOKEN_BYTES = 24
    private val KEY_NAME_PATTERN = Regex("[A-Z0-9][A-Z0-9_.-]{0,95}")
}
