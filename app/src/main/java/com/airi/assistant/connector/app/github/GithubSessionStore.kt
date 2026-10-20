package com.airi.assistant.connector.app.github

import com.airi.assistant.connector.ConnectorAuthManager
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Narrow seam for JVM tests; production storage is ConnectorAuthManager's encrypted preferences. */
internal interface GithubSessionCredentialStore {
    val isAvailable: Boolean
    fun read(namespace: String): String?
    fun write(namespace: String, value: String): Boolean
    fun remove(namespace: String): Boolean
}

internal class ConnectorAuthGithubSessionCredentialStore(
    private val authManager: ConnectorAuthManager,
) : GithubSessionCredentialStore {
    override val isAvailable: Boolean get() = authManager.isSecureStorageAvailable

    override fun read(namespace: String): String? = authManager.getCredential(namespace, SESSION_KEY)

    override fun write(namespace: String, value: String): Boolean =
        authManager.storeCredential(namespace, SESSION_KEY, value)

    override fun remove(namespace: String): Boolean = authManager.clearCredential(namespace, SESSION_KEY)

    private companion object { const val SESSION_KEY = "github_oauth_session_v1" }
}

sealed interface GithubSessionReadResult {
    data object Missing : GithubSessionReadResult
    data class Found(val session: GithubAuthorizationSession) : GithubSessionReadResult
    data class Failure(val failure: GithubAuthorizationFailure) : GithubSessionReadResult
}

sealed interface GithubSessionWriteResult {
    data object Saved : GithubSessionWriteResult
    data class Failure(val failure: GithubAuthorizationFailure) : GithubSessionWriteResult
}

/**
 * Per-project GitHub OAuth session store. The project id is validated and hashed
 * before it is used as a ConnectorAuthManager credential namespace. The raw
 * project id remains inside the encrypted value and is checked again on read.
 */
