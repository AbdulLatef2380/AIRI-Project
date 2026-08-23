package com.airi.assistant.vault

import android.content.Context
import com.airi.assistant.auth.SecureStorage
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Secret broker for agent-facing credentials.
 *
 * Agents receive [SecretCapability] metadata only. A raw secret can be used
 * solely inside [useCapability] or [useProjectCapability] by a trusted provider
 * adapter, after identity, operation, project, connector, expiry, and one-use
 * checks pass. Project-scoped secrets never fall back to a global secret with
 * the same logical name.
 */
object SecretVault {
    enum class CapabilityStatus { ACTIVE, EXPIRED, CONSUMED, REVOKED, DENIED, MISSING_SECRET }

    data class ProjectSecret(
        val projectId: String,
        val secretId: String,
        val scope: String = "PROJECT",
        val connectorId: String? = null
    )

    data class SecretCapability(
        val token: String,
        val agentId: String,
        val keyName: String,
        val operation: String,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
        val remainingUses: Int,
        val taskId: String? = null,
        val projectId: String? = null,
        val connectorId: String? = null,
        /** Internal encrypted-store namespace; it contains no secret value. */
        val storageName: String = keyName
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

    /** Stores a legacy global secret. It is never implicitly visible to a project capability. */
    fun storeSecret(keyName: String, secretValue: String) {
        storeSecretInternal(keyName = keyName, secretValue = secretValue, projectId = null, connectorId = null)
    }

    fun storeProjectSecret(
        projectId: String,
        secretId: String,
        secretValue: String,
        connectorId: String? = null
    ): Boolean {
        val normalizedProject = normalizeProjectId(projectId) ?: return false
        val normalizedSecret = normalizeKeyName(secretId) ?: return false
        val normalizedConnector = normalizeConnectorId(connectorId)
        storeSecretInternal(normalizedSecret, secretValue, normalizedProject, normalizedConnector)
        return true
    }

    fun revokeProjectSecret(projectId: String, secretId: String, connectorId: String? = null): Boolean {
        val normalizedProject = normalizeProjectId(projectId) ?: return false
        val normalizedSecret = normalizeKeyName(secretId) ?: return false
        val storageName = scopedStorageName(normalizedSecret, normalizedProject, normalizeConnectorId(connectorId))
        val existed = knownSecretNames.remove(storageName)
        store.remove(storageName)
        revokeForStorageName(storageName)
        return existed
    }

    /**
     * Issues a global or project-scoped capability token; it never returns the
     * secret value. A project secret requires a matching project and never
     * reads the legacy global namespace as a fallback.
     */
    fun issueCapability(
        agentId: String,
        keyName: String,
        operation: String,
        authorizedByPolicy: Boolean,
        taskId: String? = null,
        ttlMs: Long = DEFAULT_CAPABILITY_TTL_MS,
        uses: Int = 1,
        projectId: String? = null,
        connectorId: String? = null
    ): SecretCapability? {
        val normalizedKey = normalizeKeyName(keyName)
        val normalizedAgent = agentId.trim().takeIf(String::isNotBlank)
        val normalizedOperation = operation.trim().takeIf(String::isNotBlank)
        val normalizedProject = projectId?.let(::normalizeProjectId)
        val normalizedConnector = normalizeConnectorId(connectorId)
        if (!authorizedByPolicy || normalizedKey == null || normalizedAgent == null || normalizedOperation == null ||
            (projectId != null && normalizedProject == null)
        ) {
            recordDeniedAccess(agentId, keyName)
            return null
        }
        val storageName = scopedStorageName(normalizedKey, normalizedProject, normalizedConnector)
        if (store.get(storageName).isNullOrBlank()) return null

        val now = System.currentTimeMillis()
        val capability = SecretCapability(
            token = randomToken(),
            agentId = normalizedAgent,
            keyName = normalizedKey,
            operation = normalizedOperation,
            issuedAtMs = now,
            expiresAtMs = now + ttlMs.coerceIn(MIN_CAPABILITY_TTL_MS, MAX_CAPABILITY_TTL_MS),
            remainingUses = uses.coerceIn(1, MAX_CAPABILITY_USES),
            taskId = taskId?.trim()?.takeIf(String::isNotBlank),
            projectId = normalizedProject,
            connectorId = normalizedConnector,
            storageName = storageName
        )
        capabilities[capability.token] = capability
        return capability
    }

    /** Compatibility entry point. Its return type is now a capability, never a raw credential. */
    fun brokerSecret(agentId: String, keyName: String, authorizedByPolicy: Boolean): SecretCapability? =
        issueCapability(
            agentId = agentId,
            keyName = keyName,
            operation = DEFAULT_OPERATION,
            authorizedByPolicy = authorizedByPolicy
        )

    /** Uses a global capability. Project-bound capabilities are denied here. */
    fun <T> useCapability(
        token: String,
        agentId: String,
        operation: String,
        consumer: (String) -> T
    ): CapabilityResult<T> = consumeCapability(token, agentId, operation, null, null, consumer)

    /** Uses a capability only when the current project and optional connector match its issue scope. */
    fun <T> useProjectCapability(
        token: String,
        agentId: String,
        operation: String,
        projectId: String,
        connectorId: String? = null,
        consumer: (String) -> T
    ): CapabilityResult<T> = consumeCapability(
        token = token,
        agentId = agentId,
        operation = operation,
        projectId = normalizeProjectId(projectId),
        connectorId = normalizeConnectorId(connectorId),
        consumer = consumer
    )

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
        knownSecretNames.forEach { storageName -> store.remove(storageName) }
        knownSecretNames.clear()
        store = InMemorySecretStore()
    }

