package com.airi.assistant.connector.app.github

/**
 * Test-only Broker. One instance represents one authenticated AIRI subject;
 * the caller cannot choose an airiUserId in any request. It intentionally has
 * no production source-set counterpart and uses only synthetic fixtures.
 */
internal class MultiTenantGithubFakeBroker(
    private val authenticatedAiriUserId: String,
    sessions: List<GithubAuthorizationSession>,
) : GithubBrokerClient {
    override val availability: GithubBrokerAvailability = GithubBrokerAvailability.CONFIGURED

    private val sessionsByConnection = sessions.associateBy { it.airiConnectionId }.toMutableMap()
    private val transactions = mutableMapOf<String, String>()
    private var transactionSequence = 0

    override suspend fun beginAuthorization(
        request: GithubBrokerAuthorizationRequest,
    ): GithubBrokerResult<GithubBrokerAuthorizationStart> {
        val session = sessionsByConnection.values.singleOrNull { it.projectId == request.projectId }
            ?: return forbidden()
        val transactionId = "tx-$authenticatedAiriUserId-${++transactionSequence}"
        transactions[transactionId] = session.airiConnectionId
        return GithubBrokerResult.Success(
            GithubBrokerAuthorizationStart(
                transactionId = transactionId,
                authorizationUrl = "https://fake.invalid/github/install/$transactionId",
                expiresAtMs = session.expiresAtMs ?: 90_000L,
            )
        )
    }

    override suspend fun exchangeAuthorizationCode(
        request: GithubBrokerCodeExchange,
    ): GithubBrokerResult<GithubAuthorizationSession> {
        val connectionId = transactions.remove(request.transactionId)
            ?: return failure(GithubAuthorizationFailureCode.INVALID_STATE)
        if (request.handoffCode != "handoff-$connectionId") return forbidden()
        return authorized(request.projectId, connectionId)
    }

    override suspend fun listConnections(
        request: GithubBrokerConnectionsRequest,
    ): GithubBrokerResult<List<GithubAuthorizationSession>> =
        GithubBrokerResult.Success(
            sessionsByConnection.values.filter { request.projectId == null || it.projectId == request.projectId }
        )

    override suspend fun refreshSession(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<GithubAuthorizationSession> = authorized(request.projectId, request.airiConnectionId)

    override suspend fun revokeSession(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<Unit> {
        val authorization = authorized(request.projectId, request.airiConnectionId)
        if (authorization is GithubBrokerResult.Failure) {
            return GithubBrokerResult.Failure(authorization.failure)
        }
        sessionsByConnection.remove(request.airiConnectionId)
        return GithubBrokerResult.Success(Unit)
    }

    override suspend fun listRepositories(
        request: GithubBrokerSessionRequest,
    ): GithubBrokerResult<List<GithubRepositoryGrant>> =
        authorized(request.projectId, request.airiConnectionId).map { it.repositories }

    override suspend fun selectRepositories(
        request: GithubBrokerRepositorySelectionRequest,
    ): GithubBrokerResult<List<GithubRepositoryGrant>> {
        val session = authorized(request.projectId, request.airiConnectionId)
        if (session is GithubBrokerResult.Failure) return session
        val grants = (session as GithubBrokerResult.Success).value.repositories
        if (request.repositoryIds.any { id -> grants.none { it.repositoryId == id } }) return forbidden()
        return GithubBrokerResult.Success(grants.filter { it.repositoryId in request.repositoryIds })
    }

    private fun authorized(
        projectId: String,
        connectionId: String,
    ): GithubBrokerResult<GithubAuthorizationSession> {
        val session = sessionsByConnection[connectionId]
            ?: return failure(GithubAuthorizationFailureCode.HTTP_NOT_FOUND)
        return if (session.projectId == projectId) {
            GithubBrokerResult.Success(session)
        } else {
            forbidden()
        }
    }

    private fun <T> forbidden(): GithubBrokerResult<T> =
        failure(GithubAuthorizationFailureCode.HTTP_FORBIDDEN)

    private fun <T> failure(code: GithubAuthorizationFailureCode): GithubBrokerResult<T> =
        GithubBrokerResult.Failure(GithubAuthorizationFailure(code))

    private fun <T, R> GithubBrokerResult<T>.map(transform: (T) -> R): GithubBrokerResult<R> = when (this) {
        is GithubBrokerResult.Success -> GithubBrokerResult.Success(transform(value))
        is GithubBrokerResult.Failure -> this
    }
}
