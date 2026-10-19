package com.airi.assistant.connector.app.github

/**
 * Application-level Broker contract shared by Android and a future server
 * adapter. The authenticated AIRI subject is obtained from the Broker's
 * bearer session; it is deliberately not accepted as a client-supplied field.
 *
 * This is a domain contract, not an HTTP implementation. The wire mapping is
 * documented in docs/architecture/GITHUB_BROKER_CONTRACT_2026-10-20_AR.md.
 */
interface GithubBrokerClient {
    val availability: GithubBrokerAvailability

    suspend fun beginAuthorization(
        request: GithubBrokerAuthorizationRequest,
    ): GithubBrokerResult<GithubBrokerAuthorizationStart>

    suspend fun exchangeAuthorizationCode(
        request: GithubBrokerCodeExchange,
    ): GithubBrokerResult<GithubAuthorizationSession>

    suspend fun listConnections(
        request: GithubBrokerConnectionsRequest,
    ): GithubBrokerResult<List<GithubAuthorizationSession>>

    suspend fun refreshSession(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<GithubAuthorizationSession>

    suspend fun revokeSession(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<Unit>

    suspend fun listRepositories(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<List<GithubRepositoryGrant>>

    suspend fun selectRepositories(
        request: GithubBrokerRepositorySelectionRequest,
    ): GithubBrokerResult<List<GithubRepositoryGrant>>
}

enum class GithubBrokerAvailability { CONFIGURED, BROKER_NOT_CONFIGURED }

sealed interface GithubBrokerResult<out T> {
    data class Success<T>(val value: T) : GithubBrokerResult<T>
    data class Failure(val failure: GithubAuthorizationFailure) : GithubBrokerResult<Nothing>
}

data class GithubBrokerAuthorizationRequest(
    val projectId: String,
    /** Client-generated nonce bound to the authenticated Broker transaction. */
    val clientNonce: String,
    val codeChallenge: String,
    val requestedScopes: Set<String>,
) {
    override fun toString(): String =
        "GithubBrokerAuthorizationRequest(projectId=$projectId, clientNonce=<redacted>, " +
            "codeChallenge=<redacted>, requestedScopes=$requestedScopes)"
}

data class GithubBrokerAuthorizationStart(
    val transactionId: String,
    val authorizationUrl: String,
    val expiresAtMs: Long,
) {
    override fun toString(): String =
        "GithubBrokerAuthorizationStart(transactionId=<redacted>, authorizationUrl=<redacted>, expiresAtMs=$expiresAtMs)"
}

data class GithubBrokerCodeExchange(
    val projectId: String,
    val transactionId: String,
    val handoffCode: String,
) {
    override fun toString(): String =
        "GithubBrokerCodeExchange(projectId=$projectId, transactionId=<redacted>, handoffCode=<redacted>)"
}

data class GithubBrokerConnectionsRequest(
    val projectId: String? = null,
)

data class GithubBrokerSessionRequest(
    val projectId: String,
    val airiConnectionId: String,
) {
    override fun toString(): String =
        "GithubBrokerSessionRequest(projectId=$projectId, airiConnectionId=<redacted>)"
}

data class GithubBrokerRepositorySelectionRequest(
    val projectId: String,
    val airiConnectionId: String,
    val repositoryIds: Set<Long>,
) {
    override fun toString(): String =
        "GithubBrokerRepositorySelectionRequest(projectId=$projectId, airiConnectionId=<redacted>, " +
            "repositoryIds=$repositoryIds)"
}

/** Default DI binding. It never opens a browser or fabricates successful readiness. */
class NotConfiguredGithubBrokerClient : GithubBrokerClient {
    override val availability: GithubBrokerAvailability = GithubBrokerAvailability.BROKER_NOT_CONFIGURED

    override suspend fun beginAuthorization(
        request: GithubBrokerAuthorizationRequest,
    ): GithubBrokerResult<GithubBrokerAuthorizationStart> = unavailable()

    override suspend fun exchangeAuthorizationCode(
        request: GithubBrokerCodeExchange,
    ): GithubBrokerResult<GithubAuthorizationSession> = unavailable()

    override suspend fun listConnections(
        request: GithubBrokerConnectionsRequest,
    ): GithubBrokerResult<List<GithubAuthorizationSession>> = unavailable()

    override suspend fun refreshSession(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<GithubAuthorizationSession> = unavailable()

    override suspend fun revokeSession(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<Unit> = unavailable()

    override suspend fun listRepositories(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<List<GithubRepositoryGrant>> = unavailable()

    override suspend fun selectRepositories(
        request: GithubBrokerRepositorySelectionRequest,
    ): GithubBrokerResult<List<GithubRepositoryGrant>> = unavailable()

    private fun <T> unavailable(): GithubBrokerResult<T> = GithubBrokerResult.Failure(
        GithubAuthorizationFailure(GithubAuthorizationFailureCode.BROKER_NOT_CONFIGURED)
    )
}