    private fun storeSecretInternal(keyName: String, secretValue: String, projectId: String?, connectorId: String?) {
        val normalizedKey = normalizeKeyName(keyName) ?: return
        val storageName = scopedStorageName(normalizedKey, projectId, connectorId)
        if (secretValue.isBlank()) {
            store.remove(storageName)
            knownSecretNames.remove(storageName)
            revokeForStorageName(storageName)
            return
        }
        store.put(storageName, secretValue.trim())
        knownSecretNames += storageName
        revokeForStorageName(storageName)
    }

    private fun <T> consumeCapability(
        token: String,
        agentId: String,
        operation: String,
        projectId: String?,
        connectorId: String?,
        consumer: (String) -> T
    ): CapabilityResult<T> {
        val capability = capabilities[token] ?: return CapabilityResult(CapabilityStatus.REVOKED)
        val now = System.currentTimeMillis()
        if (capability.expiresAtMs <= now) {
            capabilities.remove(token)
            return CapabilityResult(CapabilityStatus.EXPIRED)
        }
        if (capability.agentId != agentId || capability.operation != operation ||
            capability.projectId != projectId || capability.connectorId != connectorId
        ) {
            recordDeniedAccess(agentId, capability.keyName)
            return CapabilityResult(CapabilityStatus.DENIED)
        }
        val secret = store.get(capability.storageName)
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

    private fun revokeForStorageName(storageName: String) {
        capabilities.entries.removeIf { (_, capability) -> capability.storageName == storageName }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(CAPABILITY_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun normalizeKeyName(value: String): String? =
        value.trim().uppercase().takeIf { KEY_NAME_PATTERN.matches(it) }

    private fun normalizeProjectId(value: String): String? =
        value.trim().takeIf { PROJECT_ID_PATTERN.matches(it) }

    private fun normalizeConnectorId(value: String?): String? =
        value?.trim()?.takeIf { CONNECTOR_ID_PATTERN.matches(it) }

    private fun recordDeniedAccess(agentId: String, keyName: String) {
        runCatching {
            android.util.Log.w("AIRI_Vault", "Agent ${agentId.take(48)} denied access to secret ${keyName.take(64)}")
        }
    }

    private fun scopedStorageName(keyName: String, projectId: String?, connectorId: String?): String = when {
        // Preserve the existing encrypted-store namespace for legacy global secrets.
        projectId == null -> keyName
        connectorId == null -> "PROJECT::$projectId::$keyName"
        else -> "PROJECT::$projectId::CONNECTOR::$connectorId::$keyName"
    }

    private fun storageKey(storageName: String): String = "secret_broker_$storageName"

    private const val DEFAULT_OPERATION = "provider_request"
    private const val DEFAULT_CAPABILITY_TTL_MS = 5 * 60_000L
    private const val MIN_CAPABILITY_TTL_MS = 10_000L
    private const val MAX_CAPABILITY_TTL_MS = 60 * 60_000L
    private const val MAX_CAPABILITY_USES = 5
    private const val CAPABILITY_TOKEN_BYTES = 24
    private val KEY_NAME_PATTERN = Regex("[A-Z0-9][A-Z0-9_.-]{0,95}")
    private val PROJECT_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
    private val CONNECTOR_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
}
