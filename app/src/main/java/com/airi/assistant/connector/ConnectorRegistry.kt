package com.airi.assistant.connector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of [Connector]s. Single source of truth for what
 * the agent can call.
 *
 * Thread safety: backed by [ConcurrentHashMap]; mutating operations
 * ([register], [unregister]) are safe to call from any thread. Lookups
 * ([get], [byType], [all]) are lock-free.
 *
 * Ownership: the registry holds connector instances by reference. It does
 * NOT call [Connector.connect] automatically — callers (typically
 * [ConnectorBootstrap]) decide when to connect. This keeps cold-start
 * cheap: registering 30 connectors does not open 30 HTTP clients.
 */
class ConnectorRegistry(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val store = ConcurrentHashMap<String, Connector>()

    private val _meta = MutableStateFlow<List<ConnectorMeta>>(emptyList())
    /** Observable list of all registered connectors' metadata, in
     *  insertion order. UI subscribes to this for the Connectors screen. */
    val meta: StateFlow<List<ConnectorMeta>> = _meta.asStateFlow()

    fun register(connector: Connector) {
        require(connector.id.isNotBlank()) { "Connector id must not be blank" }
        store[connector.id] = connector
        recomputeMeta()
    }

    fun unregister(id: String) {
        val removed = store.remove(id) ?: return
        // Best-effort disconnect; failure is logged by the connector itself.
        scope.launch { runCatching { removed.disconnect() } }
        recomputeMeta()
    }

    fun get(id: String): Connector? = store[id]

    fun all(): List<Connector> = store.values.toList()

    fun byType(type: ConnectorType): List<Connector> =
        store.values.filter { it.type == type }

    /** Convenience: connect every registered connector in parallel.
     *  Failures are isolated per connector (one bad connect does not
     *  stop the others). */
    fun connectAll() {
        for (c in store.values) {
            scope.launch { runCatching { c.connect() } }
        }
    }

    private fun recomputeMeta() {
        _meta.value = store.values.map { it.meta() }
    }
}
