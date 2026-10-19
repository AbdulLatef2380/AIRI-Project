package com.airi.assistant.connector.app.github

import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.app.GitHubConnector
import com.airi.assistant.connector.app.GitHubMutationPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubAuthorizationCoordinatorTest {
    @Test
    fun missingBrokerIsExplicitAndDoesNotUseLegacyPat() = runBlocking {
        val store = GithubSessionStore(FakeCredentialStore())
        val coordinator = GithubAuthorizationCoordinator(NotConfiguredGithubBrokerClient(), store)

        val result = coordinator.beginAuthorization("project-one", setOf("repo:read"))
        assertEquals(
            GithubAuthorizationFailureCode.BROKER_NOT_CONFIGURED,
            (result as GithubAuthorizationResult.Failure).failure.code,
        )
        assertEquals(GithubSessionReadResult.Missing, store.load("project-one"))
        assertEquals(
            GithubAuthorizationFailureCode.BROKER_NOT_CONFIGURED,
            (coordinator.stateFor("project-one") as GithubAuthorizationState.Failed).failure.code,
        )
    }

    @Test
    fun successfulCallbackStoresOnlyTheMatchingProjectSessionAndRejectsReplay() = runBlocking {
        val broker = FakeBroker()
        val store = GithubSessionStore(FakeCredentialStore())
        val coordinator = GithubAuthorizationCoordinator(broker, store, nowMs = { 10_000L })
        broker.exchangeSession = session("project-one", setOf("repo:read"), expiresAt = 90_000L)

        val start = (coordinator.beginAuthorization("project-one", setOf("repo:read"))
            as GithubAuthorizationResult.Success).value
        val completed = coordinator.completeCallback("project-one", start.state, "code-fixture")

        assertTrue(completed is GithubAuthorizationResult.Success)
        assertEquals("project-one", (store.load("project-one") as GithubSessionReadResult.Found).session.projectId)
        assertTrue(coordinator.stateFor("project-one") is GithubAuthorizationState.Ready)
        val replay = coordinator.completeCallback("project-one", start.state, "code-fixture")
        assertEquals(GithubAuthorizationFailureCode.INVALID_STATE, (replay as GithubAuthorizationResult.Failure).failure.code)
    }

    @Test
    fun stateCannotMoveAuthorizationBetweenProjectsAndWrongAttemptConsumesIt() = runBlocking {
        val broker = FakeBroker()
        val coordinator = GithubAuthorizationCoordinator(broker, GithubSessionStore(FakeCredentialStore()))
        val start = (coordinator.beginAuthorization("project-one", setOf("repo:read"))
            as GithubAuthorizationResult.Success).value

        val wrongProject = coordinator.completeCallback("project-two", start.state, "code-fixture")
        assertEquals(GithubAuthorizationFailureCode.PROJECT_MISMATCH, (wrongProject as GithubAuthorizationResult.Failure).failure.code)
        val replayInOwnerProject = coordinator.completeCallback("project-one", start.state, "code-fixture")
        assertEquals(GithubAuthorizationFailureCode.INVALID_STATE, (replayInOwnerProject as GithubAuthorizationResult.Failure).failure.code)
        assertEquals(0, broker.exchangeCalls)
    }

    @Test
    fun cancellationAndProviderDenialAreDistinctTypedStates() = runBlocking {
        val coordinator = GithubAuthorizationCoordinator(FakeBroker(), GithubSessionStore(FakeCredentialStore()))
        val cancelStart = (coordinator.beginAuthorization("cancel-project") as GithubAuthorizationResult.Success).value
        assertTrue(coordinator.cancelAuthorization("cancel-project", cancelStart.state) is GithubAuthorizationResult.Success)
        assertTrue(coordinator.stateFor("cancel-project") is GithubAuthorizationState.Cancelled)

        val denyStart = (coordinator.beginAuthorization("deny-project") as GithubAuthorizationResult.Success).value
        val denied = coordinator.rejectCallback("deny-project", denyStart.state)
        assertEquals(GithubAuthorizationFailureCode.ACCESS_DENIED, (denied as GithubAuthorizationResult.Failure).failure.code)
    }

    @Test
    fun missingGrantedScopePreventsPersistingSession() = runBlocking {
        val broker = FakeBroker().apply {
            exchangeSession = session("project-one", emptySet(), expiresAt = 90_000L)
        }
        val store = GithubSessionStore(FakeCredentialStore())
        val coordinator = GithubAuthorizationCoordinator(broker, store, nowMs = { 10_000L })
        val start = (coordinator.beginAuthorization("project-one", setOf("repo:read"))
            as GithubAuthorizationResult.Success).value

        val result = coordinator.completeCallback("project-one", start.state, "code-fixture")
        assertEquals(
            GithubAuthorizationFailureCode.INSUFFICIENT_PERMISSIONS,
            (result as GithubAuthorizationResult.Failure).failure.code,
        )
        assertEquals(GithubSessionReadResult.Missing, store.load("project-one"))
    }

    @Test
    fun expiredSessionRefreshesOnlyThroughBrokerAndRevocationAlwaysClearsLocally() = runBlocking {
        val credentials = FakeCredentialStore()
        val store = GithubSessionStore(credentials)
        store.save(session("project-one", setOf("repo:read"), expiresAt = 9_000L))
        val broker = FakeBroker()
        val refreshed = session("project-one", setOf("repo:read"), expiresAt = 50_000L)
        broker.exchangeSession = refreshed
        broker.refreshSessionResult = GithubBrokerResult.Success(refreshed)
        val coordinator = GithubAuthorizationCoordinator(broker, store, nowMs = { 10_000L })

        assertTrue(coordinator.stateFor("project-one") is GithubAuthorizationState.Expired)
        assertTrue(coordinator.refreshSession("project-one") is GithubAuthorizationResult.Success)
        assertTrue(coordinator.stateFor("project-one") is GithubAuthorizationState.Ready)

        broker.availability = GithubBrokerAvailability.BROKER_NOT_CONFIGURED
        val revoked = (coordinator.revokeSession("project-one") as GithubAuthorizationResult.Success).value
        assertTrue(revoked.localSessionRemoved)
        assertFalse(revoked.remoteRevocationConfirmed)
        assertEquals(GithubAuthorizationFailureCode.BROKER_NOT_CONFIGURED, revoked.remoteFailure?.code)
        assertEquals(GithubSessionReadResult.Missing, store.load("project-one"))
        assertEquals(false, (coordinator.stateFor("project-one") as GithubAuthorizationState.Revoked).remoteRevocationConfirmed)
    }

    @Test
    fun httpFailuresHaveTypedSafeClassification() {
        assertEquals(GithubAuthorizationFailureCode.HTTP_UNAUTHORIZED, GithubAuthorizationFailure.fromHttpStatus(401).code)
        assertEquals(GithubAuthorizationFailureCode.HTTP_FORBIDDEN, GithubAuthorizationFailure.fromHttpStatus(403).code)
        assertEquals(GithubAuthorizationFailureCode.HTTP_NOT_FOUND, GithubAuthorizationFailure.fromHttpStatus(404).code)
        assertEquals(GithubAuthorizationFailureCode.HTTP_CONFLICT, GithubAuthorizationFailure.fromHttpStatus(409).code)
        assertEquals(GithubAuthorizationFailureCode.HTTP_INVALID_REQUEST, GithubAuthorizationFailure.fromHttpStatus(422).code)
        assertTrue(GithubAuthorizationFailure.fromHttpStatus(429).retryable)
        assertTrue(GithubAuthorizationFailure.fromHttpStatus(503).retryable)
    }

    @Test
    fun mutationPolicyStillRequiresApprovalForEveryDeclaredWrite() {
        listOf("create_issue", "comment_issue", "update_issue", "create_pull_request", "push").forEach { action ->
            assertTrue(
                GitHubMutationPolicy.evaluate(action) is GitHubMutationPolicy.Decision.RequiresTaskApproval
            )
        }
    }

    @Test
    fun githubWriteWithoutTaskApprovalNeverReachesAdapter() = runBlocking {
        var adapterCalls = 0
        val connector = GitHubConnector(
            legacyCredentialProvider = { _, _ -> "legacy-pat-fixture" },
            tokenExecutor = { _, _ ->
                adapterCalls++
                ConnectorOutput.Success("unexpected")
            },
        )

        val result = connector.execute(
            ConnectorInput(
                action = "create_issue",
                params = mapOf("repo" to "owner/repo", "title" to "test"),
                authorizationActionId = "create_issue",
            )
        )

        assertEquals("approval_context_required", (result as ConnectorOutput.Failure).code)
        assertEquals(0, adapterCalls)
    }

    private fun session(projectId: String, scopes: Set<String>, expiresAt: Long) = GithubAuthorizationSession(
        projectId = projectId,
        accountLogin = "octocat",
        accessToken = "oauth-access-fixture",
        refreshToken = "oauth-refresh-fixture",
        expiresAtMs = expiresAt,
        grantedScopes = scopes,
        installationId = 123L,
        repositories = listOf(
            GithubRepositoryGrant(1L, "owner", "repo", mapOf("contents" to GithubRepositoryPermission.READ))
        ),
        createdAtMs = 1L,
    )

    private class FakeCredentialStore(override val isAvailable: Boolean = true) : GithubSessionCredentialStore {
        private val values = mutableMapOf<String, String>()
        override fun read(namespace: String): String? = if (isAvailable) values[namespace] else null
        override fun write(namespace: String, value: String): Boolean {
            if (!isAvailable) return false
            values[namespace] = value
            return true
        }
        override fun remove(namespace: String): Boolean = isAvailable && values.remove(namespace) != null
    }

    private class FakeBroker : GithubBrokerClient {
        override var availability: GithubBrokerAvailability = GithubBrokerAvailability.CONFIGURED
        var exchangeCalls: Int = 0
        var exchangeSession: GithubAuthorizationSession? = null
        var refreshSessionResult: GithubBrokerResult<GithubAuthorizationSession>? = null

        override suspend fun beginAuthorization(request: GithubBrokerAuthorizationRequest): GithubBrokerResult<Unit> =
            GithubBrokerResult.Success(Unit)

        override suspend fun exchangeAuthorizationCode(request: GithubBrokerCodeExchange): GithubBrokerResult<GithubAuthorizationSession> {
            exchangeCalls++
            return exchangeSession?.let { GithubBrokerResult.Success(it) }
                ?: GithubBrokerResult.Failure(GithubAuthorizationFailure(GithubAuthorizationFailureCode.NETWORK_ERROR))
        }

        override suspend fun refreshSession(request: GithubBrokerSessionRequest): GithubBrokerResult<GithubAuthorizationSession> =
            refreshSessionResult ?: GithubBrokerResult.Failure(
                GithubAuthorizationFailure(GithubAuthorizationFailureCode.NETWORK_ERROR, retryable = true)
            )

        override suspend fun revokeSession(request: GithubBrokerSessionRequest): GithubBrokerResult<Unit> =
            GithubBrokerResult.Success(Unit)

        override suspend fun listRepositories(request: GithubBrokerSessionRequest): GithubBrokerResult<List<GithubRepositoryGrant>> =
            GithubBrokerResult.Success(request.session.repositories)
    }
}
