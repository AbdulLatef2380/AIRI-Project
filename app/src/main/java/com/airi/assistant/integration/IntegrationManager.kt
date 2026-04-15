package com.airi.assistant.integration

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class IntegrationManager(context: Context) {
    private val preferences = context.getSharedPreferences("airi_integrations", Context.MODE_PRIVATE)
    private val integrations = listOf(
        GithubIntegration(preferences),
        TelegramIntegration(preferences),
        NotionIntegration(preferences)
    )
    private val _states = MutableStateFlow(integrations.map { it.state() })
    val states: StateFlow<List<IntegrationState>> = _states.asStateFlow()

    fun connect(id: String) {
        integrations.firstOrNull { it.id == id }?.connect()
        refresh()
    }

    fun disconnect(id: String) {
        integrations.firstOrNull { it.id == id }?.disconnect()
        refresh()
    }

    fun refresh() {
        _states.value = integrations.map { it.state() }
    }
}
