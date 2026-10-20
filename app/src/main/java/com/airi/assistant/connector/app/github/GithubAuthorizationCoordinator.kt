package com.airi.assistant.connector.app.github

import com.airi.assistant.connector.oauth.OAuthStateRegistry
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * App-side authorization lifecycle. It never reads legacy PAT storage and
 * cannot mark the generic GitHub connector connected. The broker is injected;
 * without a configured adapter no OAuth operation or callback exchange proceeds.
 */
class GithubAuthorizationCoordinator(
    private val broker: GithubBrokerClient,
    private val sessionStore: GithubSessionStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class PendingBrokerTransaction(
        val transactionId: String,
        val expiresAtMs: Long,
    )

    private val transientStates = ConcurrentHashMap<String, GithubAuthorizationState>()
    private val brokerTransactions = ConcurrentHashMap<String, PendingBrokerTransaction>()

    suspend fun beginAuthorization(
        projectId: String,
        requestedScopes: Set<String> = emptySet(),
    ): GithubAuthorizationResult<GithubAuthorizationStart> {
        val project = GithubProjectIdPolicy.normalize(projectId)
            ?: return fail(projectId, GithubAuthorizationFailureCode.INVALID_PROJECT)
        if (broker.availability != GithubBrokerAvailability.CONFIGURED) {
            return fail(project, GithubAuthorizationFailureCode.BROKER_NOT_CONFIGURED)
        }
        val scopes = requestedScopes.map(String::trim).filter(String::isNotBlank).toSet()
        val pkce = OAuthStateRegistry.issuePkce(
            connectorId = GITHUB_CONNECTOR_ID,
            requestedScopes = scopes,
            ownerBinding = project,
        )
        transientStates[project] = GithubAuthorizationState.AwaitingAuthorization(nowMs())
        val brokerResult = try {
            broker.beginAuthorization(
                GithubBrokerAuthorizationRequest(
                    projectId = project,
                    clientNonce = pkce.state,
                    codeChallenge = pkce.codeChallenge,
                    requestedScopes = scopes,
                )
            )
        } catch (cancelled: CancellationException) {
            OAuthStateRegistry.consumeRequest(pkce.state)
            throw cancelled
        } catch (_: Exception) {
            OAuthStateRegistry.consumeRequest(pkce.state)
            return fail(project, GithubAuthorizationFailure(
                GithubAuthorizationFailureCode.NETWORK_ERROR, retryable = true
            ))
        }
        return when (brokerResult) {
            is GithubBrokerResult.Failure -> {
                OAuthStateRegistry.consumeRequest(pkce.state)
                fail(project, brokerResult.failure)
            }
            is GithubBrokerResult.Success -> {
                val brokerStart = brokerResult.value
                if (brokerStart.transactionId.isBlank() || brokerStart.expiresAtMs <= nowMs() ||
                    !isTrustedAuthorizationUrl(brokerStart.authorizationUrl)
                ) {
                    OAuthStateRegistry.consumeRequest(pkce.state)
                    fail(project, GithubAuthorizationFailureCode.INVALID_CALLBACK)
                } else {
                    GithubAuthorizationResult.Success(
                        GithubAuthorizationStart(
                            projectId = project,
                            state = pkce.state,
                            requestedScopes = scopes,
                            transactionId = brokerStart.transactionId,
                            authorizationUrl = brokerStart.authorizationUrl,
                            expiresAtMs = brokerStart.expiresAtMs,
                        ).also {
                            brokerTransactions[pkce.state] = PendingBrokerTransaction(
                                transactionId = it.transactionId,
                                expiresAtMs = it.expiresAtMs,
                            )
                        }
                    )
                }
            }
        }
    }

    suspend fun completeCallback(
        projectId: String,
        state: String,
        authorizationCode: String,
    ): GithubAuthorizationResult<GithubAuthorizationSession> {
        val request = state.takeIf(String::isNotBlank)?.let(OAuthStateRegistry::consumeRequest)
            ?: return fail(projectId, GithubAuthorizationFailureCode.INVALID_STATE)
        val transaction = brokerTransactions.remove(state)
            ?: return fail(projectId, GithubAuthorizationFailureCode.INVALID_STATE)
        val project = GithubProjectIdPolicy.normalize(projectId)
            ?: return fail(projectId, GithubAuthorizationFailureCode.INVALID_PROJECT)
        if (request.connectorId != GITHUB_CONNECTOR_ID) {
            return fail(project, GithubAuthorizationFailureCode.INVALID_STATE)
        }
        if (request.ownerBinding != project) {
            return fail(project, GithubAuthorizationFailureCode.PROJECT_MISMATCH)
        }
        if (transaction.expiresAtMs <= nowMs()) {
            return fail(project, GithubAuthorizationFailureCode.INVALID_STATE)
        }
        if (authorizationCode.isBlank() || request.codeVerifier.isNullOrBlank()) {
            return fail(project, GithubAuthorizationFailureCode.INVALID_CALLBACK)
        }
        if (broker.availability != GithubBrokerAvailability.CONFIGURED) {
            return fail(project, GithubAuthorizationFailureCode.BROKER_NOT_CONFIGURED)
        }
        transientStates[project] = GithubAuthorizationState.Exchanging(nowMs())
        val brokerResult = try {
            broker.exchangeAuthorizationCode(
                GithubBrokerCodeExchange(
                    projectId = project,
                    transactionId = transaction.transactionId,
                    handoffCode = authorizationCode,
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return fail(project, GithubAuthorizationFailure(
                GithubAuthorizationFailureCode.NETWORK_ERROR, retryable = true
            ))
        }
        return when (brokerResult) {
            is GithubBrokerResult.Failure -> fail(project, brokerResult.failure)
            is GithubBrokerResult.Success -> {
                val session = brokerResult.value
                if (session.projectId != project || !isValidSession(session)) {
                    fail(project, GithubAuthorizationFailureCode.PROJECT_MISMATCH)
                } else if (!session.grantedScopes.containsAll(request.requestedScopes) ||
                    !request.requestedScopes.containsAll(session.grantedScopes)
                ) {
                    fail(project, GithubAuthorizationFailureCode.INSUFFICIENT_PERMISSIONS)
                } else when (val stored = sessionStore.save(session)) {
                    GithubSessionWriteResult.Saved -> {
                        transientStates[project] = GithubAuthorizationState.Ready(session.summary())
                        GithubAuthorizationResult.Success(session)
                    }
                    is GithubSessionWriteResult.Failure -> fail(project, stored.failure)
                }
            }
        }
    }

    fun rejectCallback(projectId: String, state: String): GithubAuthorizationResult<Unit> {
        val request = state.takeIf(String::isNotBlank)?.let(OAuthStateRegistry::consumeRequest)
            ?: return fail(projectId, GithubAuthorizationFailureCode.INVALID_STATE)
        brokerTransactions.remove(state)
        val project = GithubProjectIdPolicy.normalize(projectId)
            ?: return fail(projectId, GithubAuthorizationFailureCode.INVALID_PROJECT)
        if (request.connectorId != GITHUB_CONNECTOR_ID) return fail(project, GithubAuthorizationFailureCode.INVALID_STATE)
        if (request.ownerBinding != project) return fail(project, GithubAuthorizationFailureCode.PROJECT_MISMATCH)
        return fail(project, GithubAuthorizationFailureCode.ACCESS_DENIED)
    }

    fun cancelAuthorization(projectId: String, state: String): GithubAuthorizationResult<Unit> {
        val request = state.takeIf(String::isNotBlank)?.let(OAuthStateRegistry::consumeRequest)
            ?: return fail(projectId, GithubAuthorizationFailureCode.INVALID_STATE)
        brokerTransactions.remove(state)
        val project = GithubProjectIdPolicy.normalize(projectId)
            ?: return fail(projectId, GithubAuthorizationFailureCode.INVALID_PROJECT)
        if (request.connectorId != GITHUB_CONNECTOR_ID) return fail(project, GithubAuthorizationFailureCode.INVALID_STATE)
        if (request.ownerBinding != project) return fail(project, GithubAuthorizationFailureCode.PROJECT_MISMATCH)
        transientStates[project] = GithubAuthorizationState.Cancelled
        return GithubAuthorizationResult.Success(Unit)
    }

    suspend fun refreshSession(projectId: String): GithubAuthorizationResult<GithubAuthorizationSession> {
        val project = GithubProjectIdPolicy.normalize(projectId)
            ?: return fail(projectId, GithubAuthorizationFailureCode.INVALID_PROJECT)
        val session = when (val loaded = sessionStore.load(project)) {
            GithubSessionReadResult.Missing -> return fail(project, GithubAuthorizationFailureCode.SESSION_EXPIRED)
            is GithubSessionReadResult.Failure -> return fail(project, loaded.failure)
            is GithubSessionReadResult.Found -> loaded.session
        }
        if (!session.isExpired(nowMs())) return GithubAuthorizationResult.Success(session)
        if (session.airiConnectionId.isBlank()) return fail(project, GithubAuthorizationFailureCode.SESSION_EXPIRED)
        if (broker.availability != GithubBrokerAvailability.CONFIGURED) {
            return fail(project, GithubAuthorizationFailureCode.BROKER_NOT_CONFIGURED)
        }
        val brokerResult = try {
            broker.refreshSession(GithubBrokerSessionRequest(project, session.airiConnectionId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return fail(project, GithubAuthorizationFailure(
                GithubAuthorizationFailureCode.NETWORK_ERROR, retryable = true
            ))
        }
        return when (brokerResult) {
            is GithubBrokerResult.Failure -> fail(project, brokerResult.failure)
            is GithubBrokerResult.Success -> {
                val refreshed = brokerResult.value
                if (refreshed.projectId != project || !isValidSession(refreshed)) {
                    fail(project, GithubAuthorizationFailureCode.PROJECT_MISMATCH)
                } else if (!isNonExpandingRefresh(session, refreshed)) {
                    fail(project, GithubAuthorizationFailureCode.INSUFFICIENT_PERMISSIONS)
                } else when (val stored = sessionStore.save(refreshed)) {
                    GithubSessionWriteResult.Saved -> {
                        transientStates[project] = GithubAuthorizationState.Ready(refreshed.summary())
                        GithubAuthorizationResult.Success(refreshed)
                    }
                    is GithubSessionWriteResult.Failure -> fail(project, stored.failure)
                }
            }
        }
    }

    /** Clears local credentials even when remote revoke is unavailable or fails. */
    suspend fun revokeSession(projectId: String): GithubAuthorizationResult<GithubRevocationResult> {
        val project = GithubProjectIdPolicy.normalize(projectId)
            ?: return fail(projectId, GithubAuthorizationFailureCode.INVALID_PROJECT)
        val session = when (val loaded = sessionStore.load(project)) {
            GithubSessionReadResult.Missing -> null
            is GithubSessionReadResult.Failure -> return fail(project, loaded.failure)
            is GithubSessionReadResult.Found -> loaded.session
        }
        if (session != null && !sessionStore.clear(project)) {
            return fail(project, GithubAuthorizationFailureCode.SECURE_STORAGE_UNAVAILABLE)
        }
        val remoteResult = if (session == null) null else if (
            broker.availability != GithubBrokerAvailability.CONFIGURED
        ) {
            GithubBrokerResult.Failure(
                GithubAuthorizationFailure(GithubAuthorizationFailureCode.BROKER_NOT_CONFIGURED)
            )
        } else try {
            broker.revokeSession(GithubBrokerSessionRequest(project, session.airiConnectionId))
        } catch (cancelled: CancellationException) {
            transientStates[project] = GithubAuthorizationState.Revoked(remoteRevocationConfirmed = false)
            throw cancelled
        } catch (_: Exception) {
            GithubBrokerResult.Failure(
                GithubAuthorizationFailure(GithubAuthorizationFailureCode.NETWORK_ERROR, retryable = true)
            )
        }
        val remoteConfirmed = remoteResult is GithubBrokerResult.Success
        val remoteFailure = (remoteResult as? GithubBrokerResult.Failure)?.failure
        transientStates[project] = GithubAuthorizationState.Revoked(remoteConfirmed)
        return GithubAuthorizationResult.Success(
            GithubRevocationResult(
                localSessionRemoved = true,
                remoteRevocationConfirmed = remoteConfirmed,
                remoteFailure = remoteFailure,
            )
        )
    }

    suspend fun listRepositories(projectId: String): GithubAuthorizationResult<List<GithubRepositoryGrant>> {
        val project = GithubProjectIdPolicy.normalize(projectId)
            ?: return fail(projectId, GithubAuthorizationFailureCode.INVALID_PROJECT)
        val session = when (val loaded = sessionStore.load(project)) {
            GithubSessionReadResult.Missing -> return fail(project, GithubAuthorizationFailureCode.SESSION_EXPIRED)
            is GithubSessionReadResult.Failure -> return fail(project, loaded.failure)
            is GithubSessionReadResult.Found -> loaded.session
        }
        if (session.isExpired(nowMs())) return fail(project, GithubAuthorizationFailureCode.SESSION_EXPIRED)
        if (broker.availability != GithubBrokerAvailability.CONFIGURED) {
            return fail(project, GithubAuthorizationFailureCode.BROKER_NOT_CONFIGURED)
        }
        val result = try {
            broker.listRepositories(GithubBrokerSessionRequest(project, session.airiConnectionId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return fail(project, GithubAuthorizationFailure(
                GithubAuthorizationFailureCode.NETWORK_ERROR, retryable = true
            ))
        }
        return when (result) {
            is GithubBrokerResult.Failure -> fail(project, result.failure)
            is GithubBrokerResult.Success -> {
                if (!isValidRepositoryGrants(result.value)) {
                    fail(project, GithubAuthorizationFailureCode.INVALID_CALLBACK)
                } else {
                    GithubAuthorizationResult.Success(result.value)
                }
            }
        }
    }

    suspend fun selectRepositories(
        projectId: String,
        repositoryIds: Set<Long>,
    ): GithubAuthorizationResult<List<GithubRepositoryGrant>> {
        val project = GithubProjectIdPolicy.normalize(projectId)
            ?: return fail(projectId, GithubAuthorizationFailureCode.INVALID_PROJECT)
        if (repositoryIds.any { it <= 0L }) return fail(project, GithubAuthorizationFailureCode.INVALID_CALLBACK)
        val session = when (val loaded = sessionStore.load(project)) {
            GithubSessionReadResult.Missing -> return fail(project, GithubAuthorizationFailureCode.SESSION_EXPIRED)
            is GithubSessionReadResult.Failure -> return fail(project, loaded.failure)
            is GithubSessionReadResult.Found -> loaded.session
        }
        if (session.isExpired(nowMs())) return fail(project, GithubAuthorizationFailureCode.SESSION_EXPIRED)
        if (broker.availability != GithubBrokerAvailability.CONFIGURED) {
            return fail(project, GithubAuthorizationFailureCode.BROKER_NOT_CONFIGURED)
        }
        val result = try {
            broker.selectRepositories(
                GithubBrokerRepositorySelectionRequest(project, session.airiConnectionId, repositoryIds)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return fail(project, GithubAuthorizationFailureCode.NETWORK_ERROR, retryable = true)
        }
        return when (result) {
            is GithubBrokerResult.Failure -> fail(project, result.failure)
            is GithubBrokerResult.Success -> {
                val returnedIds = result.value.map { it.repositoryId }.toSet()
                if (!isValidRepositoryGrants(result.value) || returnedIds != repositoryIds) {
                    fail(project, GithubAuthorizationFailureCode.INSUFFICIENT_PERMISSIONS)
                } else when (val saved = sessionStore.save(session.copy(repositories = result.value))) {
                    GithubSessionWriteResult.Saved -> GithubAuthorizationResult.Success(result.value)
                    is GithubSessionWriteResult.Failure -> fail(project, saved.failure)
                }
            }
        }
    }

    suspend fun listConnections(
        projectId: String? = null,
    ): GithubAuthorizationResult<List<GithubAuthorizationSession>> {
        val normalizedProject = projectId?.let {
            GithubProjectIdPolicy.normalize(it)
                ?: return fail(it, GithubAuthorizationFailureCode.INVALID_PROJECT)
        }
        if (broker.availability != GithubBrokerAvailability.CONFIGURED) {
            return fail(normalizedProject.orEmpty(), GithubAuthorizationFailureCode.BROKER_NOT_CONFIGURED)
        }
        val result = try {
            broker.listConnections(GithubBrokerConnectionsRequest(normalizedProject))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return fail(normalizedProject.orEmpty(), GithubAuthorizationFailureCode.NETWORK_ERROR, retryable = true)
        }
        return when (result) {
            is GithubBrokerResult.Failure -> GithubAuthorizationResult.Failure(result.failure)
            is GithubBrokerResult.Success -> if (result.value.any { session ->
                GithubProjectIdPolicy.normalize(session.projectId) != session.projectId ||
                    (normalizedProject != null && session.projectId != normalizedProject) || !isValidSession(session)
            } || result.value.map { it.airiConnectionId }.toSet().size != result.value.size) {
                GithubAuthorizationResult.Failure(
                    GithubAuthorizationFailure(GithubAuthorizationFailureCode.INVALID_CALLBACK)
                )
            } else GithubAuthorizationResult.Success(result.value)
        }
    }

    fun stateFor(projectId: String): GithubAuthorizationState {
        val project = GithubProjectIdPolicy.normalize(projectId)
            ?: return GithubAuthorizationState.Failed(
                GithubAuthorizationFailure(GithubAuthorizationFailureCode.INVALID_PROJECT)
            )
        return when (val loaded = sessionStore.load(project)) {
            GithubSessionReadResult.Missing -> transientStates[project] ?: GithubAuthorizationState.SignedOut
            is GithubSessionReadResult.Failure -> GithubAuthorizationState.Failed(loaded.failure)
            is GithubSessionReadResult.Found -> {
                val transientFailure = (transientStates[project] as? GithubAuthorizationState.Failed)?.failure
                if (loaded.session.isExpired(nowMs()) || transientFailure?.code in REAUTHORIZATION_FAILURES) {
                    GithubAuthorizationState.Expired(loaded.session.summary())
                } else {
                    GithubAuthorizationState.Ready(loaded.session.summary())
                }
            }
        }
    }

    private fun isValidSession(session: GithubAuthorizationSession): Boolean =
        GithubProjectIdPolicy.normalize(session.projectId) == session.projectId &&
            session.airiConnectionId.isNotBlank() && session.githubAccountId > 0L &&
            session.accountLogin.isNotBlank() &&
            session.expiresAtMs?.let { it > nowMs() } != false &&
            session.grantedScopes.none(String::isBlank) && isValidRepositoryGrants(session.repositories)

    private fun isValidRepositoryGrant(grant: GithubRepositoryGrant): Boolean =
        grant.repositoryId > 0L && grant.owner.isNotBlank() && grant.name.isNotBlank()

    private fun isValidRepositoryGrants(grants: List<GithubRepositoryGrant>): Boolean =
        grants.all(::isValidRepositoryGrant) && grants.map { it.repositoryId }.toSet().size == grants.size

    /** A refresh may narrow authority but cannot silently switch identities or widen grants. */
    private fun isNonExpandingRefresh(
        previous: GithubAuthorizationSession,
        refreshed: GithubAuthorizationSession,
    ): Boolean {
        if (refreshed.airiConnectionId != previous.airiConnectionId ||
            refreshed.githubAccountId != previous.githubAccountId ||
            !previous.grantedScopes.containsAll(refreshed.grantedScopes)
        ) return false
        val oldGrants = previous.repositories.associateBy { it.repositoryId }
        return refreshed.repositories.all { grant ->
            val old = oldGrants[grant.repositoryId] ?: return@all false
            grant.permissions.all { (permission, level) ->
                level.ordinal <= (old.permissions[permission] ?: GithubRepositoryPermission.NONE).ordinal
            }
        }
    }

    private fun isTrustedAuthorizationUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            (uri.host.equals("github.com", ignoreCase = true) ||
                uri.host.equals("www.github.com", ignoreCase = true)) &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)

    private fun <T> fail(
        projectId: String,
        code: GithubAuthorizationFailureCode,
        retryable: Boolean = false,
    ): GithubAuthorizationResult<T> = fail(projectId, GithubAuthorizationFailure(code, retryable = retryable))

    private fun <T> fail(
        projectId: String,
        failure: GithubAuthorizationFailure,
    ): GithubAuthorizationResult<T> {
        GithubProjectIdPolicy.normalize(projectId)?.let { transientStates[it] = GithubAuthorizationState.Failed(failure) }
        return GithubAuthorizationResult.Failure(failure)
    }

    private companion object {
        const val GITHUB_CONNECTOR_ID = "github"
        val REAUTHORIZATION_FAILURES = setOf(
            GithubAuthorizationFailureCode.SESSION_EXPIRED,
            GithubAuthorizationFailureCode.SESSION_REVOKED,
            GithubAuthorizationFailureCode.HTTP_UNAUTHORIZED,
        )
    }
}
