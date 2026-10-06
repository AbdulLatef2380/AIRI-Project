package com.airi.assistant.ui.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.R
import com.airi.assistant.connector.ConnectorAccessProfile
import com.airi.assistant.connector.ConnectorAuthManager
import com.airi.assistant.connector.ConnectorAuthorizationManager
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.integrations.github.GithubService
import com.airi.assistant.integrations.google.GoogleDataAuthorization
import com.airi.assistant.integrations.telegram.TelegramService
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

class IntegrationsViewModel(application: Application) : AndroidViewModel(application) {

    // ── Private services (internal domain — ViewModels do not expose services) ─
    private val secureStorage = ServiceLocator.secureStorage
    private val authManager: ConnectorAuthManager = ServiceLocator.connectorAuthManager
    private val authorizationManager: ConnectorAuthorizationManager = ServiceLocator.connectorAuthorizationManager
    // The registered GoogleConnector uses this same process-scoped service. Keeping
    // data access tokens only here prevents per-ViewModel token split-brain.
    private val googleAuthService = ServiceLocator.googleAuthService
    private var pendingGoogleScopes: Set<String> = emptySet()

    /**
     * SECURITY: Per-session CSRF state token for OAuth flows.
     *
     * Generated fresh on each ViewModel instantiation. When a future browser
     * OAuth flow (e.g. Slack, Google Drive, Discord) is initiated, this token
     * is passed as the `state` parameter in the authorization URL. On callback
     * reception (via [AppEvent.OAuthCallbackReceived]), the incoming `state`
     * must match [oauthStateToken] or the callback is rejected as CSRF.
     *
     * Current GitHub and Telegram integrations use token-paste flows and do not
     * use this token. It is here for forward-compatibility with browser OAuth.
     */
    private val oauthStateToken: String = buildString {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        append(android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
    }

    /**
     * Validate an OAuth callback's state parameter against [oauthStateToken].
     * Returns true if valid, false if CSRF attack or replay.
     */
    fun validateOAuthState(incomingState: String): Boolean {
        val valid = OAuthCallbackStateValidator.matches(
            expectedState = oauthStateToken,
            incomingState = incomingState
        )
        if (!valid) {
            android.util.Log.w("IntegrationsVM", "OAuth callback state mismatch")
        }
        return valid
    }

    /** Returns the current OAuth state token for inclusion in authorization URLs. */
    fun getOAuthStateToken(): String = oauthStateToken
    private val githubService = GithubService(secureStorage)
    private val telegramService = TelegramService(secureStorage)

    // ── Integration UI State ──────────────────────────────────────────────────

    data class IntegrationItem(
        val id: String,
        val name: String,
        val description: String,
        val emoji: String,
        val readiness: IntegrationReadiness,
        val connectedAs: String,
        val lastUpdated: Long
    ) {
        val isReady: Boolean
            get() = readiness == IntegrationReadiness.READY
    }

    sealed interface GoogleAuthorizationEffect {
        data class LaunchIdentity(val intent: Intent) : GoogleAuthorizationEffect
        data class LaunchConsent(val pendingIntent: PendingIntent) : GoogleAuthorizationEffect
        data class LaunchBrowser(val intent: Intent) : GoogleAuthorizationEffect
    }

    private val _items = MutableStateFlow(buildItems())
    val items: StateFlow<List<IntegrationItem>> = _items.asStateFlow()

    private val _googleFeedback = MutableStateFlow<Int?>(null)
    val googleFeedback: StateFlow<Int?> = _googleFeedback.asStateFlow()

    private val _googleAuthorizationEffects = MutableSharedFlow<GoogleAuthorizationEffect>(
        extraBufferCapacity = 1
    )
    val googleAuthorizationEffects: SharedFlow<GoogleAuthorizationEffect> =
        _googleAuthorizationEffects.asSharedFlow()

    data class AccessProfileRequest(
        val connectorId: String,
        val runtimeId: String,
        val surfaceIds: List<String>,
    )

    private val _accessProfileRequest = MutableStateFlow<AccessProfileRequest?>(null)
    val accessProfileRequest: StateFlow<AccessProfileRequest?> = _accessProfileRequest.asStateFlow()

    fun refresh() {
        _items.value = buildItems()
    }

    /** Starts a non-Google provider flow through the shared authorization manager. */
    fun beginConnectorAuthorization(id: String) {
        viewModelScope.launch {
            when (val result = authorizationManager.begin(id)) {
                is ConnectorAuthorizationManager.StartResult.OAuthBrowser -> {
                    _googleAuthorizationEffects.tryEmit(
                        GoogleAuthorizationEffect.LaunchBrowser(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(result.url))
                        )
                    )
                }
                is ConnectorAuthorizationManager.StartResult.CredentialRequired -> when (result.connectorId) {
                    "github" -> openGithubDialog()
                    "telegram" -> openTelegramDialog()
                    "notion_mcp" -> openNotionDialog()
                    else -> openProviderTokenDialog(result.connectorId, result.provider, result.label)
                }
                is ConnectorAuthorizationManager.StartResult.GoogleIdentity -> {
                    _googleAuthorizationEffects.tryEmit(
                        GoogleAuthorizationEffect.LaunchIdentity(result.intent)
                    )
                }
                is ConnectorAuthorizationManager.StartResult.GoogleConsent -> requestGoogleDataAuthorization(result.scopes)
                is ConnectorAuthorizationManager.StartResult.AccessProfileRequired -> {
                    _accessProfileRequest.value = AccessProfileRequest(
                        connectorId = result.connectorId,
                        runtimeId = result.runtimeId,
                        surfaceIds = result.surfaceIds,
                    )
                }
                is ConnectorAuthorizationManager.StartResult.Ready -> refresh()
                is ConnectorAuthorizationManager.StartResult.Failed -> {
                    _googleFeedback.value = R.string.integration_google_sign_in_failed
                }
            }
        }
    }

