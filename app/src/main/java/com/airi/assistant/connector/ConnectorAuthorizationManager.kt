package com.airi.assistant.connector

import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.connector.app.GitHubConnector
import com.airi.assistant.connector.app.GoogleConnector
import com.airi.assistant.connector.app.MicrosoftGraphConnector
import com.airi.assistant.connector.app.DropboxConnector
import com.airi.assistant.connector.app.BoxConnector
import com.airi.assistant.connector.app.ProviderTokenConnector
import com.airi.assistant.connector.app.ZapierConnector
import com.airi.assistant.connector.mcp.NotionMcpConnector
import com.airi.assistant.connector.mcp.saveNotionToken
import com.airi.assistant.connector.mcp.getNotionToken
import com.airi.assistant.connector.oauth.OAuthStateRegistry
import com.airi.assistant.connector.oauth.MicrosoftOAuthConfiguration
import com.airi.assistant.connector.oauth.OAuthConfiguration
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.release.ReleaseScopePolicy
import com.airi.assistant.integrations.google.GoogleAuthService
import com.airi.assistant.integrations.google.GoogleDataAuthorization
import com.airi.assistant.integrations.google.GoogleDataScopes
import com.airi.assistant.integrations.google.GoogleIdentityCredential
import com.airi.assistant.integrations.telegram.TelegramService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single orchestration boundary for connector authorization.
 *
 * This class deliberately does not collect secrets from an arbitrary caller or
 * put provider credentials in logs. Provider adapters validate credentials,
 * the manager invokes the real connector health check, and only then does the
 * registry expose a connected runtime.
 */
