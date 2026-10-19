package com.airi.assistant.connector.app.github

/**
 * Application-level contract for the future server-side broker.
 * These Kotlin types describe domain operations only; they are not an HTTP
 * schema. Endpoint paths, wire formats, and authorization handoff must come
 * from the deployed broker specification before a network adapter is added.
 */
interface GithubBrokerClient {
    val availability: GithubBrokerAvailability

    suspend fun beginAuthorization(
        request: GithubBrokerAuthorizationRequest,
    ): GithubBrokerResult<Unit>

    suspend fun exchangeAuthorizationCode(
        request: GithubBrokerCodeExchange,
    ): GithubBrokerResult<GithubAuthorizationSession>

    suspend fun refreshSession(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<GithubAuthorizationSession>

    suspend fun revokeSession(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<Unit>

    suspend fun listRepositories(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<List<GithubRepositoryGrant>>
}

enum class GithubBrokerAvailability { CONFIGURED, BROKER_NOT_CONFIGURED }

sealed interface GithubBrokerResult<out T> {
    data class Success<T>(val value: T) : GithubBrokerResult<T>
    data class Failure(val failure: GithubAuthorizationFailure) : GithubBrokerResult<Nothing>
}

data class GithubBrokerAuthorizationRequest(
    val projectId: String,
    val state: String,
    val codeChallenge: String,
    val requestedScopes: Set<String>,
) {
    override fun toString(): String =
        "GithubBrokerAuthorizationRequest(projectId=$projectId, state=<redacted>, codeChallenge=<redacted>, requestedScopes=$requestedScopes)"
}

data class GithubBrokerCodeExchange(
    val projectId: String,
    val authorizationCode: String,
    val codeVerifier: String,
    val requestedScopes: Set<String>,
) {
    override fun toString(): String =
        "GithubBrokerCodeExchange(projectId=$projectId, authorizationCode=<redacted>, " +
            "codeVerifier=<redacted>, requestedScopes=$requestedScopes)"
}

data class GithubBrokerSessionRequest(
    val projectId: String,
    val airiConnectionId: String,
) {
    override fun toString(): String =
        "GithubBrokerSessionRequest(projectId=$projectId, airiConnectionId=<redacted>)"
}

/** Default DI binding. It never opens a browser or fabricates successful readiness. */
class NotConfiguredGithubBrokerClient : GithubBrokerClient {
    override val availability: GithubBrokerAvailability = GithubBrokerAvailability.BROKER_NOT_CONFIGURED

    override suspend fun beginAuthorization(
        request: GithubBrokerAuthorizationRequest,
    ): GithubBrokerResult<Unit> = unavailable()

    override suspend fun exchangeAuthorizationCode(
        request: GithubBrokerCodeExchange,
    ): GithubBrokerResult<GithubAuthorizationSession> = unavailable()

    override suspend fun refreshSession(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<GithubAuthorizationSession> = unavailable()

    override suspend fun revokeSession(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<Unit> = unavailable()

    override suspend fun listRepositories(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<List<GithubRepositoryGrant>> = unavailable()

    private fun <T> unavailable(): GithubBrokerResult<T> = GithubBrokerResult.Failure(
        GithubAuthorizationFailure(GithubAuthorizationFailureCode.BROKER_NOT_CONFIGURED)
    )
}