    fun chooseAccessProfile(profile: ConnectorAccessProfile) {
        val request = _accessProfileRequest.value ?: return
        request.surfaceIds.forEach { surfaceId ->
            ServiceLocator.connectorAccessProfileStore.set(surfaceId, profile)
        }
        _accessProfileRequest.value = null
        beginConnectorAuthorization(request.connectorId)
    }

    fun cancelAccessProfileRequest() {
        _accessProfileRequest.value = null
    }

    /** Re-evaluates the registered connector after an explicit Google account action. */
    private fun refreshGoogleConnectorState() {
        viewModelScope.launch {
            ServiceLocator.connectorRegistry.connect("google")
            refresh()
        }
    }

    private fun buildItems(): List<IntegrationItem> {
        // Resolve all user-facing strings through the Application context so
        // they pick up the active locale (en / ar) instead of being hardcoded.
        val ctx = getApplication<Application>()
        return listOf(
            IntegrationItem(
                id          = "github",
                name        = ctx.getString(R.string.integration_github_name),
                description = ctx.getString(R.string.integration_github_description),
                emoji       = "",
                readiness   = IntegrationReadinessPolicy.credentialBacked(secureStorage.isGithubConnected()),
                connectedAs = secureStorage.getGithubUsername(),
                lastUpdated = secureStorage.getGithubUpdated()
            ),
            IntegrationItem(
                id          = "telegram",
                name        = ctx.getString(R.string.integration_telegram_name),
                description = ctx.getString(R.string.integration_telegram_description),
                emoji       = "",
                readiness   = IntegrationReadinessPolicy.credentialBacked(secureStorage.isTelegramConnected()),
                connectedAs = secureStorage.getTelegramUsername(),
                lastUpdated = secureStorage.getTelegramUpdated()
            ),
            IntegrationItem(
                id          = "google",
                name        = ctx.getString(R.string.integration_google_name),
                description = ctx.getString(R.string.integration_google_description),
                emoji       = "",
                readiness   = IntegrationReadinessPolicy.google(
                    hasSignedInIdentity = !googleAuthService.getLastSignedInEmail().isNullOrBlank(),
                    hasDataAccessToken = !googleAuthService.getDataAccessToken().isNullOrBlank()
                ),
                connectedAs = googleAuthService.getLastSignedInEmail().orEmpty(),
                lastUpdated = secureStorage.getGoogleUpdated()
            )
        )
    }

    // ── Dialog State ──────────────────────────────────────────────────────────

    sealed class DialogState {
        object None : DialogState()
        data class Github(
            val token: String = "",
            val loading: Boolean = false,
            val error: String? = null
        ) : DialogState()
        data class Telegram(
            val token: String = "",
            val loading: Boolean = false,
            val error: String? = null
        ) : DialogState()
        data class Notion(
            val token: String = "",
            val loading: Boolean = false,
            val error: String? = null
        ) : DialogState()
        data class ProviderToken(
            val connectorId: String,
            val provider: String,
            val credentialLabel: String,
            val token: String = "",
            val loading: Boolean = false,
            val error: String? = null,
        ) : DialogState()
    }

    private val _dialog = MutableStateFlow<DialogState>(DialogState.None)
    val dialog: StateFlow<DialogState> = _dialog.asStateFlow()

    fun openGithubDialog()   { _dialog.value = DialogState.Github() }
    fun openTelegramDialog() { _dialog.value = DialogState.Telegram() }
    fun openNotionDialog() { _dialog.value = DialogState.Notion() }
    fun openProviderTokenDialog(connectorId: String, provider: String, credentialLabel: String) {
        _dialog.value = DialogState.ProviderToken(connectorId, provider, credentialLabel)
    }
    fun closeDialog()        { _dialog.value = DialogState.None }

