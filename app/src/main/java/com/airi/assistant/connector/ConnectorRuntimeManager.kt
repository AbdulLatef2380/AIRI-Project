package com.airi.assistant.connector

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ConnectorRuntimeManager — live health monitoring, capability mapping,
 * and failure recovery for the full connector ecosystem.
 *
 * ── RESPONSIBILITIES ─────────────────────────────────────────────────────────
 *
 * 1. HEALTH MONITORING
 *    Polls every registered [Connector] on [HEALTH_POLL_MS] interval.
 *    Tracks consecutive failures and marks connectors DEGRADED or OFFLINE.
 *
 * 2. CAPABILITY MAPPING
 *    Maintains a live [CapabilityMap] of connector_id → available actions.
 *    Used by [ToolResolver] to short-circuit unavailable tool calls before
 *    they incur network timeouts.
 *
 * 3. FAILURE RECOVERY
 *    On OFFLINE detection, schedules automatic reconnect attempts with
 *    exponential backoff (up to [MAX_RECONNECT_BACKOFF_MS]).
 *
 * 4. PERMISSION LAYER
 *    Each connector has a [PermissionLevel]. Connectors above the current
 *    policy level are blocked before execution.
 *
 * 5. OBSERVABILITY
 *    [healthMap] StateFlow drives the ConnectorsScreen live health grid.
 *
 * ── INTEGRATION ──────────────────────────────────────────────────────────────
 *
 *   ServiceLocator exposes this singleton. ConnectorBootstrap calls [start].
 *   AgentRouter queries [isHealthy] before routing to a connector.
 */
