package com.airi.assistant.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import com.airi.assistant.core.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * ViewModel for [com.airi.assistant.ui.screens.ConnectorsScreen].
 *
 * Owns the active tab + a derived list of connectors filtered by tab.
 * Connect / disconnect actions are dispatched through the registry.
 */
class ConnectorsViewModel(application: Application) : AndroidViewModel(application) {

    private val registry = ServiceLocator.connectorRegistry

    private val _selectedTab = MutableStateFlow(ConnectorType.API)
    val selectedTab: StateFlow<ConnectorType> = _selectedTab.asStateFlow()

    /** All registered connectors as (meta + current state). Recomputes
     *  whenever the registry changes or any connector emits new state. */
    private val _items = MutableStateFlow<List<ConnectorRow>>(emptyList())
    val items: StateFlow<List<ConnectorRow>> = _items.asStateFlow()

    init {
        // Subscribe to registry meta changes; for each connector also
        // subscribe to its state flow so the row stays live.
        viewModelScope.launch {
            registry.meta.collect { metas ->
                refreshItems(metas)
            }
        }
    }

    fun selectTab(type: ConnectorType) {
        _selectedTab.value = type
    }

    fun connect(id: String) {
        val c = registry.get(id) ?: return
        viewModelScope.launch {
            runCatching { c.connect() }
            refreshItems(registry.meta.value)
        }
    }

    fun disconnect(id: String) {
        val c = registry.get(id) ?: return
        viewModelScope.launch {
            runCatching { c.disconnect() }
            refreshItems(registry.meta.value)
        }
    }

    private fun refreshItems(metas: List<ConnectorMeta>) {
        _items.value = metas.map { m ->
            val live = registry.get(m.id)
            ConnectorRow(
                meta  = m,
                state = live?.state()?.value ?: ConnectorState(connected = false),
            )
        }
    }

    /** UI projection: meta + last-known state. */
    data class ConnectorRow(
        val meta: ConnectorMeta,
        val state: ConnectorState,
    )
}
