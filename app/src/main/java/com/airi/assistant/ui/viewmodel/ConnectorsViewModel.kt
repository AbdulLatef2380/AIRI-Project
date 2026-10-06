package com.airi.assistant.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.connector.ConnectorAccessProfile
import com.airi.assistant.connector.ConnectorAvailability
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

/** ViewModel for the connector catalog, lifecycle controls, and user access grants. */
class ConnectorsViewModel(application: Application) : AndroidViewModel(application) {

    private val registry = ServiceLocator.connectorRegistry
    private val accessProfileStore = ServiceLocator.connectorAccessProfileStore

    private val _selectedTab = MutableStateFlow(ConnectorType.API)
    val selectedTab: StateFlow<ConnectorType> = _selectedTab.asStateFlow()

    private val _items = MutableStateFlow<List<ConnectorRow>>(emptyList())
    val items: StateFlow<List<ConnectorRow>> = _items.asStateFlow()

    private val _accessProfiles = MutableStateFlow<Map<String, ConnectorAccessProfile>>(emptyMap())
    val accessProfiles: StateFlow<Map<String, ConnectorAccessProfile>> = _accessProfiles.asStateFlow()

    init {
        viewModelScope.launch {
            registry.meta.collectLatest {
                val metas = registry.catalogMeta()
                refreshAccessProfiles(metas.map { it.id })
                observeItems(metas).collect { rows -> _items.value = rows }
            }
        }
    }

    fun selectTab(type: ConnectorType) {
        _selectedTab.value = type
    }

    fun connect(id: String) {
        val meta = registry.catalogMeta().firstOrNull { it.id == id } ?: return
        if (meta.runtimeId == id && registry.get(id) == null) return
        if (meta.availability == ConnectorAvailability.COMING_SOON) return
        viewModelScope.launch { registry.connect(meta.runtimeId) }
    }

    fun disconnect(id: String) {
        val runtimeId = registry.catalogMeta().firstOrNull { it.id == id }?.runtimeId ?: id
        if (registry.get(runtimeId) == null) return
        viewModelScope.launch { ServiceLocator.connectorAuthorizationManager.disconnect(runtimeId) }
    }

    fun setAccessProfile(surfaceId: String, profile: ConnectorAccessProfile) {
        if (_items.value.none { it.meta.id == surfaceId }) return
        accessProfileStore.set(surfaceId, profile)
        _accessProfiles.value = _accessProfiles.value.toMutableMap().apply {
            if (profile == ConnectorAccessProfile.NOT_CONFIGURED) remove(surfaceId)
            else put(surfaceId, profile)
        }
    }

    private fun refreshAccessProfiles(surfaceIds: List<String>) {
        _accessProfiles.value = surfaceIds.distinct().mapNotNull { surfaceId ->
            accessProfileStore.get(surfaceId)
                .takeIf { it != ConnectorAccessProfile.NOT_CONFIGURED }
                ?.let { surfaceId to it }
        }.toMap()
    }

    private fun observeItems(metas: List<ConnectorMeta>): Flow<List<ConnectorRow>> {
        if (metas.isEmpty()) return flowOf(emptyList())
        val stateFlows = metas.map { meta ->
            registry.get(meta.runtimeId)?.state() ?: flowOf(ConnectorState(connected = false))
        }
        return combine(stateFlows) { states ->
            metas.mapIndexed { index, meta -> ConnectorRow(meta = meta, state = states[index]) }
        }
    }

    data class ConnectorRow(val meta: ConnectorMeta, val state: ConnectorState)
}
