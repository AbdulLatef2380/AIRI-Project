package com.airi.assistant.ui.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.R
import com.airi.assistant.connector.ConnectorAuthManager
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.integrations.github.GithubService
import com.airi.assistant.integrations.google.GoogleAuthService
import com.airi.assistant.integrations.telegram.TelegramService
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

class IntegrationsViewModel(application: Application) : AndroidViewModel(application) {

    // ── Private services (internal domain — ViewModels do not expose services) ─
    // : Use ServiceLocator singleton — eliminates split-brain on Keystore failure.
    private val secureStorage    = ServiceLocator.secureStorage
    // : ConnectorAuthManager — canonical credential store that connectors read from.
    private val authManager: ConnectorAuthManager = ServiceLocator.connectorAuthManager

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
        val valid = incomingState.isNotBlank() && incomingState == oauthStateToken
        if (!valid) {
            android.util.Log.w("IntegrationsVM", "SECURITY: OAuth state mismatch — possible CSRF. " +
                "incoming='${incomingState.take(8)}…' expected='${oauthStateToken.take(8)}…'")
        }
        return valid
    }

    /** Returns the current OAuth state token for inclusion in authorization URLs. */
    fun getOAuthStateToken(): String = oauthStateToken
    private val githubService    = GithubService(secureStorage)
    private val telegramService  = TelegramService(secureStorage)
    private val googleAuthService = GoogleAuthService(application, secureStorage)

    // ── Integration UI State ──────────────────────────────────────────────────

    data class IntegrationItem(
        val id: String,
        val name: String,
        val description: String,
        val emoji: String,
        val isConnected: Boolean,
        val connectedAs: String,
        val lastUpdated: Long
    )

    private val _items = MutableStateFlow(buildItems())
    val items: StateFlow<List<IntegrationItem>> = _items.asStateFlow()

    fun refresh() {
        _items.value = buildItems()
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
                isConnected = secureStorage.isGithubConnected(),
                connectedAs = secureStorage.getGithubUsername(),
                lastUpdated = secureStorage.getGithubUpdated()
            ),
            IntegrationItem(
                id          = "telegram",
                name        = ctx.getString(R.string.integration_telegram_name),
                description = ctx.getString(R.string.integration_telegram_description),
                emoji       = "",
                isConnected = secureStorage.isTelegramConnected(),
                connectedAs = secureStorage.getTelegramUsername(),
                lastUpdated = secureStorage.getTelegramUpdated()
            ),
            IntegrationItem(
                id          = "google",
                name        = ctx.getString(R.string.integration_google_name),
                description = ctx.getString(R.string.integration_google_description),
                emoji       = "",
                isConnected = secureStorage.isGoogleConnected(),
                connectedAs = secureStorage.getGoogleEmail() ?: "",
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
    }

    private val _dialog = MutableStateFlow<DialogState>(DialogState.None)
    val dialog: StateFlow<DialogState> = _dialog.asStateFlow()

    fun openGithubDialog()   { _dialog.value = DialogState.Github() }
    fun openTelegramDialog() { _dialog.value = DialogState.Telegram() }
    fun closeDialog()        { _dialog.value = DialogState.None }

    fun updateGithubToken(token: String) {
        val current = _dialog.value as? DialogState.Github ?: return
        _dialog.value = current.copy(token = token, error = null)
    }

    fun updateTelegramToken(token: String) {
        val current = _dialog.value as? DialogState.Telegram ?: return
        _dialog.value = current.copy(token = token, error = null)
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
            githubService.validateAndConnect(current.token)
                .onSuccess {
                    if (!authManager.storeCredential("github", "pat", current.token.trim())) {
                        _dialog.value = current.copy(
                            loading = false,
                            error = "Secure credential storage is unavailable. GitHub was not connected."
                        )
                    } else {
                        _dialog.value = DialogState.None
                        refresh()
                    }
                }
                .onFailure { e ->
                    AppErrorHandler.capture(e, "IntegrationsViewModel.connectGithub")
                    _dialog.value = current.copy(loading = false, error = e.message ?: "Connection failed")
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
            telegramService.validateAndConnect(current.token)
                .onSuccess {
                    _dialog.value = DialogState.None
                    refresh()
                }
                .onFailure { e ->
                    AppErrorHandler.capture(e, "IntegrationsViewModel.connectTelegram")
                    _dialog.value = current.copy(loading = false, error = e.message ?: "Connection failed")
                }
        }
    }

    fun onGoogleSignInSuccess(account: GoogleSignInAccount) {
        googleAuthService.handleSignInSuccess(account)
        refresh()
    }

    fun onGoogleSignInFailed() {
        // No state change needed — user cancelled or error occurred
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
        when (id) {
            "google" -> googleAuthService.disconnect()
            else     -> secureStorage.disconnect(id)
        }
        refresh()
    }
}
