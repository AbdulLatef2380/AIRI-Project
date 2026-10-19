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

/** OAuth credentials are held only by the encrypted session store and trusted broker adapter. */
data class GithubAuthorizationSession(
    val projectId: String,
    val accountLogin: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long? = null,
    val grantedScopes: Set<String> = emptySet(),
    val installationId: Long? = null,
    val repositories: List<GithubRepositoryGrant> = emptyList(),
    val createdAtMs: Long,
) {
    fun isExpired(nowMs: Long): Boolean = expiresAtMs?.let { it <= nowMs } ?: false

    fun summary(): GithubSessionSummary = GithubSessionSummary(
        projectId = projectId,
        accountLogin = accountLogin,
        expiresAtMs = expiresAtMs,
        grantedScopes = grantedScopes,
        installationId = installationId,
        repositories = repositories,
    )

    override fun toString(): String =
        "GithubAuthorizationSession(projectId=$projectId, accountLogin=$accountLogin, " +
            "accessToken=<redacted>, refreshToken=${if (refreshToken == null) "<none>" else "<redacted>"}, " +
            "expiresAtMs=$expiresAtMs, grantedScopes=$grantedScopes, installationId=$installationId, " +
            "repositories=${repositories.size}, createdAtMs=$createdAtMs)"
}

data class GithubSessionSummary(
    val projectId: String,
    val accountLogin: String,
    val expiresAtMs: Long?,
    val grantedScopes: Set<String>,
    val installationId: Long?,
    val repositories: List<GithubRepositoryGrant>,
)

sealed interface GithubAuthorizationResult<out T> {
    data class Success<T>(val value: T) : GithubAuthorizationResult<T>
    data class Failure(val failure: GithubAuthorizationFailure) : GithubAuthorizationResult<Nothing>
}

/** Contains no URI or external handoff details until the broker contract defines them. */
data class GithubAuthorizationStart(
    val projectId: String,
    val state: String,
    val requestedScopes: Set<String>,
) {
    override fun toString(): String =
        "GithubAuthorizationStart(projectId=$projectId, state=<redacted>, requestedScopes=$requestedScopes)"
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
