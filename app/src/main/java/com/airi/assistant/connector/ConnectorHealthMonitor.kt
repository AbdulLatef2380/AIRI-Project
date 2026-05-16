package com.airi.assistant.connector

import android.util.Log
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConnectorHealthMonitor(private val registry: ConnectorRegistry) {
    private val TAG = "ConnectorHealthMonitor"

    data class HealthEntry(val connectorId: String, val name: String, val isConnected: Boolean,
        val lastChecked: Long, val errorMessage: String? = null)

    private val _healthSummary = MutableStateFlow<List<HealthEntry>>(emptyList())
    val healthSummary: StateFlow<List<HealthEntry>> = _healthSummary.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { while (true) { checkAll(); delay(60_000L) } }
    }

    private suspend fun checkAll() {
        val results = mutableListOf<HealthEntry>()
        registry.all().forEach { connector ->
            scope.launch {
                val entry = runCatching {
                    val state = connector.state().value
                    HealthEntry(connector.id, connector.name, state.connected, System.currentTimeMillis(), state.errorMessage)
                }.getOrElse { e -> HealthEntry(connector.id, connector.name, false, System.currentTimeMillis(), e.message) }
                synchronized(results) { results.add(entry) }
                if (!entry.isConnected && entry.errorMessage != null)
                    AgentActivityBus.emit("Connector '${entry.name}' offline: ${entry.errorMessage.take(60)}", ActivityCategory.CONNECTOR, ActivitySeverity.WARN)
            }
        }
        delay(200)
        _healthSummary.value = results.sortedBy { it.connectorId }
    }
}
