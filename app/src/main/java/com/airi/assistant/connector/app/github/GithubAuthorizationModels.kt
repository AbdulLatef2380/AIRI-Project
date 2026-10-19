package com.airi.assistant.connector.app.github

/** Public lifecycle projection; it never contains an access or refresh token. */
sealed interface GithubAuthorizationState {
    data object SignedOut : GithubAuthorizationState
    data class AwaitingAuthorization(val startedAtMs: Long) : GithubAuthorizationState
    data class Exchanging(val startedAtMs: Long) : GithubAuthorizationState
    data class Ready(val session: GithubSessionSummary) : GithubAuthorizationState
    data class Expired(val session: GithubSessionSummary) : GithubAuthorizationState
    /** Local credentials were erased; false means remote revocation was not confirmed. */
    data class Revoked(val remoteRevocationConfirmed: Boolean) : GithubAuthorizationState
    data object Cancelled : GithubAuthorizationState
    data class Failed(val failure: GithubAuthorizationFailure) : GithubAuthorizationState
}

enum class GithubAuthorizationFailureCode {
    BROKER_NOT_CONFIGURED,
    INVALID_PROJECT,
    INVALID_CALLBACK,
    INVALID_STATE,
    PROJECT_MISMATCH,
    NETWORK_ERROR,
    ACCESS_DENIED,
    INSUFFICIENT_PERMISSIONS,
    SESSION_EXPIRED,
    SESSION_REVOKED,
    SECURE_STORAGE_UNAVAILABLE,
    CORRUPT_SESSION,
    HTTP_UNAUTHORIZED,
    HTTP_FORBIDDEN,
    HTTP_NOT_FOUND,
    HTTP_CONFLICT,
    HTTP_INVALID_REQUEST,
    RATE_LIMITED,
    PROVIDER_UNAVAILABLE,
    HTTP_FAILURE,
}

/** Contains only safe classifications and status metadata, never provider response bodies. */
data class GithubAuthorizationFailure(
    val code: GithubAuthorizationFailureCode,
    val retryable: Boolean = false,
    val httpStatus: Int? = null,
) {
    companion object {
        fun fromHttpStatus(status: Int): GithubAuthorizationFailure = when (status) {
            401 -> GithubAuthorizationFailure(GithubAuthorizationFailureCode.HTTP_UNAUTHORIZED, httpStatus = status)
            403 -> GithubAuthorizationFailure(GithubAuthorizationFailureCode.HTTP_FORBIDDEN, httpStatus = status)
            404 -> GithubAuthorizationFailure(GithubAuthorizationFailureCode.HTTP_NOT_FOUND, httpStatus = status)
            409 -> GithubAuthorizationFailure(GithubAuthorizationFailureCode.HTTP_CONFLICT, httpStatus = status)
            422 -> GithubAuthorizationFailure(GithubAuthorizationFailureCode.HTTP_INVALID_REQUEST, httpStatus = status)
            429 -> GithubAuthorizationFailure(GithubAuthorizationFailureCode.RATE_LIMITED, retryable = true, httpStatus = status)
            in 500..599 -> GithubAuthorizationFailure(GithubAuthorizationFailureCode.PROVIDER_UNAVAILABLE, retryable = true, httpStatus = status)
            else -> GithubAuthorizationFailure(GithubAuthorizationFailureCode.HTTP_FAILURE, httpStatus = status)
        }
    }
}

enum class GithubRepositoryPermission { NONE, READ, WRITE }

data class GithubRepositoryGrant(
    val repositoryId: Long,
    val owner: String,
    val name: String,
    val permissions: Map<String, GithubRepositoryPermission> = emptyMap(),
) {
    val fullName: String get() = "$owner/$name"
}

/**
 * Client-safe projection of a server-side GitHub connection.
 * GitHub access and refresh tokens never belong in this Android model.
 */
data class GithubAuthorizationSession(
    val airiConnectionId: String,
    val projectId: String,
    val githubAccountId: Long,
    val accountLogin: String,
    val expiresAtMs: Long? = null,
    val grantedScopes: Set<String> = emptySet(),
    val repositories: List<GithubRepositoryGrant> = emptyList(),
    val createdAtMs: Long,
) {
    fun isExpired(nowMs: Long): Boolean = expiresAtMs?.let { it <= nowMs } ?: false

    fun summary(): GithubSessionSummary = GithubSessionSummary(
        airiConnectionId = airiConnectionId,
        projectId = projectId,
        githubAccountId = githubAccountId,
        accountLogin = accountLogin,
        expiresAtMs = expiresAtMs,
        grantedScopes = grantedScopes,
        repositories = repositories,
    )

    override fun toString(): String =
        "GithubAuthorizationSession(airiConnectionId=<redacted>, projectId=$projectId, " +
            "githubAccountId=$githubAccountId, accountLogin=$accountLogin, " +
            "expiresAtMs=$expiresAtMs, grantedScopes=$grantedScopes, " +
            "repositories=${repositories.size}, createdAtMs=$createdAtMs)"
}

data class GithubSessionSummary(
    val airiConnectionId: String,
    val projectId: String,
    val githubAccountId: Long,
    val accountLogin: String,
    val expiresAtMs: Long?,
    val grantedScopes: Set<String>,
    val repositories: List<GithubRepositoryGrant>,
)

sealed interface GithubAuthorizationResult<out T> {
    data class Success<T>(val value: T) : GithubAuthorizationResult<T>
    data class Failure(val failure: GithubAuthorizationFailure) : GithubAuthorizationResult<Nothing>
}

/** Client-safe Broker handoff projection; it contains no token or installation ID. */
data class GithubAuthorizationStart(
    val projectId: String,
    val state: String,
    val requestedScopes: Set<String>,
    val transactionId: String,
    val authorizationUrl: String,
    val expiresAtMs: Long,
) {
    override fun toString(): String =
        "GithubAuthorizationStart(projectId=$projectId, state=<redacted>, transactionId=<redacted>, " +
            "authorizationUrl=<redacted>, requestedScopes=$requestedScopes, expiresAtMs=$expiresAtMs)"
}

data class GithubRevocationResult(
    val localSessionRemoved: Boolean,
    val remoteRevocationConfirmed: Boolean,
    val remoteFailure: GithubAuthorizationFailure? = null,
)

internal object GithubProjectIdPolicy {
    private val pattern = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")

    fun normalize(value: String): String? = value.trim().takeIf(pattern::matches)
}
