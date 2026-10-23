package com.airi.assistant.ui.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.R
import com.airi.assistant.connector.ConnectorAccessProfile
import com.airi.assistant.connector.ConnectorAuthManager
import com.airi.assistant.connector.ConnectorAuthorizationManager
import com.airi.assistant.connector.app.github.GithubAuthorizationFailure
import com.airi.assistant.connector.app.github.GithubAuthorizationResult
import com.airi.assistant.connector.app.github.GithubAuthorizationState
import com.airi.assistant.connector.app.github.GithubRepositoryGrant
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.integrations.google.GoogleDataAuthorization
import com.airi.assistant.integrations.google.GoogleSignInFailureClassifier
import com.airi.assistant.integrations.google.GoogleSignInFailureReason
import com.airi.assistant.integrations.telegram.TelegramService
import com.airi.assistant.integrations.google.GoogleIdentityCredential
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
    private val githubAuthorizationCoordinator = ServiceLocator.githubAuthorizationCoordinator
    // The registered GoogleConnector uses this same process-scoped service. Keeping
    // data access tokens only here prevents per-ViewModel token split-brain.
    private val googleAuthService = ServiceLocator.googleAuthService
    private var pendingGoogleScopes: Set<String> = emptySet()
    private var pendingGoogleSurfaceId: String = "google"
    private var activeConnectorId: String? = null

    /**
     * SECURITY: Per-session CSRF state token for OAuth flows.
     *
     * Generated fresh on each ViewModel instantiation. When a future browser
     * OAuth flow (e.g. Slack, Google Drive, Discord) is initiated, this token
     * is passed as the `state` parameter in the authorization URL. On callback
     * reception (via [AppEvent.OAuthCallbackReceived]), the incoming `state`
     * must match [oauthStateToken] or the callback is rejected as CSRF.
     *
     * GitHub uses its project-scoped coordinator; Telegram uses a credential
     * form. This state is retained for the other browser OAuth callbacks.
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

    data class GithubProjectAuthorizationUiState(
        val projectId: String?,
        val projectName: String?,
        val authorizationState: GithubAuthorizationState,
        val isLoading: Boolean = false,
        val runtimeReady: Boolean = false,
    )

    data class GithubProjectRepositoryUiState(
        val projectId: String? = null,
        val connectionId: String? = null,
        val repositories: List<GithubRepositoryGrant> = emptyList(),
        val selectedRepositoryIds: Set<Long> = emptySet(),
        val savedRepositoryIds: Set<Long> = emptySet(),
        val isLoading: Boolean = false,
        val isSaving: Boolean = false,
        val hasLoaded: Boolean = false,
        val error: GithubAuthorizationFailure? = null,
    ) {
        val hasUnsavedChanges: Boolean
            get() = selectedRepositoryIds != savedRepositoryIds
    }

    sealed interface GoogleAuthorizationEffect {
        data class LaunchIdentity(val connectorId: String) : GoogleAuthorizationEffect
        data class LaunchConsent(val pendingIntent: PendingIntent) : GoogleAuthorizationEffect
        data class LaunchBrowser(val intent: Intent) : GoogleAuthorizationEffect
    }

    private val _items = MutableStateFlow(buildItems())
    val items: StateFlow<List<IntegrationItem>> = _items.asStateFlow()

    private val _githubProjectAuthorization = MutableStateFlow(
        GithubProjectAuthorizationUiState(
            projectId = null,
            projectName = null,
            authorizationState = GithubAuthorizationState.SignedOut,
        )
    )
    val githubProjectAuthorization: StateFlow<GithubProjectAuthorizationUiState> =
        _githubProjectAuthorization.asStateFlow()

    private val _githubProjectRepositories = MutableStateFlow(GithubProjectRepositoryUiState())
    val githubProjectRepositories: StateFlow<GithubProjectRepositoryUiState> =
        _githubProjectRepositories.asStateFlow()

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
        val allowedProfiles: List<ConnectorAccessProfile>,
    )

    private val _accessProfileRequest = MutableStateFlow<AccessProfileRequest?>(null)
    val accessProfileRequest: StateFlow<AccessProfileRequest?> = _accessProfileRequest.asStateFlow()

    fun refresh() {
        _items.value = buildItems()
        refreshGithubProjectAuthorizationState()
    }

    /** Refreshes only the OAuth projection for the currently selected workspace/project. */
    fun refreshGithubProjectAuthorization() {
        refreshGithubProjectAuthorizationState(forceRepositoryRefresh = true)
    }

    fun refreshGithubRepositories() {
        refreshGithubProjectAuthorizationState(forceRepositoryRefresh = true)
    }

    fun setGithubRepositorySelected(repositoryId: Long, selected: Boolean) {
        val current = _githubProjectRepositories.value
        if (current.isLoading || current.isSaving || current.repositories.none { it.repositoryId == repositoryId }) return
        val updated = current.selectedRepositoryIds.toMutableSet().apply {
            if (selected) add(repositoryId) else remove(repositoryId)
        }
        _githubProjectRepositories.value = current.copy(selectedRepositoryIds = updated)
    }

    fun saveGithubRepositorySelection() {
        val current = _githubProjectRepositories.value
        val projectId = current.projectId ?: return
        val connectionId = current.connectionId ?: return
        if (current.isLoading || current.isSaving || !current.hasUnsavedChanges) return
        val allowedIds = current.repositories.map { it.repositoryId }.toSet()
        if (!allowedIds.containsAll(current.selectedRepositoryIds)) return
        _githubProjectRepositories.value = current.copy(isSaving = true, error = null)
        viewModelScope.launch {
            val result = githubAuthorizationCoordinator.selectRepositories(projectId, current.selectedRepositoryIds)
            val latest = _githubProjectRepositories.value
            if (latest.projectId != projectId || latest.connectionId != connectionId ||
                ServiceLocator.workspaceRuntime.activeSession.value?.sessionId != projectId
            ) return@launch
            when (result) {
                is GithubAuthorizationResult.Success -> {
                    val savedIds = result.value.map { it.repositoryId }.toSet()
                    _githubProjectRepositories.value = latest.copy(
                        selectedRepositoryIds = savedIds,
                        savedRepositoryIds = savedIds,
                        isSaving = false,
                        error = null,
                    )
                }
                is GithubAuthorizationResult.Failure -> {
                    _githubProjectRepositories.value = latest.copy(isSaving = false, error = result.failure)
                    refreshGithubProjectAuthorizationState()
                }
            }
        }
    }

    /** Starts project-scoped OAuth. It never falls back to the global PAT flow. */
    fun connectGithubProject() {
        val project = ServiceLocator.workspaceRuntime.activeSession.value
        val projectId = project?.sessionId?.takeIf(String::isNotBlank)
        if (projectId == null) {
            refreshGithubProjectAuthorizationState(project)
            return
        }
        _githubProjectAuthorization.value = GithubProjectAuthorizationUiState(
            projectId = projectId,
            projectName = project.name,
            authorizationState = githubAuthorizationCoordinator.stateFor(projectId),
            isLoading = true,
        )
        viewModelScope.launch {
            try {
                when (val result = githubAuthorizationCoordinator.beginAuthorization(projectId)) {
                    is com.airi.assistant.connector.app.github.GithubAuthorizationResult.Success -> {
                        val url = result.value.authorizationUrl
                        val uri = android.net.Uri.parse(url)
                        if (uri.scheme == "https" && uri.host in setOf("github.com", "www.github.com") &&
                            uri.userInfo == null && (uri.port == -1 || uri.port == 443)
                        ) {
                            _googleAuthorizationEffects.emit(
                                GoogleAuthorizationEffect.LaunchBrowser(
                                    Intent(Intent.ACTION_VIEW, uri)
                                )
                            )
                        } else {
                            githubAuthorizationCoordinator.cancelAuthorization(projectId, result.value.state)
                        }
                    }
                    is com.airi.assistant.connector.app.github.GithubAuthorizationResult.Failure -> Unit
                }
            } finally {
                refreshGithubProjectAuthorizationState()
            }
        }
    }

    /** Removes only the active project's OAuth session; legacy tokens are not used as a fallback. */
    fun disconnectGithubProject() {
        val projectId = ServiceLocator.workspaceRuntime.activeSession.value?.sessionId
            ?.takeIf(String::isNotBlank) ?: return
        val projectName = ServiceLocator.workspaceRuntime.activeSession.value?.name
        _githubProjectAuthorization.value = GithubProjectAuthorizationUiState(
            projectId = projectId,
            projectName = projectName,
            authorizationState = githubAuthorizationCoordinator.stateFor(projectId),
            isLoading = true,
        )
        viewModelScope.launch {
            try {
                githubAuthorizationCoordinator.revokeSession(projectId)
            } finally {
                refreshGithubProjectAuthorizationState()
            }
        }
    }

    private fun refreshGithubProjectAuthorizationState(
        project: com.airi.assistant.workspace.WorkspaceRuntime.WorkspaceSession? =
            ServiceLocator.workspaceRuntime.activeSession.value,
        forceRepositoryRefresh: Boolean = false,
    ) {
        _items.value = buildItems()
        val projectId = project?.sessionId?.takeIf(String::isNotBlank)
        val authorizationState = projectId?.let(githubAuthorizationCoordinator::stateFor)
            ?: GithubAuthorizationState.SignedOut
        val runtimeState = ServiceLocator.connectorRegistry.get("github")?.state()?.value
        _githubProjectAuthorization.value = GithubProjectAuthorizationUiState(
            projectId = projectId,
            projectName = project?.name,
            authorizationState = authorizationState,
            // A refresh observes the completed coordinator state. Do not carry
            // a stale spinner across broker failure, callback, or resume.
            isLoading = false,
            runtimeReady = runtimeState?.let { it.connected && it.healthy } == true,
        )
        val ready = authorizationState as? GithubAuthorizationState.Ready
        if (projectId == null || ready == null) {
            _githubProjectRepositories.value = GithubProjectRepositoryUiState(projectId = projectId)
        } else {
            loadGithubRepositories(
                projectId = projectId,
                connectionId = ready.session.airiConnectionId,
                initiallySelectedIds = ready.session.repositories.map { it.repositoryId }.toSet(),
                force = forceRepositoryRefresh,
            )
        }
    }

    private fun loadGithubRepositories(
        projectId: String,
        connectionId: String,
        initiallySelectedIds: Set<Long>,
        force: Boolean,
    ) {
        val current = _githubProjectRepositories.value
        if (current.projectId == projectId && current.connectionId == connectionId &&
            !force && (current.isLoading || current.hasLoaded || current.error != null)
        ) return
        _githubProjectRepositories.value = GithubProjectRepositoryUiState(
            projectId = projectId,
            connectionId = connectionId,
            selectedRepositoryIds = initiallySelectedIds,
            savedRepositoryIds = initiallySelectedIds,
            isLoading = true,
        )
        viewModelScope.launch {
            val result = githubAuthorizationCoordinator.listRepositories(projectId)
            val latest = _githubProjectRepositories.value
            if (latest.projectId != projectId || latest.connectionId != connectionId ||
                ServiceLocator.workspaceRuntime.activeSession.value?.sessionId != projectId
            ) return@launch
            when (result) {
                is GithubAuthorizationResult.Success -> {
                    val availableIds = result.value.map { it.repositoryId }.toSet()
                    val savedIds = initiallySelectedIds.intersect(availableIds)
                    _githubProjectRepositories.value = latest.copy(
                        repositories = result.value,
                        selectedRepositoryIds = savedIds,
                        savedRepositoryIds = savedIds,
                        isLoading = false,
                        hasLoaded = true,
                        error = null,
                    )
                }
                is GithubAuthorizationResult.Failure -> {
                    _githubProjectRepositories.value = latest.copy(
                        repositories = emptyList(),
                        isLoading = false,
                        hasLoaded = false,
                        error = result.failure,
                    )
                }
            }
        }
    }

    /** Starts a non-Google provider flow through the shared authorization manager. */
    fun beginConnectorAuthorization(id: String, presentationContext: Context = getApplication<Application>()) {
        if (id.isBlank()) return
        // GitHub is project-scoped and broker-authorized. It must never enter
        // the generic credential form, which belongs to explicitly supported
        // legacy providers and has no tenant/repository binding.
        if (id == "github") {
            connectGithubProject()
            return
        }
        // The visible Google card represents the shared identity, but Gmail is
        // the first concrete data surface and needs its own read-only OAuth
        // scope. Starting the global identity-only flow left users "connected"
        // while every Gmail action still lacked an access token.
        val authorizationId = if (id == "google") "google_gmail" else id
        if (activeConnectorId != authorizationId) {
            _dialog.value = DialogState.None
            _accessProfileRequest.value = null
            _googleFeedback.value = null
            pendingGoogleScopes = emptySet()
        }
        // Record the user-selected connector first for the isolation contract;
        // Google then narrows the in-flight target to its concrete data surface.
        activeConnectorId = id
        if (authorizationId != id) activeConnectorId = authorizationId
        viewModelScope.launch {
            when (val result = authorizationManager.begin(authorizationId)) {
                is ConnectorAuthorizationManager.StartResult.OAuthBrowser -> {
                    _googleAuthorizationEffects.tryEmit(
                        GoogleAuthorizationEffect.LaunchBrowser(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(result.url))
                        )
                    )
                }
                is ConnectorAuthorizationManager.StartResult.CredentialRequired -> when (result.connectorId) {
                    "github" -> _googleFeedback.value = R.string.github_project_error_broker_not_configured
                    "telegram" -> openTelegramDialog()
                    "notion_mcp" -> openNotionDialog()
                    else -> openProviderTokenDialog(result.connectorId, result.provider, result.label)
                }
                is ConnectorAuthorizationManager.StartResult.GoogleIdentity -> {
                    pendingGoogleSurfaceId = result.connectorId
                    _googleAuthorizationEffects.tryEmit(
                        GoogleAuthorizationEffect.LaunchIdentity(result.connectorId)
                    )
                }
                is ConnectorAuthorizationManager.StartResult.GoogleConsent ->
                    requestGoogleDataAuthorization(result.connectorId, result.scopes, presentationContext)
                is ConnectorAuthorizationManager.StartResult.AccessProfileRequired -> {
                    val permissions = ServiceLocator.connectorRegistry.get(result.runtimeId)?.agentActions()
                        .orEmpty()
                        .filter { (it.surfaceId ?: result.runtimeId) in result.surfaceIds }
                        .map { it.permission }
                        .toSet()
                    _accessProfileRequest.value = AccessProfileRequest(
                        connectorId = result.connectorId,
                        runtimeId = result.runtimeId,
                        surfaceIds = result.surfaceIds,
                        allowedProfiles = ConnectorAccessProfile.selectableFor(permissions),
                    )
                }
                is ConnectorAuthorizationManager.StartResult.Ready -> refresh()
                is ConnectorAuthorizationManager.StartResult.Failed -> {
                    _googleFeedback.value = when {
                        result.code == "github_broker_required" -> R.string.github_project_error_broker_not_configured
                        authorizationId == "google" || authorizationId.startsWith("google_") ->
                            GoogleIntegrationSignInPolicy.failureFeedback(
                                GoogleSignInFailureClassifier.classifyCode(result.code, result.message)
                            )
                        else -> R.string.integration_google_sign_in_failed
                    }
                }
            }
        }
    }

    fun chooseAccessProfile(
        profile: ConnectorAccessProfile,
        presentationContext: Context = getApplication<Application>(),
    ) {
        val request = _accessProfileRequest.value ?: return
        if (!ConnectorScreenIsolationPolicy.acceptsResult(request.connectorId, activeConnectorId)) return
        if (profile !in request.allowedProfiles) return
        request.surfaceIds.forEach { surfaceId ->
            ServiceLocator.connectorAccessProfileStore.set(surfaceId, profile)
        }
        _accessProfileRequest.value = null
        if (profile != ConnectorAccessProfile.NOT_CONFIGURED) beginConnectorAuthorization(request.connectorId, presentationContext)
    }

    fun cancelAccessProfileRequest() {
        _accessProfileRequest.value = null
        activeConnectorId = null
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
                readiness   = githubProjectReadiness(),
                connectedAs = githubProjectAccountLogin(),
                lastUpdated = 0L
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
                    hasDataAccessToken = googleAuthService.hasAnyAuthorizedDataAccess(),
                ),
                connectedAs = googleAuthService.getLastSignedInEmail().orEmpty(),
                lastUpdated = secureStorage.getGoogleUpdated()
            )
        )
    }

    // ── Dialog State ──────────────────────────────────────────────────────────

    sealed class DialogState {
        object None : DialogState()
        data class Telegram(
            val connectorId: String = "telegram",
            val token: String = "",
            val loading: Boolean = false,
            val error: String? = null
        ) : DialogState()
        data class Notion(
            val connectorId: String = "notion",
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

    fun openTelegramDialog() { activeConnectorId = "telegram"; _dialog.value = DialogState.Telegram() }
    fun openNotionDialog() { activeConnectorId = "notion"; _dialog.value = DialogState.Notion() }
    fun openProviderTokenDialog(connectorId: String, provider: String, credentialLabel: String) {
        if (connectorId.isBlank()) return
        activeConnectorId = connectorId
        _dialog.value = DialogState.ProviderToken(connectorId, provider, credentialLabel)
    }
    fun closeDialog()        {
        _dialog.value = DialogState.None
        activeConnectorId = null
        pendingGoogleScopes = emptySet()
    }

    fun updateTelegramToken(token: String) {
        val current = _dialog.value as? DialogState.Telegram ?: return
        if (!ConnectorScreenIsolationPolicy.acceptsDialogUpdate(current.connectorId, activeConnectorId)) return
        _dialog.value = current.copy(token = token, error = null)
    }

    fun updateNotionToken(token: String) {
        val current = _dialog.value as? DialogState.Notion ?: return
        if (!ConnectorScreenIsolationPolicy.acceptsDialogUpdate(current.connectorId, activeConnectorId)) return
        _dialog.value = current.copy(token = token, error = null)
    }

    fun updateProviderToken(token: String) {
        val current = _dialog.value as? DialogState.ProviderToken ?: return
        if (!ConnectorScreenIsolationPolicy.acceptsDialogUpdate(current.connectorId, activeConnectorId)) return
        _dialog.value = current.copy(token = token, error = null)
    }

    fun connectProviderToken() {
        val current = _dialog.value as? DialogState.ProviderToken ?: return
        if (!ConnectorScreenIsolationPolicy.acceptsResult(current.connectorId, activeConnectorId)) return
        if (current.token.isBlank()) {
            _dialog.value = current.copy(error = "Enter the ${current.credentialLabel}")
            return
        }
        _dialog.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val result = authorizationManager.submitCredential(current.connectorId, current.token)) {
                is ConnectorAuthorizationManager.CompletionResult.Ready -> {
                    if (!ConnectorScreenIsolationPolicy.acceptsResult(current.connectorId, activeConnectorId)) return@launch
                    _dialog.value = DialogState.None
                    refresh()
                }
                is ConnectorAuthorizationManager.CompletionResult.Failed -> {
                    if (!ConnectorScreenIsolationPolicy.acceptsResult(current.connectorId, activeConnectorId)) return@launch
                    _dialog.value = current.copy(loading = false, error = result.message)
                }
                is ConnectorAuthorizationManager.CompletionResult.ConsentRequired -> {
                    if (!ConnectorScreenIsolationPolicy.acceptsResult(current.connectorId, activeConnectorId)) return@launch
                    _dialog.value = current.copy(loading = false, error = "Additional authorization is required")
                }
            }
        }
    }

    // ── Google identity via Credential Manager ───────────────────────────────

    /** Backward-compatible entry point for non-Compose callers. */
    fun authenticateGoogle() {
        authenticateGoogle(getApplication<Application>())
    }

    fun authenticateGoogle(presentationContext: Context = getApplication<Application>()) {
        val surfaceId = pendingGoogleSurfaceId
        viewModelScope.launch {
            googleAuthService.requestIdentity(presentationContext).fold(
                onSuccess = { identity -> onGoogleSignInSuccess(identity, presentationContext) },
                onFailure = { error ->
                    if (ConnectorScreenIsolationPolicy.acceptsResult(surfaceId, activeConnectorId)) {
                        _googleFeedback.value = GoogleIntegrationSignInPolicy.failureFeedback(
                            GoogleSignInFailureClassifier.classify(error)
                        )
                    }
                },
            )
        }
    }

    // ── Connect / Disconnect ──────────────────────────────────────────────────

    fun connectTelegram() {
        val current = _dialog.value as? DialogState.Telegram ?: return
        if (!ConnectorScreenIsolationPolicy.acceptsResult(current.connectorId, activeConnectorId)) return
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
                    if (!ConnectorScreenIsolationPolicy.acceptsResult(current.connectorId, activeConnectorId)) return@launch
                    _dialog.value = DialogState.None
                    refresh()
                }
                is ConnectorAuthorizationManager.CompletionResult.Failed -> {
                    if (!ConnectorScreenIsolationPolicy.acceptsResult(current.connectorId, activeConnectorId)) return@launch
                    _dialog.value = current.copy(loading = false, error = result.message)
                }
                is ConnectorAuthorizationManager.CompletionResult.ConsentRequired -> {
                    if (!ConnectorScreenIsolationPolicy.acceptsResult(current.connectorId, activeConnectorId)) return@launch
                    _dialog.value = current.copy(loading = false, error = "Additional authorization is required")
                }
            }
        }
    }

    fun connectNotion() {
        val current = _dialog.value as? DialogState.Notion ?: return
        if (!ConnectorScreenIsolationPolicy.acceptsResult(current.connectorId, activeConnectorId)) return
        if (current.token.isBlank()) {
            _dialog.value = current.copy(error = "Enter a Notion integration token")
            return
        }
        _dialog.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val result = authorizationManager.submitCredential("notion", current.token)) {
                is ConnectorAuthorizationManager.CompletionResult.Ready -> {
                    if (!ConnectorScreenIsolationPolicy.acceptsResult(current.connectorId, activeConnectorId)) return@launch
                    _dialog.value = DialogState.None
                    refresh()
                }
                is ConnectorAuthorizationManager.CompletionResult.Failed -> {
                    if (!ConnectorScreenIsolationPolicy.acceptsResult(current.connectorId, activeConnectorId)) return@launch
                    _dialog.value = current.copy(loading = false, error = result.message)
                }
                is ConnectorAuthorizationManager.CompletionResult.ConsentRequired -> {
                    if (!ConnectorScreenIsolationPolicy.acceptsResult(current.connectorId, activeConnectorId)) return@launch
                    _dialog.value = current.copy(loading = false, error = "Additional authorization is required")
                }
            }
        }
    }

    fun onGoogleSignInSuccess(
        identity: GoogleIdentityCredential,
        presentationContext: Context = getApplication<Application>(),
    ) {
        if (!ConnectorScreenIsolationPolicy.acceptsResult(pendingGoogleSurfaceId, activeConnectorId)) return
        if (!GoogleIntegrationSignInPolicy.canConnect(identity.email)) {
            _googleFeedback.value = GoogleIntegrationSignInPolicy.missingEmailFeedback()
            return
        }
        viewModelScope.launch {
            when (val result = authorizationManager.onGoogleSignIn(identity, pendingGoogleSurfaceId)) {
                is ConnectorAuthorizationManager.CompletionResult.ConsentRequired -> {
                    requestGoogleDataAuthorization(result.connectorId, result.scopes, presentationContext)
                }
                is ConnectorAuthorizationManager.CompletionResult.Failed -> {
                    _googleFeedback.value = if (result.code == "google_email_missing") {
                        GoogleIntegrationSignInPolicy.missingEmailFeedback()
                    } else {
                        GoogleIntegrationSignInPolicy.failureFeedback(
                            GoogleSignInFailureClassifier.classifyCode(result.code, result.message)
                        )
                    }
                }
                is ConnectorAuthorizationManager.CompletionResult.Ready -> refresh()
            }
        }
    }

    fun requestGoogleDataAuthorization(
        surfaceId: String,
        scopes: Set<String>,
        presentationContext: Context = getApplication<Application>(),
    ) {
        if (surfaceId.isBlank()) return
        activeConnectorId = surfaceId
        if (scopes.isEmpty()) {
            _googleFeedback.value = GoogleIntegrationSignInPolicy.authorizationFailedFeedback()
            return
        }
        pendingGoogleSurfaceId = surfaceId
        pendingGoogleScopes = scopes
        googleAuthService.authorizeDataAccess(surfaceId, scopes, presentationContext)
            .addOnSuccessListener { result -> handleGoogleDataAuthorization(result, surfaceId, presentationContext) }
            .addOnFailureListener { error ->
                pendingGoogleScopes = emptySet()
                googleAuthService.clearDataAccessToken(surfaceId)
                refreshGoogleConnectorState()
                _googleFeedback.value = GoogleIntegrationSignInPolicy.dataAuthorizationFailureFeedback(
                    GoogleSignInFailureClassifier.classify(error)
                )
            }
    }

    fun onGoogleDataAuthorizationResult(
        resultIntent: Intent?,
        presentationContext: Context = getApplication<Application>(),
    ) {
        if (!ConnectorScreenIsolationPolicy.acceptsResult(pendingGoogleSurfaceId, activeConnectorId)) return
        val currentScopes = authorizationManager.requiredOAuthScopesFor(pendingGoogleSurfaceId)
        if (pendingGoogleScopes.isEmpty() || pendingGoogleScopes != currentScopes) {
            pendingGoogleScopes = emptySet()
            googleAuthService.clearDataAccessToken(pendingGoogleSurfaceId)
            _googleFeedback.value = GoogleIntegrationSignInPolicy.dataAuthorizationFailureFeedback(
                GoogleSignInFailureReason.FIREBASE_CONFIGURATION
            )
            return
        }
        handleGoogleDataAuthorization(
            googleAuthService.completeDataAuthorization(resultIntent, pendingGoogleSurfaceId, pendingGoogleScopes),
            pendingGoogleSurfaceId,
            presentationContext,
        )
    }

    fun onGoogleDataAuthorizationCancelled() {
        val surfaceId = pendingGoogleSurfaceId
        if (!ConnectorScreenIsolationPolicy.acceptsResult(surfaceId, activeConnectorId)) return
        pendingGoogleScopes = emptySet()
        googleAuthService.clearDataAccessToken(surfaceId)
        _googleFeedback.value = GoogleIntegrationSignInPolicy.authorizationCancelledFeedback()
    }

    fun onGoogleDataAuthorizationFailed() {
        val surfaceId = pendingGoogleSurfaceId
        if (!ConnectorScreenIsolationPolicy.acceptsResult(surfaceId, activeConnectorId)) return
        pendingGoogleScopes = emptySet()
        googleAuthService.clearDataAccessToken(surfaceId)
        refreshGoogleConnectorState()
        _googleFeedback.value = GoogleIntegrationSignInPolicy.authorizationFailedFeedback()
    }

    private fun handleGoogleDataAuthorization(
        result: GoogleDataAuthorization,
        surfaceId: String,
        presentationContext: Context,
    ) {
        when (result) {
            GoogleDataAuthorization.Authorized -> {
                pendingGoogleScopes = emptySet()
                viewModelScope.launch {
                    when (val completion = authorizationManager.onGoogleConsent(result, surfaceId)) {
                        is ConnectorAuthorizationManager.CompletionResult.Ready -> {
                            refresh()
                            _googleFeedback.value = GoogleIntegrationSignInPolicy.dataAuthorizedFeedback()
                        }
                        is ConnectorAuthorizationManager.CompletionResult.ConsentRequired ->
                            requestGoogleDataAuthorization(completion.connectorId, completion.scopes, presentationContext)
                        is ConnectorAuthorizationManager.CompletionResult.Failed ->
                            _googleFeedback.value = GoogleIntegrationSignInPolicy.dataAuthorizationFailureFeedback(
                                GoogleSignInFailureClassifier.classifyCode(completion.code, completion.message)
                            )
                    }
                }
            }
            is GoogleDataAuthorization.ConsentRequired -> {
                _googleAuthorizationEffects.tryEmit(
                    GoogleAuthorizationEffect.LaunchConsent(result.pendingIntent)
                )
            }
            GoogleDataAuthorization.Cancelled -> {
                pendingGoogleScopes = emptySet()
                googleAuthService.clearDataAccessToken(surfaceId)
                _googleFeedback.value = GoogleIntegrationSignInPolicy.authorizationCancelledFeedback()
            }
            GoogleDataAuthorization.Unavailable -> {
                pendingGoogleScopes = emptySet()
                googleAuthService.clearDataAccessToken(surfaceId)
                _googleFeedback.value = GoogleIntegrationSignInPolicy.authorizationFailedFeedback()
            }
            is GoogleDataAuthorization.Failed -> {
                pendingGoogleScopes = emptySet()
                googleAuthService.clearDataAccessToken(surfaceId)
                _googleFeedback.value = GoogleIntegrationSignInPolicy.dataAuthorizationFailureFeedback(result.reason)
            }
        }
    }

    fun onGoogleSignInCancelled() {
        if (!ConnectorScreenIsolationPolicy.acceptsResult(pendingGoogleSurfaceId, activeConnectorId)) return
        _googleFeedback.value = GoogleIntegrationSignInPolicy.cancelledFeedback()
    }

    fun onGoogleSignInFailed(error: Throwable? = null) {
        if (!ConnectorScreenIsolationPolicy.acceptsResult(pendingGoogleSurfaceId, activeConnectorId)) return
        _googleFeedback.value = error?.let {
            GoogleIntegrationSignInPolicy.failureFeedback(GoogleSignInFailureClassifier.classify(it))
        } ?: GoogleIntegrationSignInPolicy.providerFailureFeedback()
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
        // MainActivity owns state consumption and code exchange. Refresh only
        // the UI projection; never consume or exchange the callback twice.
        // A provider denial has no authorization code but still has a valid
        // state, so it must refresh the visible failure state as well.
        if (state.isBlank()) return
        refreshGithubProjectAuthorizationState()
    }

    init {
        viewModelScope.launch {
            ServiceLocator.workspaceRuntime.activeSession.collect { project ->
                refreshGithubProjectAuthorizationState(project)
            }
        }

        // Subscribe to OAuth deep-link callbacks from MainActivity
        viewModelScope.launch {
            com.airi.assistant.domain.event.EventBus.events.collect { event ->
                if (event is com.airi.assistant.domain.event.AppEvent.OAuthCallbackReceived) {
                    if (event.provider == "github") {
                        handleOAuthCallback(code = event.code, state = event.state)
                    }
                }
            }
        }
    }

    fun disconnect(id: String) {
        viewModelScope.launch {
            if (id == "github") {
                disconnectGithubProject()
                return@launch
            }
            authorizationManager.disconnect(id)
            refresh()
        }
    }

    private fun githubProjectState(): GithubAuthorizationState =
        ServiceLocator.workspaceRuntime.activeSession.value?.sessionId
            ?.takeIf(String::isNotBlank)
            ?.let(githubAuthorizationCoordinator::stateFor)
            ?: GithubAuthorizationState.SignedOut

    private fun githubProjectReadiness(): IntegrationReadiness {
        if (githubProjectState() !is GithubAuthorizationState.Ready) return IntegrationReadiness.DISCONNECTED
        val runtime = ServiceLocator.connectorRegistry.get("github")?.state()?.value
        return if (runtime?.let { it.connected && it.healthy } == true) {
            IntegrationReadiness.READY
        } else {
            IntegrationReadiness.DISCONNECTED
        }
    }

    private fun githubProjectAccountLogin(): String =
        if (githubProjectReadiness() == IntegrationReadiness.READY) {
            (githubProjectState() as? GithubAuthorizationState.Ready)?.session?.accountLogin.orEmpty()
        } else ""
}
