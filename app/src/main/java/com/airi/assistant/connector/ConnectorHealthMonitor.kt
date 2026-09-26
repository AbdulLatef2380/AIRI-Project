package com.airi.assistant.connector

import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull

/** Periodic state sampler; provider probing belongs to each connector's connect/execute contract. */
class ConnectorHealthMonitor(private val registry: ConnectorRegistry) {
    data class HealthEntry(
        val connectorId: String,
        val name: String,
        val isConnected: Boolean,
        val isHealthy: Boolean,
        val lastChecked: Long,
        val errorMessage: String? = null,
    )

    private val _healthSummary = MutableStateFlow<List<HealthEntry>>(emptyList())
    val healthSummary: StateFlow<List<HealthEntry>> = _healthSummary.asStateFlow()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    private val lastOfflineNotice = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun start() {
        if (monitorJob?.isActive == true) return
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        monitorJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                checkAll()
                delay(60_000L)
            }
        }
    }

    @Synchronized
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        scope.coroutineContext[Job]?.cancel()
        lastOfflineNotice.clear()
    }

    private suspend fun checkAll() {
        val activeIds = registry.all().mapTo(mutableSetOf()) { it.id }
        lastOfflineNotice.keys.removeIf { it !in activeIds }
        val results = coroutineScope {
            registry.all().map { connector ->
                async {
                    val now = System.currentTimeMillis()
                    val entry = withTimeoutOrNull(1_000L) {
                        try {
                            val state = connector.state().value
                            HealthEntry(connector.id, connector.name, state.connected, state.healthy, now, state.errorMessage)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            HealthEntry(connector.id, connector.name, false, false, now, error.message)
                        }
                    } ?: HealthEntry(connector.id, connector.name, false, false, now, "Health snapshot timed out")
                    entry
                }
            }.awaitAll()
        }
        results.forEach(::emitOfflineNotice)
        _healthSummary.value = results.sortedBy { it.connectorId }
    }

    private fun emitOfflineNotice(entry: HealthEntry) {
        if (entry.isConnected && entry.isHealthy) {
            lastOfflineNotice.remove(entry.connectorId)
            return
        }
        val now = System.currentTimeMillis()
        val previous = lastOfflineNotice[entry.connectorId] ?: 0L
        if (entry.errorMessage != null && now - previous >= 300_000L) {
            lastOfflineNotice[entry.connectorId] = now
            AgentActivityBus.emit(
                "Connector '${entry.name}' unavailable: ${entry.errorMessage.take(60)}",
                ActivityCategory.CONNECTOR,
                ActivitySeverity.WARN,
            )
        }
    }
}
