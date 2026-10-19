package com.airi.assistant.connector.app.github

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiTenantGithubFakeBrokerTest {
    @Test
    fun authenticatedUserSeesOnlyOwnConnectionsAndRepositories() = runBlocking {
        val userA = session("user-a", "project-a", "connection-a", 101L, "repo-a", 11L)
        val userB = session("user-b", "project-b", "connection-b", 202L, "repo-b", 22L)
        val brokerA = MultiTenantGithubFakeBroker("user-a", listOf(userA))
        val brokerB = MultiTenantGithubFakeBroker("user-b", listOf(userB))

        val connectionsA = brokerA.listConnections(GithubBrokerConnectionsRequest()).successValue()
        assertEquals(listOf("connection-a"), connectionsA.map { it.airiConnectionId })
        assertEquals(listOf("repo-a"), brokerA.listRepositories(sessionRequest(userA)).successValue().map { it.name })
        assertEquals(listOf("connection-b"), brokerB.listConnections(GithubBrokerConnectionsRequest()).successValue().map { it.airiConnectionId })
    }

    @Test
    fun foreignConnectionAndProjectChangesAreRejected() = runBlocking {
        val userA = session("user-a", "project-a", "connection-a", 101L, "repo-a", 11L)
        val brokerA = MultiTenantGithubFakeBroker("user-a", listOf(userA))

        assertEquals(
            GithubAuthorizationFailureCode.HTTP_NOT_FOUND,
            (brokerA.listRepositories(GithubBrokerSessionRequest("project-a", "connection-b")) as GithubBrokerResult.Failure).failure.code,
        )
        assertEquals(
            GithubAuthorizationFailureCode.HTTP_FORBIDDEN,
            (brokerA.listRepositories(GithubBrokerSessionRequest("project-b", "connection-a")) as GithubBrokerResult.Failure).failure.code,
        )
    }

    @Test
    fun repositoryIdCannotExpandTheInstallationGrant() = runBlocking {
        val userA = session("user-a", "project-a", "connection-a", 101L, "repo-a", 11L)
        val brokerA = MultiTenantGithubFakeBroker("user-a", listOf(userA))
        val result = brokerA.selectRepositories(
            GithubBrokerRepositorySelectionRequest("project-a", "connection-a", setOf(11L, 999L))
        )
        assertEquals(GithubAuthorizationFailureCode.HTTP_FORBIDDEN, (result as GithubBrokerResult.Failure).failure.code)
    }

    @Test
    fun handoffIsSingleUseAndRevocationStopsFurtherAccess() = runBlocking {
        val userA = session("user-a", "project-a", "connection-a", 101L, "repo-a", 11L)
        val brokerA = MultiTenantGithubFakeBroker("user-a", listOf(userA))
        val start = brokerA.beginAuthorization(
            GithubBrokerAuthorizationRequest("project-a", "client-nonce", "challenge", setOf("repo:read"))
        ).successValue()
        val exchanged = brokerA.exchangeAuthorizationCode(
            GithubBrokerCodeExchange("project-a", start.transactionId, "handoff-connection-a")
        ).successValue()
        assertEquals("connection-a", exchanged.airiConnectionId)
        assertTrue(brokerA.exchangeAuthorizationCode(
            GithubBrokerCodeExchange("project-a", start.transactionId, "handoff-connection-a")
        ) is GithubBrokerResult.Failure)

        assertTrue(brokerA.revokeSession(sessionRequest(userA)) is GithubBrokerResult.Success)
        assertEquals(
            GithubAuthorizationFailureCode.HTTP_NOT_FOUND,
            (brokerA.listRepositories(sessionRequest(userA)) as GithubBrokerResult.Failure).failure.code,
        )
    }

    private fun session(
        userId: String,
        projectId: String,
        connectionId: String,
        accountId: Long,
        repositoryName: String,
        repositoryId: Long,
    ) = GithubAuthorizationSession(
        airiConnectionId = connectionId,
        projectId = projectId,
        githubAccountId = accountId,
        accountLogin = userId,
        expiresAtMs = 90_000L,
        grantedScopes = setOf("repo:read"),
        repositories = listOf(
            GithubRepositoryGrant(
                repositoryId = repositoryId,
                owner = "owner-$userId",
                name = repositoryName,
                permissions = mapOf("contents" to GithubRepositoryPermission.READ),
            )
        ),
        createdAtMs = 1L,
    )

    private fun sessionRequest(session: GithubAuthorizationSession) =
        GithubBrokerSessionRequest(session.projectId, session.airiConnectionId)

    private fun <T> GithubBrokerResult<T>.successValue(): T =
        (this as GithubBrokerResult.Success).value
}