    fun updateGithubToken(token: String) {
        val current = _dialog.value as? DialogState.Github ?: return
        _dialog.value = current.copy(token = token, error = null)
    }

    fun updateTelegramToken(token: String) {
        val current = _dialog.value as? DialogState.Telegram ?: return
        _dialog.value = current.copy(token = token, error = null)
    }

    fun updateNotionToken(token: String) {
        val current = _dialog.value as? DialogState.Notion ?: return
        _dialog.value = current.copy(token = token, error = null)
    }

    fun updateProviderToken(token: String) {
        val current = _dialog.value as? DialogState.ProviderToken ?: return
        _dialog.value = current.copy(token = token, error = null)
    }

    fun connectProviderToken() {
        val current = _dialog.value as? DialogState.ProviderToken ?: return
        if (current.token.isBlank()) {
            _dialog.value = current.copy(error = "Enter the ${current.credentialLabel}")
            return
        }
        _dialog.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val result = authorizationManager.submitCredential(current.connectorId, current.token)) {
                is ConnectorAuthorizationManager.CompletionResult.Ready -> {
                    _dialog.value = DialogState.None
                    refresh()
                }
                is ConnectorAuthorizationManager.CompletionResult.Failed -> {
                    _dialog.value = current.copy(loading = false, error = result.message)
                }
                is ConnectorAuthorizationManager.CompletionResult.ConsentRequired -> {
                    _dialog.value = current.copy(loading = false, error = "Additional authorization is required")
                }
            }
        }
    }

    // ── Google Sign-In Intent ─────────────────────────────────────────────────

    fun getGoogleSignInIntent(): Intent = googleAuthService.getSignInIntent()

    // ── Connect / Disconnect ──────────────────────────────────────────────────

    fun connectGithub() {
        val current = _dialog.value as? DialogState.Github ?: return
        if (current.token.isBlank()) {
            _dialog.value = current.copy(
                error = getApplication<Application>().getString(R.string.integration_error_paste_github)
            )
            return
        }
        _dialog.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val result = authorizationManager.submitCredential("github", current.token)) {
                is ConnectorAuthorizationManager.CompletionResult.Ready -> {
                    _dialog.value = DialogState.None
                    refresh()
                }
                is ConnectorAuthorizationManager.CompletionResult.Failed -> {
                    _dialog.value = current.copy(loading = false, error = result.message)
                }
                is ConnectorAuthorizationManager.CompletionResult.ConsentRequired -> {
                    _dialog.value = current.copy(loading = false, error = "Additional authorization is required")
                }
            }
        }
    }

    fun connectTelegram() {
        val current = _dialog.value as? DialogState.Telegram ?: return
        if (current.token.isBlank()) {
            _dialog.value = current.copy(
                error = getApplication<Application>().getString(R.string.integration_error_paste_telegram)
            )
            return
        }
        _dialog.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val result = authorizationManager.submitCredential("telegram", current.token)) {
                is ConnectorAuthorizationManager.CompletionResult.Ready -> {
                    _dialog.value = DialogState.None
                    refresh()
                }
                is ConnectorAuthorizationManager.CompletionResult.Failed -> {
                    _dialog.value = current.copy(loading = false, error = result.message)
                }
                is ConnectorAuthorizationManager.CompletionResult.ConsentRequired -> {
                    _dialog.value = current.copy(loading = false, error = "Additional authorization is required")
                }
            }
        }
    }

    fun connectNotion() {
        val current = _dialog.value as? DialogState.Notion ?: return
        if (current.token.isBlank()) {
            _dialog.value = current.copy(error = "Enter a Notion integration token")
            return
        }
        _dialog.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val result = authorizationManager.submitCredential("notion", current.token)) {
                is ConnectorAuthorizationManager.CompletionResult.Ready -> {
                    _dialog.value = DialogState.None
                    refresh()
                }
                is ConnectorAuthorizationManager.CompletionResult.Failed -> {
                    _dialog.value = current.copy(loading = false, error = result.message)
                }
                is ConnectorAuthorizationManager.CompletionResult.ConsentRequired -> {
                    _dialog.value = current.copy(loading = false, error = "Additional authorization is required")
                }
            }
        }
    }

    fun onGoogleSignInSuccess(account: GoogleSignInAccount) {
        if (!GoogleIntegrationSignInPolicy.canConnect(account.email)) {
            _googleFeedback.value = GoogleIntegrationSignInPolicy.missingEmailFeedback()
            return
        }
        viewModelScope.launch {
            when (val result = authorizationManager.onGoogleSignIn(account)) {
                is ConnectorAuthorizationManager.CompletionResult.ConsentRequired -> {
                    refreshGoogleConnectorState()
                    requestGoogleDataAuthorization(result.scopes)
                }
                is ConnectorAuthorizationManager.CompletionResult.Failed -> {
                    _googleFeedback.value = GoogleIntegrationSignInPolicy.providerFailureFeedback()
                }
                is ConnectorAuthorizationManager.CompletionResult.Ready -> refresh()
            }
        }
    }

    fun requestGoogleDataAuthorization(scopes: Set<String> = authorizationManager.requiredOAuthScopesFor("google")) {
        if (scopes.isEmpty()) {
            _googleFeedback.value = GoogleIntegrationSignInPolicy.authorizationFailedFeedback()
            return
        }
        pendingGoogleScopes = scopes
        googleAuthService.authorizeDataAccess(scopes)
            .addOnSuccessListener(::handleGoogleDataAuthorization)
            .addOnFailureListener {
                pendingGoogleScopes = emptySet()
                refreshGoogleConnectorState()
                _googleFeedback.value = GoogleIntegrationSignInPolicy.authorizationFailedFeedback()
            }
    }

    fun onGoogleDataAuthorizationResult(resultIntent: Intent?) {
        val currentScopes = authorizationManager.requiredOAuthScopesFor("google")
        if (pendingGoogleScopes.isEmpty() || pendingGoogleScopes != currentScopes) {
            pendingGoogleScopes = emptySet()
            googleAuthService.clearDataAccessToken()
            _googleFeedback.value = GoogleIntegrationSignInPolicy.authorizationFailedFeedback()
            return
        }
        handleGoogleDataAuthorization(googleAuthService.completeDataAuthorization(resultIntent, pendingGoogleScopes))
    }

    private fun handleGoogleDataAuthorization(result: GoogleDataAuthorization) {
        when (result) {
            GoogleDataAuthorization.Authorized -> {
                pendingGoogleScopes = emptySet()
                refreshGoogleConnectorState()
                _googleFeedback.value = GoogleIntegrationSignInPolicy.dataAuthorizedFeedback()
            }
            is GoogleDataAuthorization.ConsentRequired -> {
                _googleAuthorizationEffects.tryEmit(
                    GoogleAuthorizationEffect.LaunchConsent(result.pendingIntent)
                )
            }
            GoogleDataAuthorization.Cancelled -> {
                pendingGoogleScopes = emptySet()
                refreshGoogleConnectorState()
                _googleFeedback.value = GoogleIntegrationSignInPolicy.authorizationCancelledFeedback()
            }
            GoogleDataAuthorization.Unavailable -> {
                pendingGoogleScopes = emptySet()
                refreshGoogleConnectorState()
                _googleFeedback.value = GoogleIntegrationSignInPolicy.authorizationFailedFeedback()
            }
        }
    }

    fun onGoogleSignInCancelled() {
        _googleFeedback.value = GoogleIntegrationSignInPolicy.cancelledFeedback()
    }

    fun onGoogleSignInFailed() {
        _googleFeedback.value = GoogleIntegrationSignInPolicy.providerFailureFeedback()
    }

    fun consumeGoogleFeedback() {
        _googleFeedback.value = null
    }

    /**
     * Handle an OAuth callback received from [AppEvent.OAuthCallbackReceived].
     *
     * SECURITY: Validates the `state` parameter to prevent CSRF.
     * Only called from callers that subscribe to [EventBus.events].
     */
    fun handleOAuthCallback(code: String, state: String) {
        // Browser callbacks are consumed and routed by MainActivity through
        // OAuthStateRegistry. This method remains only for legacy callers and
        // intentionally does not inspect or log authorization codes.
        if (code.isBlank() || state.isBlank()) {
            android.util.Log.w("IntegrationsVM", "Ignored incomplete OAuth callback")
        }
    }

    init {
        // : One-time migration — bridge any PAT already stored in the legacy
        // SecureStorage "github_token" key into ConnectorAuthManager "github"/"pat".
        // Runs every launch but is a no-op once ConnectorAuthManager already has the key.
        val existingGithubPat = secureStorage.getGithubToken()
        if (!existingGithubPat.isNullOrBlank() && authManager.getCredential("github", "pat").isNullOrBlank()) {
            authManager.storeCredential("github", "pat", existingGithubPat)
        }

        // Subscribe to OAuth deep-link callbacks from MainActivity
        viewModelScope.launch {
            com.airi.assistant.domain.event.EventBus.events.collect { event ->
                if (event is com.airi.assistant.domain.event.AppEvent.OAuthCallbackReceived) {
                    handleOAuthCallback(code = event.code, state = event.state)
                }
            }
        }
    }

    fun disconnect(id: String) {
        viewModelScope.launch {
            authorizationManager.disconnect(id)
            refresh()
        }
    }
}