class GithubSessionStore internal constructor(
    private val credentialStore: GithubSessionCredentialStore,
) {
    constructor(authManager: ConnectorAuthManager) : this(ConnectorAuthGithubSessionCredentialStore(authManager))

    fun load(projectId: String): GithubSessionReadResult {
        val normalizedProject = GithubProjectIdPolicy.normalize(projectId)
            ?: return GithubSessionReadResult.Failure(
                GithubAuthorizationFailure(GithubAuthorizationFailureCode.INVALID_PROJECT)
            )
        if (!credentialStore.isAvailable) return storageFailure()
        val encoded = credentialStore.read(namespaceFor(normalizedProject))
            ?: return GithubSessionReadResult.Missing
        val decoded = runCatching { decode(encoded) }.getOrNull()
            ?: return GithubSessionReadResult.Failure(
                GithubAuthorizationFailure(GithubAuthorizationFailureCode.CORRUPT_SESSION)
            )
        if (decoded.projectId != normalizedProject) return GithubSessionReadResult.Failure(
            GithubAuthorizationFailure(GithubAuthorizationFailureCode.PROJECT_MISMATCH)
        )
        return GithubSessionReadResult.Found(decoded)
    }

    fun save(session: GithubAuthorizationSession): GithubSessionWriteResult {
        val normalizedProject = GithubProjectIdPolicy.normalize(session.projectId)
        if (normalizedProject == null || normalizedProject != session.projectId ||
            session.airiConnectionId.isBlank() || session.githubAccountId <= 0L ||
            session.accountLogin.isBlank() ||
            session.expiresAtMs?.let { it <= 0L } == true ||
            session.grantedScopes.any(String::isBlank) ||
            session.repositories.any { it.repositoryId <= 0L || it.owner.isBlank() || it.name.isBlank() } ||
            session.repositories.map { it.repositoryId }.toSet().size != session.repositories.size
        ) {
            return GithubSessionWriteResult.Failure(
                GithubAuthorizationFailure(GithubAuthorizationFailureCode.INVALID_CALLBACK)
            )
        }
        if (!credentialStore.isAvailable) return storageWriteFailure()
        val saved = credentialStore.write(namespaceFor(normalizedProject), encode(session))
        return if (saved) GithubSessionWriteResult.Saved else storageWriteFailure()
    }

    /** Erases only this project's OAuth session; the legacy PAT namespace is untouched. */
    fun clear(projectId: String): Boolean {
        val normalizedProject = GithubProjectIdPolicy.normalize(projectId) ?: return false
        if (!credentialStore.isAvailable) return false
        return credentialStore.remove(namespaceFor(normalizedProject))
    }

    internal fun namespaceFor(projectId: String): String {
        val normalizedProject = requireNotNull(GithubProjectIdPolicy.normalize(projectId))
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedProject.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
        return "github_project_$digest"
    }

    private fun storageFailure() = GithubSessionReadResult.Failure(
        GithubAuthorizationFailure(GithubAuthorizationFailureCode.SECURE_STORAGE_UNAVAILABLE)
    )

    private fun storageWriteFailure() = GithubSessionWriteResult.Failure(
        GithubAuthorizationFailure(GithubAuthorizationFailureCode.SECURE_STORAGE_UNAVAILABLE)
    )

    private fun encode(session: GithubAuthorizationSession): String {
        val scopes = JSONArray().apply { session.grantedScopes.sorted().forEach(::put) }
        val repos = JSONArray().apply {
            session.repositories.forEach { repo ->
                val permissions = JSONObject().apply {
                    repo.permissions.toSortedMap().forEach { (permission, level) -> put(permission, level.name) }
                }
                put(JSONObject()
                    .put("repositoryId", repo.repositoryId)
                    .put("owner", repo.owner)
                    .put("name", repo.name)
                    .put("permissions", permissions))
            }
        }
        return JSONObject()
            .put("version", STORAGE_VERSION)
            .put("airiConnectionId", session.airiConnectionId)
            .put("projectId", session.projectId)
            .put("githubAccountId", session.githubAccountId)
            .put("accountLogin", session.accountLogin)
            .put("expiresAtMs", session.expiresAtMs ?: JSONObject.NULL)
            .put("grantedScopes", scopes)
            .put("repositories", repos)
            .put("createdAtMs", session.createdAtMs)
            .toString()
    }

    private fun decode(encoded: String): GithubAuthorizationSession {
        val json = JSONObject(encoded)
        require(json.optInt("version") == STORAGE_VERSION)
        val scopesJson = json.getJSONArray("grantedScopes")
        val scopes = buildSet {
            for (index in 0 until scopesJson.length()) add(scopesJson.getString(index))
        }
        val reposJson = json.getJSONArray("repositories")
        val repositories = buildList {
            for (index in 0 until reposJson.length()) {
                val repoJson = reposJson.getJSONObject(index)
                val permissionJson = repoJson.optJSONObject("permissions") ?: JSONObject()
                val permissions = buildMap {
                    permissionJson.keys().forEach { key ->
                        put(key, GithubRepositoryPermission.valueOf(permissionJson.getString(key)))
                    }
                }
                add(
                    GithubRepositoryGrant(
                        repositoryId = repoJson.getLong("repositoryId"),
                        owner = repoJson.getString("owner"),
                        name = repoJson.getString("name"),
                        permissions = permissions,
                    )
                )
            }
        }
        val expiresAt = if (json.isNull("expiresAtMs")) null else json.getLong("expiresAtMs")
        return GithubAuthorizationSession(
            airiConnectionId = json.getString("airiConnectionId"),
            projectId = json.getString("projectId"),
            githubAccountId = json.getLong("githubAccountId"),
            accountLogin = json.getString("accountLogin"),
            expiresAtMs = expiresAt,
            grantedScopes = scopes,
            repositories = repositories,
            createdAtMs = json.getLong("createdAtMs"),
        )
    }

    private companion object { const val STORAGE_VERSION = 2 }
}
