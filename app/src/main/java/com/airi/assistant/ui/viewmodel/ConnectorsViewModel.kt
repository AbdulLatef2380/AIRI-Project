package com.airi.assistant.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import com.airi.assistant.core.ServiceLocator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
        // The registry can add or remove connectors; each new metadata snapshot
        // replaces the previous state subscriptions. This keeps the UI live when
        // a connector changes authorization or health without being re-registered.
        viewModelScope.launch {
            registry.meta.collectLatest { metas ->
                observeItems(metas).collect { rows ->
                    _items.value = rows
                }
            }
        }
    }

    fun selectTab(type: ConnectorType) {
        _selectedTab.value = type
    }

    fun connect(id: String) {
        val connector = registry.get(id) ?: return
        viewModelScope.launch {
            runCatching { connector.connect() }
        }
    }

    fun disconnect(id: String) {
        val connector = registry.get(id) ?: return
        viewModelScope.launch {
            runCatching { connector.disconnect() }
        }
    }

    private fun observeItems(metas: List<ConnectorMeta>): Flow<List<ConnectorRow>> {
        if (metas.isEmpty()) return flowOf(emptyList())
        val stateFlows = metas.map { meta ->
            registry.get(meta.id)?.state() ?: flowOf(ConnectorState(connected = false))
        }
        return combine(stateFlows) { states ->
            metas.mapIndexed { index, meta ->
                ConnectorRow(meta = meta, state = states[index])
            }
        }
    }

    /** UI projection: meta + last-known state. */
    data class ConnectorRow(
        val meta: ConnectorMeta,
        val state: ConnectorState,
    )
}