class ConnectorAuthorizationManager(
    private val registry: ConnectorRegistry,
    private val authManager: ConnectorAuthManager,
    private val secureStorage: SecureStorage,
    private val googleAuthService: GoogleAuthService,
    private val accessProfileStore: ConnectorAccessProfileStore = InMemoryConnectorAccessProfileStore(),
    private val telegramService: TelegramService = TelegramService(secureStorage),
) {
    sealed interface StartResult {
        data class OAuthBrowser(val connectorId: String, val url: String) : StartResult
        data class GoogleIdentity(val connectorId: String) : StartResult
        data class GoogleConsent(val connectorId: String, val scopes: Set<String>) : StartResult
        data class AccessProfileRequired(
            val connectorId: String,
            val runtimeId: String,
            val surfaceIds: List<String>,
        ) : StartResult
        data class CredentialRequired(
            val connectorId: String,
            val label: String,
            val provider: String,
        ) : StartResult
        data class Ready(val connectorId: String, val state: ConnectorState) : StartResult
        data class Failed(val code: String, val message: String, val retryable: Boolean = false) : StartResult
    }

    sealed interface CompletionResult {
        data class Ready(val connectorId: String, val state: ConnectorState) : CompletionResult
        data class ConsentRequired(val connectorId: String, val scopes: Set<String> = emptySet()) : CompletionResult
        data class Failed(val code: String, val message: String, val retryable: Boolean = false) : CompletionResult
    }

    /** Starts the provider-appropriate flow for a catalog or runtime id. */
    suspend fun begin(id: String): StartResult = withContext<StartResult>(Dispatchers.IO) {
        val runtimeId = resolveRuntimeId(id)
        if (isReleaseBlocked(runtimeId)) {
            return@withContext StartResult.Failed(
                "integration_unavailable",
                "External automation integrations are unavailable in this release."
            )
        }
        val catalog = registry.catalogMeta()
        val meta = catalog.firstOrNull { it.id == id }
            ?: catalog.firstOrNull { it.runtimeId == runtimeId }
            ?: registry.get(runtimeId)?.meta()
            ?: return@withContext StartResult.Failed("not_found", "Connector '$id' is not registered")
        val strategy = ConnectorAuthStrategies.forMeta(meta)
        ConnectorRolloutRegistry.get(id)?.takeUnless { it.canStartAuthorization }?.let { rollout ->
            return@withContext StartResult.Failed(
                "configuration_required",
                rollout.blockedReason ?: "Provider configuration is required before authorization can start."
            )
        }
        if (!strategy.isExecutable) {
            val rollout = ConnectorRolloutRegistry.get(id)
            val adapterContract = RemainingProviderAdapterContracts.get(id)
            val detail = rollout?.let {
                buildString {
                    append(it.blockedReason)
                    append(" Rollout batch: ${it.batch.name}; required adapter: ${it.requiredAdapter}.")
                    adapterContract?.let { contract ->
                        append(" Auth: ${contract.authMode}; required scopes: ${contract.requiredScopes.joinToString(", ")}; health: ${contract.healthEndpoint}.")
                    }
                }
            } ?: strategy.summary
            return@withContext StartResult.Failed("adapter_not_installed", detail)
        }
        // The global Google card signs in the identity only. Data authorization is always per catalog surface.
        if (runtimeId == "google" && id == "google") return@withContext beginGoogle("google")
        val surfaceIds = authorizationSurfaceIds(id, runtimeId)
        if (surfaceIds.any { accessProfileStore.get(it) == ConnectorAccessProfile.NOT_CONFIGURED }) {
            return@withContext StartResult.AccessProfileRequired(
                connectorId = id,
                runtimeId = runtimeId,
                surfaceIds = surfaceIds,
            )
        }
        if (!authManager.isSecureStorageAvailable || !secureStorage.isEncrypted) {
            return@withContext StartResult.Failed(
                "secure_storage_unavailable",
                "Encrypted credential and authorization storage is unavailable; connector was not connected."
            )
        }
        when (runtimeId) {
            "google" -> beginGoogle(surfaceIds.firstOrNull() ?: id)
            "microsoft_graph" -> beginMicrosoft(runtimeId)
            "dropbox" -> beginDropbox(runtimeId)
            "box" -> beginBox(runtimeId)
            "zapier" -> beginZapier(runtimeId)
            "github" -> StartResult.CredentialRequired(runtimeId, "GitHub personal access token", "GitHub")
            "telegram" -> StartResult.CredentialRequired(runtimeId, "Telegram bot token", "Telegram")
            "notion_mcp" -> StartResult.CredentialRequired(runtimeId, "Notion integration token", "Notion")
            in ProviderTokenConnector.configs().map { it.id } -> {
                val config = ProviderTokenConnector.configs().first { it.id == runtimeId }
                StartResult.CredentialRequired(runtimeId, config.credentialLabel, config.provider)
            }
            else -> connectAndVerify(runtimeId).let { state ->
                if (state.connected && state.healthy) {
                    StartResult.Ready(runtimeId, state)
                } else {
                    StartResult.Failed(
                        "health_check_failed",
                        state.errorMessage ?: state.statusLine.ifBlank { "Connector health check failed" },
                        retryable = true,
                    )
                }
            }
        }
    }

    fun requiredOAuthScopesFor(id: String): Set<String> = ConnectorOAuthScopeResolver.requiredScopes(
        registry = registry,
        profiles = accessProfileStore,
        runtimeId = resolveRuntimeId(id),
        targetSurfaceId = registry.catalogMeta().firstOrNull { it.id == id }?.id,
    )

    private fun isDataAccessAuthorizedFor(surfaceId: String, scopes: Set<String>): Boolean =
        surfaceId in GoogleDataScopes.surfaceIds &&
            googleAuthService.hasAuthorizedDataScopes(surfaceId, scopes)

    private fun isCurrentGoogleProfileAuthorized(surfaceId: String, scopes: Set<String>): Boolean {
        val currentProfileScopes = requiredOAuthScopesFor(surfaceId)
        return currentProfileScopes == scopes && isDataAccessAuthorizedFor(surfaceId, scopes)
    }

    private fun beginGoogle(surfaceId: String): StartResult {
        if (surfaceId == "google") {
            return if (googleAuthService.getLastSignedInEmail().isNullOrBlank()) {
                StartResult.GoogleIdentity("google")
            } else {
                StartResult.Ready("google", ConnectorState(true, true, "Google identity signed in"))
            }
        }
        val scopes = requiredOAuthScopesFor(surfaceId)
        if (scopes.isEmpty()) {
            return StartResult.Failed("access_profile_required", "Choose an access profile that permits an action on this Google surface.")
        }
        val email = googleAuthService.getLastSignedInEmail()
        return if (email.isNullOrBlank()) StartResult.GoogleIdentity(surfaceId)
        else StartResult.GoogleConsent(surfaceId, scopes)
    }

    private fun beginMicrosoft(runtimeId: String): StartResult {
        val connector = registry.get(MicrosoftOAuthConfiguration.CONNECTOR_ID) as? MicrosoftGraphConnector
            ?: return StartResult.Failed("not_registered", "Microsoft Graph runtime adapter is not registered")
        val actionScopes = requiredOAuthScopesFor(runtimeId)
        if (actionScopes.isEmpty()) {
            return StartResult.Failed("access_profile_required", "Choose a read-access profile for at least one Microsoft surface before authorizing Graph access.")
        }
        return when (connector.oauthConfiguration()) {
            is OAuthConfiguration.Configured -> connector.buildAuthUrl(actionScopes).fold(
                onSuccess = { StartResult.OAuthBrowser(MicrosoftOAuthConfiguration.CONNECTOR_ID, it) },
                onFailure = { StartResult.Failed("oauth_configuration_invalid", "Microsoft OAuth configuration is invalid") },
            )
            OAuthConfiguration.MissingClientId -> StartResult.Failed("oauth_missing_client_id", "Microsoft OAuth client id is not configured for this build")
            OAuthConfiguration.InvalidRedirectUri -> StartResult.Failed("oauth_invalid_redirect_uri", "Microsoft OAuth redirect URI is invalid or not registered")
            OAuthConfiguration.DisabledForBuild -> StartResult.Failed("oauth_disabled_for_build", "Microsoft OAuth is disabled for this build")
        }
    }

    private fun beginDropbox(runtimeId: String): StartResult {
        val connector = registry.get(runtimeId) as? DropboxConnector
            ?: return StartResult.Failed("not_registered", "Dropbox runtime adapter is not registered")
        val scopes = requiredOAuthScopesFor(runtimeId)
        if (scopes.isEmpty()) {
            return StartResult.Failed("access_profile_required", "Choose a Dropbox access profile before authorizing Dropbox access.")
        }
        return when (connector.oauthConfiguration()) {
            is OAuthConfiguration.Configured -> connector.buildAuthUrl(scopes).fold(
                onSuccess = { StartResult.OAuthBrowser(runtimeId, it) },
                onFailure = { StartResult.Failed("oauth_configuration_invalid", "Dropbox OAuth configuration is invalid") },
            )
            OAuthConfiguration.MissingClientId -> StartResult.Failed("oauth_missing_client_id", "Dropbox app key is not configured for this build")
            OAuthConfiguration.InvalidRedirectUri -> StartResult.Failed("oauth_invalid_redirect_uri", "Dropbox OAuth redirect URI is invalid or not registered")
            OAuthConfiguration.DisabledForBuild -> StartResult.Failed("oauth_disabled_for_build", "Dropbox OAuth is disabled for this build")
        }
    }

    private fun beginBox(runtimeId: String): StartResult {
        val connector = registry.get(runtimeId) as? BoxConnector
            ?: return StartResult.Failed("not_registered", "Box runtime adapter is not registered")
        if (requiredOAuthScopesFor(runtimeId) != setOf(ConnectorProviderScopes.BOX_ROOT_READONLY)) {
            return StartResult.Failed("access_profile_required", "Box read-only access profile is required before authorization.")
        }
        return when (connector.oauthConfiguration()) {
            is OAuthConfiguration.Configured -> connector.buildAuthUrl().fold(
                onSuccess = { StartResult.OAuthBrowser(runtimeId, it) },
                onFailure = { StartResult.Failed("oauth_configuration_invalid", "Box OAuth broker configuration is invalid") },
            )
            OAuthConfiguration.MissingClientId -> StartResult.Failed("oauth_broker_required", "Box requires an HTTPS AIRI OAuth broker; no broker is configured for this build")
            OAuthConfiguration.InvalidRedirectUri -> StartResult.Failed("oauth_invalid_redirect_uri", "Box OAuth redirect URI or broker URL is invalid")
            OAuthConfiguration.DisabledForBuild -> StartResult.Failed("oauth_disabled_for_build", "Box OAuth is disabled for this build")
        }
    }

    private fun beginZapier(runtimeId: String): StartResult {
        val connector = registry.get(runtimeId) as? ZapierConnector
            ?: return StartResult.Failed("not_registered", "Zapier runtime adapter is not registered")
        val scopes = requiredOAuthScopesFor(runtimeId)
        if (scopes.isEmpty()) {
            return StartResult.Failed("access_profile_required", "Choose a read-access profile for the Zapier surface before authorizing it.")
        }
        return runCatching { StartResult.OAuthBrowser(runtimeId, connector.buildAuthUrl(scopes)) }
            .getOrElse { StartResult.Failed("oauth_not_configured", "Zapier OAuth is not configured for this build") }
    }

    /** Securely validates and commits PAT/API/MCP credentials, then health-checks. */
    suspend fun submitCredential(id: String, credential: String): CompletionResult = withContext(Dispatchers.IO) {
        if (!authManager.isSecureStorageAvailable || !secureStorage.isEncrypted) {
            return@withContext CompletionResult.Failed(
                "secure_storage_unavailable",
                "Encrypted credential storage is unavailable; the credential was not accepted."
            )
        }
        val runtimeId = resolveRuntimeId(id)
        val surfaceIds = authorizationSurfaceIds(id, runtimeId)
        if (surfaceIds.any { accessProfileStore.get(it) == ConnectorAccessProfile.NOT_CONFIGURED }) {
            return@withContext CompletionResult.Failed(
                "access_profile_required",
                "Choose an access profile before saving credentials."
            )
        }
        if (credential.isBlank()) return@withContext CompletionResult.Failed("credential_missing", "Credential cannot be empty")
        val validated = when (runtimeId) {
            // GitHub has one runtime authority. Store the PAT in the connector
            // vault, then let GitHubConnector.connect() validate /user and set
            // the canonical connection state. The former direct-service path is
            // intentionally not used here, preventing two health checks and
            // two independent connection states from diverging.
            "github" -> if (authManager.storeCredential("github", "pat", credential.trim())) {
                authManager.setExplicitlyDisconnected("github", false)
                Result.success(true)
            } else {
                Result.failure(IllegalStateException("Secure credential storage is unavailable"))
            }
            "telegram" -> telegramService.validateAndConnect(credential).map { true }
            "notion_mcp" -> {
                if (!secureStorage.isEncrypted) Result.failure(IllegalStateException("Secure credential storage is unavailable"))
                else runCatching { secureStorage.saveNotionToken(credential.trim()); true }
            }
            in ProviderTokenConnector.configs().map { it.id } -> {
                if (!authManager.storeCredential(runtimeId, "token", credential.trim())) {
                    Result.failure(IllegalStateException("Secure credential storage is unavailable"))
                } else {
                    authManager.setExplicitlyDisconnected(runtimeId, false)
                    Result.success(true)
                }
            }
            else -> Result.failure(IllegalArgumentException("Connector '$id' does not accept a credential form"))
        }
        validated.fold(
            onSuccess = {
                if (runtimeId in ProviderTokenConnector.configs().map { it.id }) {
                    authManager.setExplicitlyDisconnected(runtimeId, false)
                }
                val state = registry.connect(runtimeId, persistConnection = true)
                if (state.connected && state.healthy) CompletionResult.Ready(runtimeId, state)
                else {
                    if (runtimeId == "github") {
                        authManager.clearCredential("github", "pat")
                        authManager.setExplicitlyDisconnected("github", true)
                        secureStorage.disconnect("github")
                    }
                    if (runtimeId == "notion_mcp") secureStorage.clearIntegrationToken("notion")
                    CompletionResult.Failed("health_check_failed", state.errorMessage ?: state.statusLine.ifBlank { "Connector health check failed" }, true)
                }
            },
            onFailure = { e -> CompletionResult.Failed("credential_rejected", e.message ?: "Provider rejected the credential") }
        )
    }

    /** Completes the registered OAuth callback and rejects unknown/replayed state. */
    suspend fun completeOAuth(uri: android.net.Uri): CompletionResult = withContext(Dispatchers.IO) {
        if (!authManager.isSecureStorageAvailable || !secureStorage.isEncrypted) {
            return@withContext CompletionResult.Failed(
                "secure_storage_unavailable",
                "Encrypted credential storage is unavailable; OAuth completion was rejected."
            )
        }
        val state = uri.getQueryParameter("state")
            ?: return@withContext CompletionResult.Failed("oauth_state_missing", "OAuth callback did not contain state")
        val pending = OAuthStateRegistry.consumeRequest(state)
            ?: return@withContext CompletionResult.Failed("oauth_state_invalid", "OAuth state is invalid, expired, or already consumed")
        if (isReleaseBlocked(pending.connectorId)) {
            return@withContext CompletionResult.Failed(
                "integration_unavailable",
                "External automation integrations are unavailable in this release."
            )
        }
        when (pending.connectorId) {
            MicrosoftOAuthConfiguration.CONNECTOR_ID -> {
                val expectedScopes = ConnectorProviderScopes.microsoftAuthorizationScopes(requiredOAuthScopesFor(pending.connectorId))
                if (pending.requestedScopes != expectedScopes) {
                    return@withContext CompletionResult.Failed("oauth_scope_set_changed", "Microsoft access profiles changed during authorization; start the consent flow again.")
                }
            }
            DropboxConnector.CONNECTOR_ID -> if (pending.requestedScopes != requiredOAuthScopesFor(DropboxConnector.CONNECTOR_ID)) {
                return@withContext CompletionResult.Failed("oauth_scope_set_changed", "Dropbox access profile changed during authorization; start the consent flow again.")
            }
            BoxConnector.CONNECTOR_ID -> if (pending.requestedScopes != setOf(ConnectorProviderScopes.BOX_ROOT_READONLY)) {
                return@withContext CompletionResult.Failed("oauth_scope_set_changed", "Box access profile changed during authorization; start the consent flow again.")
            }
            "zapier" -> if (pending.requestedScopes != requiredOAuthScopesFor("zapier")) {
                return@withContext CompletionResult.Failed("oauth_scope_set_changed", "Zapier access profiles changed during authorization; start the consent flow again.")
            }
        }
        val code = uri.getQueryParameter("code")
            ?: return@withContext CompletionResult.Failed("oauth_code_missing", "OAuth callback did not contain an authorization code")
        if (pending.connectorId != "zapier" && pending.connectorId != MicrosoftOAuthConfiguration.CONNECTOR_ID && pending.connectorId != DropboxConnector.CONNECTOR_ID && pending.connectorId != BoxConnector.CONNECTOR_ID) {
            return@withContext CompletionResult.Failed("oauth_provider_unsupported", "No OAuth adapter is registered for '${pending.connectorId}'")
        }
        if (pending.connectorId == MicrosoftOAuthConfiguration.CONNECTOR_ID) {
            val microsoft = registry.get(MicrosoftOAuthConfiguration.CONNECTOR_ID) as? MicrosoftGraphConnector
                ?: return@withContext CompletionResult.Failed("not_registered", "Microsoft Graph runtime adapter is not registered")
            if (!microsoft.handleCallback(code, pending)) {
                return@withContext CompletionResult.Failed("token_exchange_failed", "Microsoft authorization was not saved", true)
            }
            return@withContext connectAndVerify(MicrosoftOAuthConfiguration.CONNECTOR_ID)
                .toCompletion(MicrosoftOAuthConfiguration.CONNECTOR_ID)
        }
        if (pending.connectorId == DropboxConnector.CONNECTOR_ID) {
            val dropbox = registry.get(DropboxConnector.CONNECTOR_ID) as? DropboxConnector
                ?: return@withContext CompletionResult.Failed("not_registered", "Dropbox runtime adapter is not registered")
            if (!dropbox.handleCallback(code, pending)) {
                return@withContext CompletionResult.Failed("token_exchange_failed", "Dropbox authorization was not saved", true)
            }
            return@withContext connectAndVerify(DropboxConnector.CONNECTOR_ID)
                .toCompletion(DropboxConnector.CONNECTOR_ID)
        }
        if (pending.connectorId == BoxConnector.CONNECTOR_ID) {
            val box = registry.get(BoxConnector.CONNECTOR_ID) as? BoxConnector
                ?: return@withContext CompletionResult.Failed("not_registered", "Box runtime adapter is not registered")
            if (!box.handleCallback(code, pending)) {
                return@withContext CompletionResult.Failed("oauth_broker_exchange_failed", "Box authorization was not saved by the AIRI broker", true)
            }
            return@withContext connectAndVerify(BoxConnector.CONNECTOR_ID)
                .toCompletion(BoxConnector.CONNECTOR_ID)
        }
        return@withContext CompletionResult.Failed(
            "oauth_broker_required",
            "Zapier requires a server-side confidential OAuth broker; the authorization code was not exchanged in the Android app."
        )
    }

    suspend fun onGoogleSignIn(identity: GoogleIdentityCredential, connectorId: String = "google"): CompletionResult {
        if (identity.email.isBlank()) return CompletionResult.Failed("google_email_missing", "Google account did not provide an email")
        googleAuthService.handleSignInSuccess(identity)
        if (connectorId == "google") return CompletionResult.Ready("google", ConnectorState(true, true, "Google identity signed in"))
        val scopes = requiredOAuthScopesFor(connectorId)
        return if (scopes.isEmpty()) CompletionResult.Failed(
            "access_profile_required",
            "Choose an access profile that permits an action on this Google surface."
        ) else CompletionResult.ConsentRequired(connectorId, scopes)
    }

    suspend fun onGoogleConsent(result: GoogleDataAuthorization, connectorId: String): CompletionResult = when (result) {
        GoogleDataAuthorization.Authorized -> {
            val scopes = requiredOAuthScopesFor(connectorId)
            when {
                scopes.isEmpty() -> CompletionResult.Failed("access_profile_required", "Choose an access profile before authorizing this Google surface.")
                !isCurrentGoogleProfileAuthorized(connectorId, scopes) -> CompletionResult.Failed("oauth_scope_set_changed", "Google authorization no longer matches the current access profile; consent is required again.")
                else -> connectAndVerify("google").toCompletion("google")
            }
        }
        is GoogleDataAuthorization.ConsentRequired -> CompletionResult.ConsentRequired(connectorId, requiredOAuthScopesFor(connectorId))
        GoogleDataAuthorization.Cancelled -> CompletionResult.Failed("authorization_cancelled", "Google data authorization was cancelled")
        GoogleDataAuthorization.Unavailable -> CompletionResult.Failed("authorization_unavailable", "Google data authorization is unavailable", true)
    }

    suspend fun disconnect(id: String): Boolean = withContext(Dispatchers.IO) {
        val runtimeId = resolveRuntimeId(id)
        if (runtimeId == "google" && id != "google" && id in GoogleDataScopes.surfaceIds) {
            val cleared = googleAuthService.disconnectSurface(id)
            if (secureStorage.getGoogleSurfaceGrants().isEmpty()) registry.clearPersistedConnection("google")
            return@withContext cleared
        }
        val runtimeDisconnected = registry.disconnect(runtimeId)
        val credentialsCleared = when (runtimeId) {
            "google" -> googleAuthService.disconnect()
            "microsoft_graph" -> authManager.revokeToken(MicrosoftOAuthConfiguration.CONNECTOR_ID)
            "dropbox" -> authManager.revokeToken(DropboxConnector.CONNECTOR_ID)
            "box" -> authManager.revokeToken(BoxConnector.CONNECTOR_ID)
            "github", "telegram" -> secureStorage.disconnect(runtimeId)
            "notion_mcp" -> secureStorage.clearIntegrationToken("notion")
            "zapier" -> authManager.revokeToken("zapier")
            else -> true
        }
        runtimeDisconnected && credentialsCleared
    }

    private suspend fun connectAndVerify(runtimeId: String): ConnectorState {
        val connector = registry.get(runtimeId)
            ?: return ConnectorState(false, false, errorMessage = "Connector '$runtimeId' is not registered")
        val state = registry.connect(runtimeId, persistConnection = true)
        return if (state.connected && state.healthy) state else connector.state().value.copy(
            connected = false,
            healthy = false,
            errorMessage = state.errorMessage ?: connector.state().value.errorMessage
        )
    }

    private fun resolveRuntimeId(id: String): String = when (id) {
        "google", "microsoft_graph", "notion_mcp" -> id
        else -> ConnectorRuntimeDescriptors.runtimeIdFor(id)
    }

    private fun authorizationSurfaceIds(id: String, runtimeId: String): List<String> {
        // Google is one runtime connector with independent user-data surfaces.
        // A surface id must never expand to every Google product: doing so made
        // the global Google card ask for an impossible cross-product grant and
        // left Gmail without a usable authorization path.
        if (id in GoogleDataScopes.surfaceIds) return listOf(id)
        val catalogSurface = registry.catalogMeta().firstOrNull { it.id == id }
        if (catalogSurface != null) return listOf(catalogSurface.id)
        val declared = registry.get(runtimeId)?.agentActions()
            ?.map { it.surfaceId ?: runtimeId }
            ?.distinct()
            .orEmpty()
        return declared.ifEmpty { listOf(runtimeId) }
    }

    /**
     * Authorization is a capability boundary, not only a UI concern.  Keep the
     * release gate here so generic integration screens, deep links, and future
     * callers cannot start provider authorization behind the frozen release.
     */
    private fun isReleaseBlocked(runtimeId: String): Boolean =
        runtimeId in setOf("zapier", "ifttt", "n8n") &&
            !ReleaseScopePolicy.externalAutomationIntegrationsEnabled

    private fun ConnectorState.toCompletion(id: String): CompletionResult =
        if (connected && healthy) CompletionResult.Ready(id, this)
        else CompletionResult.Failed("health_check_failed", errorMessage ?: statusLine.ifBlank { "Connector health check failed" }, true)
}
