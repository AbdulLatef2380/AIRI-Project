package com.airi.assistant.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.integrations.github.GithubService
import com.airi.assistant.integrations.google.GoogleAuthService
import com.airi.assistant.integrations.telegram.TelegramService
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class IntegrationsViewModel(application: Application) : AndroidViewModel(application) {

    val secureStorage = SecureStorage(application)
    val githubService = GithubService(secureStorage)
    val telegramService = TelegramService(secureStorage)
    val googleAuthService = GoogleAuthService(application, secureStorage)

    // ─── Integration UI State ──────────────────────────────────────────────────

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

    private fun buildItems(): List<IntegrationItem> = listOf(
        IntegrationItem(
            id = "github",
            name = "GitHub",
            description = "Connect repositories, issues, and coding context.",
            emoji = "🐙",
            isConnected = secureStorage.isGithubConnected(),
            connectedAs = secureStorage.getGithubUsername(),
            lastUpdated = secureStorage.getGithubUpdated()
        ),
        IntegrationItem(
            id = "telegram",
            name = "Telegram",
            description = "Link a Telegram bot for messaging workflows.",
            emoji = "✈️",
            isConnected = secureStorage.isTelegramConnected(),
            connectedAs = secureStorage.getTelegramUsername(),
            lastUpdated = secureStorage.getTelegramUpdated()
        ),
        IntegrationItem(
            id = "google",
            name = "Google",
            description = "Access Gmail, Drive, and Calendar (read-only).",
            emoji = "🔵",
            isConnected = secureStorage.isGoogleConnected(),
            connectedAs = secureStorage.getGoogleEmail() ?: "",
            lastUpdated = secureStorage.getGoogleUpdated()
        )
    )

    // ─── Dialog State ─────────────────────────────────────────────────────────

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

    fun openGithubDialog() { _dialog.value = DialogState.Github() }
    fun openTelegramDialog() { _dialog.value = DialogState.Telegram() }
    fun closeDialog() { _dialog.value = DialogState.None }

    fun updateGithubToken(token: String) {
        val current = _dialog.value as? DialogState.Github ?: return
        _dialog.value = current.copy(token = token, error = null)
    }

    fun updateTelegramToken(token: String) {
        val current = _dialog.value as? DialogState.Telegram ?: return
        _dialog.value = current.copy(token = token, error = null)
    }

    // ─── Connect / Disconnect ─────────────────────────────────────────────────

    fun connectGithub() {
        val current = _dialog.value as? DialogState.Github ?: return
        if (current.token.isBlank()) {
            _dialog.value = current.copy(error = "Please paste your GitHub token")
            return
        }
        _dialog.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            githubService.validateAndConnect(current.token)
                .onSuccess {
                    _dialog.value = DialogState.None
                    refresh()
                }
                .onFailure { e ->
                    _dialog.value = current.copy(loading = false, error = e.message ?: "Connection failed")
                }
        }
    }

    fun connectTelegram() {
        val current = _dialog.value as? DialogState.Telegram ?: return
        if (current.token.isBlank()) {
            _dialog.value = current.copy(error = "Please paste your bot token")
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

    fun disconnect(id: String) {
        when (id) {
            "google" -> googleAuthService.disconnect()
            else -> secureStorage.disconnect(id)
        }
        refresh()
    }
}