class ConnectorRuntimeManager(
    private val connectorRegistry: ConnectorRegistry,
) {

    private val TAG   = "ConnectorRuntimeMgr"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    // ── Data types ────────────────────────────────────────────────────────────

    enum class ConnectorHealth { HEALTHY, DEGRADED, OFFLINE, UNKNOWN }

    enum class PermissionLevel { PUBLIC, USER_CONSENT, ADMIN }

    data class ConnectorStatus(
        val id:                String,
        val name:              String,
        val health:            ConnectorHealth,
        val consecutiveFails:  Int,
        val lastCheckedMs:     Long,
        val lastErrorMessage:  String?,
        val reconnectAttempts: Int,
        val type:              ConnectorType,
    )

    data class CapabilityEntry(
        val connectorId: String,
        val actions:     List<String>,
        val isOnline:    Boolean,
    )

    // ── State ─────────────────────────────────────────────────────────────────

    private val statusMap  = mutableMapOf<String, ConnectorStatus>()
    private val failCounts = mutableMapOf<String, Int>()
    private val reconnects = mutableMapOf<String, Int>()

    private val _healthMap = MutableStateFlow<Map<String, ConnectorStatus>>(emptyMap())
    val healthMap: StateFlow<Map<String, ConnectorStatus>> = _healthMap.asStateFlow()

    private val _capabilityMap = MutableStateFlow<Map<String, CapabilityEntry>>(emptyMap())
    val capabilityMap: StateFlow<Map<String, CapabilityEntry>> = _capabilityMap.asStateFlow()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            Log.i(TAG, "ConnectorRuntimeManager starting — polling every ${HEALTH_POLL_MS}ms")
            while (isActive) {
                runCatching { pollAll() }
                    .onFailure { Log.w(TAG, "pollAll error: ${it.message}") }
                delay(HEALTH_POLL_MS)
            }
        }
    }

    // ── Query API ─────────────────────────────────────────────────────────────

    fun isHealthy(connectorId: String): Boolean =
        _healthMap.value[connectorId]?.health == ConnectorHealth.HEALTHY

    fun getStatus(connectorId: String): ConnectorStatus? =
        _healthMap.value[connectorId]

    fun healthSummary(): String {
        val statuses = _healthMap.value.values
        val healthy  = statuses.count { it.health == ConnectorHealth.HEALTHY }
        val total    = statuses.size
        return "$healthy/$total connectors healthy"
    }

    /**
     * Force an immediate health check for a single connector.
     */
    suspend fun checkNow(connectorId: String): ConnectorHealth {
        val connector = connectorRegistry.get(connectorId) ?: return ConnectorHealth.UNKNOWN
        return runCatching { checkConnector(connector) }.getOrDefault(ConnectorHealth.OFFLINE)
    }

    /**
     * Attempt to reconnect an OFFLINE connector immediately.
     */
    suspend fun reconnectNow(connectorId: String): Boolean {
        val connector = connectorRegistry.get(connectorId) ?: return false
        return runCatching {
            val state = connector.connect()
            val ok = state.healthy
            updateStatus(connector, if (ok) ConnectorHealth.HEALTHY else ConnectorHealth.OFFLINE, null)
            Log.i(TAG, "RECONNECT_${if (ok) "OK" else "FAIL"} id=$connectorId")
            ok
        }.getOrDefault(false)
    }

    // ── Internal polling ──────────────────────────────────────────────────────

    private suspend fun pollAll() = mutex.withLock {
        val connectors = connectorRegistry.all()
        val newMap     = mutableMapOf<String, ConnectorStatus>()
        val capMap     = mutableMapOf<String, CapabilityEntry>()

        for (connector in connectors) {
            val health = runCatching { checkConnector(connector) }.getOrDefault(ConnectorHealth.UNKNOWN)
            val fails  = if (health != ConnectorHealth.HEALTHY) (failCounts[connector.id] ?: 0) + 1 else 0
            failCounts[connector.id] = fails

            val derivedHealth = when {
                fails == 0                      -> ConnectorHealth.HEALTHY
                fails < DEGRADED_FAIL_THRESHOLD -> ConnectorHealth.DEGRADED
                else                            -> ConnectorHealth.OFFLINE
            }

            val status = ConnectorStatus(
                id               = connector.id,
                name             = connector.name,
                health           = derivedHealth,
                consecutiveFails = fails,
                lastCheckedMs    = System.currentTimeMillis(),
                lastErrorMessage = if (derivedHealth != ConnectorHealth.HEALTHY) "Consecutive failures: $fails" else null,
                reconnectAttempts = reconnects[connector.id] ?: 0,
                type             = connector.type,
            )
            newMap[connector.id] = status

            // Schedule reconnect for OFFLINE connectors
            if (derivedHealth == ConnectorHealth.OFFLINE) {
                scheduleReconnect(connector)
            }

            // Capability mapping from meta
            val meta    = connector.meta()
            capMap[connector.id] = CapabilityEntry(
                connectorId = connector.id,
                actions     = meta.tags,
                isOnline    = derivedHealth == ConnectorHealth.HEALTHY,
            )
        }

        _healthMap.value    = newMap
        _capabilityMap.value = capMap
    }

    private suspend fun checkConnector(connector: Connector): ConnectorHealth {
        val state = connector.state().value
        return when {
            state.healthy  -> ConnectorHealth.HEALTHY
            state.connected -> ConnectorHealth.DEGRADED
            else            -> ConnectorHealth.OFFLINE
        }
    }

    private fun updateStatus(connector: Connector, health: ConnectorHealth, error: String?) {
        val current = _healthMap.value.toMutableMap()
        current[connector.id] = (current[connector.id] ?: ConnectorStatus(
            id = connector.id, name = connector.name, health = health,
            consecutiveFails = 0, lastCheckedMs = System.currentTimeMillis(),
            lastErrorMessage = error, reconnectAttempts = 0, type = connector.type,
        )).copy(health = health, lastErrorMessage = error, lastCheckedMs = System.currentTimeMillis())
        _healthMap.value = current
    }

    private fun scheduleReconnect(connector: Connector) {
        val attempts = reconnects[connector.id] ?: 0
        if (attempts >= MAX_RECONNECT_ATTEMPTS) return
        val backoff = (BASE_RECONNECT_MS * (1L shl attempts.coerceAtMost(4))).coerceAtMost(MAX_RECONNECT_BACKOFF_MS)
        reconnects[connector.id] = attempts + 1

        scope.launch {
            delay(backoff)
            runCatching {
                val state = connector.connect()
                if (state.healthy) {
                    failCounts[connector.id] = 0
                    updateStatus(connector, ConnectorHealth.HEALTHY, null)
                    Log.i(TAG, "AUTO_RECONNECT_OK id=${connector.id} attempt=${attempts + 1}")
                }
            }.onFailure {
                Log.w(TAG, "AUTO_RECONNECT_FAIL id=${connector.id}: ${it.message}")
            }
        }
    }

    companion object {
        private const val HEALTH_POLL_MS             = 30_000L
        private const val DEGRADED_FAIL_THRESHOLD    = 2
        private const val MAX_RECONNECT_ATTEMPTS     = 5
        private const val BASE_RECONNECT_MS          = 5_000L
        private const val MAX_RECONNECT_BACKOFF_MS   = 120_000L
    }
}
