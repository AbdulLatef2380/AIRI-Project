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
        fun put(keyName: String, value: String): Boolean
        fun get(keyName: String): String?
        fun remove(keyName: String)
    }

    private class InMemorySecretStore : SecretStore {
        private val values = ConcurrentHashMap<String, String>()
        override fun put(keyName: String, value: String): Boolean { values[keyName] = value; return true }
        override fun get(keyName: String): String? = values[keyName]
        override fun remove(keyName: String) { values.remove(keyName) }
    }

    private class KeystoreSecretStore(private val storage: SecureStorage) : SecretStore {
        override fun put(keyName: String, value: String): Boolean = skillSecretParts(keyName)?.let { (skillId, secretId) ->
            storage.saveSkillSecret(skillId, secretId, value)
        } ?: storage.saveBrokerSecret(keyName, value)

        override fun get(keyName: String): String? = skillSecretParts(keyName)?.let { (skillId, secretId) ->
            storage.getSkillSecret(skillId, secretId)
        } ?: storage.getBrokerSecret(keyName)

        override fun remove(keyName: String) {
            val parts = skillSecretParts(keyName)
            if (parts != null) storage.clearSkillSecret(parts.first, parts.second)
            else storage.clearBrokerSecret(keyName)
        }

        private fun skillSecretParts(storageName: String): Pair<String, String>? {
            val prefix = "PROJECT::SKILL-"
            val separator = "::CONNECTOR::$SKILL_SECRET_CONNECTOR::"
            if (!storageName.startsWith(prefix)) return null
            val remainder = storageName.removePrefix(prefix)
            val split = remainder.indexOf(separator)
            if (split <= 0) return null
            val skillId = remainder.substring(0, split)
            val secretId = remainder.substring(split + separator.length)
            return skillId to secretId
        }
    }

    private object UnavailableSecretStore : SecretStore {
        override fun put(keyName: String, value: String) = false
        override fun get(keyName: String): String? = null
        override fun remove(keyName: String) = Unit
    }

    private val capabilities = ConcurrentHashMap<String, SecretCapability>()
    private val knownSecretNames = ConcurrentHashMap.newKeySet<String>()
    private val secureRandom = SecureRandom()

    @Volatile
    private var store: SecretStore = InMemorySecretStore()

    @Volatile
    private var encryptedStoreReady: Boolean = false

    /** True only after an encrypted Android Keystore-backed store was verified. */
    val isEncryptedStoreReady: Boolean get() = encryptedStoreReady

    /** Activates Keystore-backed storage. Tests may deliberately use the in-memory fallback. */
    fun initialize(context: Context) {
        val secureStorage = SecureStorage(context.applicationContext)
        encryptedStoreReady = secureStorage.isEncrypted
        store = if (encryptedStoreReady) KeystoreSecretStore(secureStorage) else UnavailableSecretStore
    }

    /** Stores a legacy global secret. It is never implicitly visible to a project capability. */
    fun storeSecret(keyName: String, secretValue: String): Boolean =
        storeSecretInternal(keyName = keyName, secretValue = secretValue, projectId = null, connectorId = null)

    fun storeProjectSecret(
        projectId: String,
        secretId: String,
        secretValue: String,
        connectorId: String? = null
    ): Boolean {
        val normalizedProject = normalizeProjectId(projectId) ?: return false
        val normalizedSecret = normalizeKeyName(secretId) ?: return false
        val normalizedConnector = normalizeConnectorId(connectorId)
        return storeSecretInternal(normalizedSecret, secretValue, normalizedProject, normalizedConnector)
    }

    /** Stores a credential under a skill-only namespace, never shared with projects or connectors. */
    fun storeSkillSecret(skillId: String, secretId: String, secretValue: String): Boolean {
        val normalizedSkill = normalizeSkillId(skillId) ?: return false
        val normalizedSecret = normalizeKeyName(secretId) ?: return false
        return storeSecretInternal(
            normalizedSecret,
            secretValue,
            skillProjectId(normalizedSkill),
            SKILL_SECRET_CONNECTOR,
        )
    }

    fun hasSkillSecret(skillId: String, secretId: String): Boolean {
        val normalizedSkill = normalizeSkillId(skillId) ?: return false
        val normalizedSecret = normalizeKeyName(secretId) ?: return false
        val storageName = scopedStorageName(normalizedSecret, skillProjectId(normalizedSkill), SKILL_SECRET_CONNECTOR)
        return !store.get(storageName).isNullOrBlank()
    }

    fun revokeSkillSecret(skillId: String, secretId: String): Boolean {
        val normalizedSkill = normalizeSkillId(skillId) ?: return false
        val normalizedSecret = normalizeKeyName(secretId) ?: return false
        return revokeScopedSecret(normalizedSecret, skillProjectId(normalizedSkill), SKILL_SECRET_CONNECTOR)
    }

    /** Issues a one-use, skill-bound capability after the caller's approval gate succeeds. */
    fun issueSkillCapability(
        skillId: String,
        secretId: String,
        operation: String,
        authorizedByPolicy: Boolean,
        taskId: String? = null,
        ttlMs: Long = DEFAULT_CAPABILITY_TTL_MS,
    ): SecretCapability? {
        val normalizedSkill = normalizeSkillId(skillId) ?: return null
        return issueCapability(
            agentId = normalizedSkill,
            keyName = secretId,
            operation = operation,
            authorizedByPolicy = authorizedByPolicy,
            taskId = taskId,
            ttlMs = ttlMs,
            uses = 1,
            projectId = skillProjectId(normalizedSkill),
            connectorId = SKILL_SECRET_CONNECTOR,
        )
    }

    fun <T> useSkillCapability(
        token: String,
        skillId: String,
        operation: String,
        consumer: (String) -> T,
    ): CapabilityResult<T> {
        val normalizedSkill = normalizeSkillId(skillId)
            ?: return CapabilityResult(CapabilityStatus.DENIED)
        return useProjectCapability(
            token = token,
            agentId = normalizedSkill,
            operation = operation,
            projectId = skillProjectId(normalizedSkill),
            connectorId = SKILL_SECRET_CONNECTOR,
            consumer = consumer,
        )
    }

    /** Suspendable variant for trusted adapters that must perform I/O using the secret. */
    suspend fun <T> useSkillCapabilitySuspend(
        token: String,
        skillId: String,
        operation: String,
        consumer: suspend (String) -> T,
    ): CapabilityResult<T> {
        val normalizedSkill = normalizeSkillId(skillId)
            ?: return CapabilityResult(CapabilityStatus.DENIED)
        val boundProject = skillProjectId(normalizedSkill)
        val pair = synchronized(capabilities) {
            val capability = capabilities[token] ?: return CapabilityResult(CapabilityStatus.REVOKED)
            if (capability.expiresAtMs <= System.currentTimeMillis()) {
                capabilities.remove(token)
                return CapabilityResult(CapabilityStatus.EXPIRED)
            }
            if (capability.agentId != normalizedSkill || capability.operation != operation ||
                capability.projectId != boundProject || capability.connectorId != SKILL_SECRET_CONNECTOR
            ) {
                recordDeniedAccess(normalizedSkill, capability.keyName)
                return CapabilityResult(CapabilityStatus.DENIED)
            }
            val secret = store.get(capability.storageName)
            if (secret.isNullOrBlank()) {
                capabilities.remove(token)
                return CapabilityResult(CapabilityStatus.MISSING_SECRET)
            }
            if (capability.remainingUses <= 1) capabilities.remove(token)
            else capabilities[token] = capability.copy(remainingUses = capability.remainingUses - 1)
            secret
        }
        return try {
            CapabilityResult(CapabilityStatus.CONSUMED, consumer(pair))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CapabilityResult(CapabilityStatus.CONSUMED)
        }
    }

    /** Returns only whether a scoped secret exists; it never reveals secret material. */
    fun hasProjectSecret(projectId: String, secretId: String, connectorId: String? = null): Boolean {
        val normalizedProject = normalizeProjectId(projectId) ?: return false
        val normalizedSecret = normalizeKeyName(secretId) ?: return false
        val storageName = scopedStorageName(normalizedSecret, normalizedProject, normalizeConnectorId(connectorId))
        return !store.get(storageName).isNullOrBlank()
    }

    fun revokeProjectSecret(projectId: String, secretId: String, connectorId: String? = null): Boolean {
        val normalizedProject = normalizeProjectId(projectId) ?: return false
        val normalizedSecret = normalizeKeyName(secretId) ?: return false
        val storageName = scopedStorageName(normalizedSecret, normalizedProject, normalizeConnectorId(connectorId))
        return revokeStorageName(storageName)
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

    /** Invalidates all bearer capabilities after credential wipe/logout. */
    fun revokeAllCapabilities() {
        capabilities.clear()
        knownSecretNames.clear()
    }

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
        encryptedStoreReady = false
    }

    private fun storeSecretInternal(keyName: String, secretValue: String, projectId: String?, connectorId: String?): Boolean {
        val normalizedKey = normalizeKeyName(keyName) ?: return false
        val storageName = scopedStorageName(normalizedKey, projectId, connectorId)
        if (secretValue.isBlank()) {
            store.remove(storageName)
            knownSecretNames.remove(storageName)
            revokeForStorageName(storageName)
            return true
        }
        if (!store.put(storageName, secretValue.trim())) return false
        knownSecretNames += storageName
        revokeForStorageName(storageName)
        return true
    }

    private fun revokeScopedSecret(keyName: String, projectId: String, connectorId: String?): Boolean =
        revokeStorageName(scopedStorageName(keyName, projectId, connectorId))

    private fun revokeStorageName(storageName: String): Boolean {
        val existed = knownSecretNames.remove(storageName) || !store.get(storageName).isNullOrBlank()
        store.remove(storageName)
        revokeForStorageName(storageName)
        return existed
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

    private fun normalizeSkillId(value: String): String? =
        value.trim().lowercase().takeIf { SKILL_ID_PATTERN.matches(it) }

    private fun skillProjectId(skillId: String) = "SKILL-$skillId"

    private const val DEFAULT_OPERATION = "provider_request"
    private const val DEFAULT_CAPABILITY_TTL_MS = 5 * 60_000L
    private const val MIN_CAPABILITY_TTL_MS = 10_000L
    private const val MAX_CAPABILITY_TTL_MS = 60 * 60_000L
    private const val MAX_CAPABILITY_USES = 5
    private const val CAPABILITY_TOKEN_BYTES = 24
    private const val SKILL_SECRET_CONNECTOR = "skill_secret"
    private val KEY_NAME_PATTERN = Regex("[A-Z0-9][A-Z0-9_.-]{0,95}")
    private val SKILL_ID_PATTERN = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
    private val PROJECT_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
    private val CONNECTOR_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
}
