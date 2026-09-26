package com.airi.assistant.connector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    private val registrationOrder = ConcurrentHashMap<String, Long>()
    private val sequence = AtomicLong(0L)
    private val explicitlyDisconnected = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleGeneration = ConcurrentHashMap<String, Long>()
    private val lifecycleMutex = Mutex()

    private val _meta = MutableStateFlow<List<ConnectorMeta>>(emptyList())
    private val _readiness = MutableStateFlow<ConnectorReadinessState>(ConnectorReadinessState.NotInitialized)
    val readiness: StateFlow<ConnectorReadinessState> = _readiness.asStateFlow()
    /** Observable list of all registered connectors' metadata, in
     *  insertion order. UI subscribes to this for the Connectors screen. */
    val meta: StateFlow<List<ConnectorMeta>> = _meta.asStateFlow()
    fun markInitializing() { _readiness.value = ConnectorReadinessState.Initializing }
    fun markReady() { _readiness.value = ConnectorReadinessState.Ready(store.size) }
    fun markDegraded(reason: String) { _readiness.value = ConnectorReadinessState.Degraded(store.size, reason.take(240)) }
    fun markFailed(reason: String) { _readiness.value = ConnectorReadinessState.Failed(reason.take(240)) }

    /** Catalog entries remain discoverable, but only registered adapters are executable. */
    fun catalogMeta(): List<ConnectorMeta> {
        val catalogIds = OfficialConnectorCatalog.all.mapTo(mutableSetOf()) { it.id }
        val catalogEntries = OfficialConnectorCatalog.all.map { definition ->
            get(definition.id)?.meta()?.withCatalogDefinition(definition)
                ?: definition.toConnectorMeta()
        }
        val liveOnly = meta.value.filterNot { it.id in catalogIds }.map { item ->
            if (item.type == ConnectorType.APP && item.availability == ConnectorAvailability.READY) {
                item.copy(availability = ConnectorAvailability.PARTIAL)
            } else item
        }
        return catalogEntries + liveOnly
    }

    fun catalogSearch(query: String): List<ConnectorMeta> {
        val q = query.trim()
        if (q.isEmpty()) return catalogMeta()
        return catalogMeta().filter { item ->
            listOf(item.id, item.name, item.description, item.provider.orEmpty(), item.category.orEmpty())
                .any { it.contains(q, ignoreCase = true) } ||
                item.tags.any { it.contains(q, ignoreCase = true) } ||
                item.capabilities.any { it.id.contains(q, ignoreCase = true) }
        }
    }

    fun catalogFilter(category: String? = null, availability: ConnectorAvailability? = null): List<ConnectorMeta> =
        catalogMeta().filter { item ->
            (category == null || item.category.equals(category, ignoreCase = true)) &&
                (availability == null || item.availability == availability)
        }

    /** Catalog entries remain discoverable, but only registered adapters are executable. */
    fun catalogMeta(): List<ConnectorMeta> {
        val catalogIds = OfficialConnectorCatalog.all.mapTo(mutableSetOf()) { it.id }
        val catalogEntries = OfficialConnectorCatalog.all.map { definition ->
            get(definition.id)?.meta()?.withCatalogDefinition(definition)
                ?: definition.toConnectorMeta()
        }
        val liveOnly = meta.value.filterNot { it.id in catalogIds }.map { item ->
            if (item.type == ConnectorType.APP && item.availability == ConnectorAvailability.READY) {
                item.copy(availability = ConnectorAvailability.PARTIAL)
            } else item
        }
        return catalogEntries + liveOnly
    }

    fun catalogSearch(query: String): List<ConnectorMeta> {
        val q = query.trim()
        if (q.isEmpty()) return catalogMeta()
        return catalogMeta().filter { item ->
            listOf(item.id, item.name, item.description, item.provider.orEmpty(), item.category.orEmpty())
                .any { it.contains(q, ignoreCase = true) } ||
                item.tags.any { it.contains(q, ignoreCase = true) } ||
                item.capabilities.any { it.id.contains(q, ignoreCase = true) }
        }
    }

    fun catalogFilter(category: String? = null, availability: ConnectorAvailability? = null): List<ConnectorMeta> =
        catalogMeta().filter { item ->
            (category == null || item.category.equals(category, ignoreCase = true)) &&
                (availability == null || item.availability == availability)
        }

    fun register(connector: Connector) {
        require(connector.id.isNotBlank()) { "Connector id must not be blank" }
        store[connector.id] = connector
        explicitlyDisconnected.remove(connector.id)
        lifecycleGeneration[connector.id] = 0L
        registrationOrder.putIfAbsent(connector.id, sequence.getAndIncrement())
        recomputeMeta()
    }

    fun unregister(id: String) {
        val removed = store.remove(id) ?: return
        explicitlyDisconnected.add(id)
        lifecycleGeneration[id] = (lifecycleGeneration[id] ?: 0L) + 1L
        registrationOrder.remove(id)
        // Best-effort disconnect; failure is logged by the connector itself.
        scope.launch { runCatching { removed.disconnect() } }
        recomputeMeta()
    }

    fun get(id: String): Connector? = store[id]

    fun isExplicitlyDisconnected(id: String): Boolean = explicitlyDisconnected.contains(id)

    suspend fun connect(id: String): ConnectorState = lifecycleMutex.withLock {
        val connector = get(id) ?: return ConnectorState(false, false, errorMessage = "Connector not registered")
        val token = (lifecycleGeneration[id] ?: 0L) + 1L
        lifecycleGeneration[id] = token
        return runCatching { connector.connect() }.getOrElse {
            explicitlyDisconnected.add(id)
            ConnectorState(false, false, errorMessage = it.message ?: "Connection failed")
        }.also {
            if (lifecycleGeneration[id] == token && it.connected && it.healthy) {
                explicitlyDisconnected.remove(id)
            }
        }
    }

    suspend fun disconnect(id: String): Boolean = lifecycleMutex.withLock {
        val connector = get(id) ?: return false
        val token = (lifecycleGeneration[id] ?: 0L) + 1L
        lifecycleGeneration[id] = token
        explicitlyDisconnected.add(id)
        return runCatching { connector.disconnect(); true }.getOrDefault(false)
    }

    fun all(): List<Connector> = store.values.sortedBy { registrationOrder[it.id] ?: Long.MAX_VALUE }

    fun byType(type: ConnectorType): List<Connector> =
        store.values.filter { it.type == type }
            .sortedBy { registrationOrder[it.id] ?: Long.MAX_VALUE }

    /** Search live metadata without exposing connector credentials or instances. */
    fun search(query: String): List<ConnectorMeta> {
        val q = query.trim()
        if (q.isEmpty()) return meta.value
        return meta.value.filter { item ->
            listOf(item.id, item.name, item.description, item.provider.orEmpty(), item.category.orEmpty())
                .any { it.contains(q, ignoreCase = true) } ||
                item.tags.any { it.contains(q, ignoreCase = true) } ||
                item.capabilities.any { it.id.contains(q, ignoreCase = true) }
        }
    }

    fun filter(category: String? = null, availability: ConnectorAvailability? = null): List<ConnectorMeta> =
        meta.value.filter { item ->
            (category == null || item.category.equals(category, ignoreCase = true)) &&
                (availability == null || item.availability == availability)
        }

    fun getCapabilities(id: String): List<ConnectorCapability> =
        get(id)?.meta()?.capabilities.orEmpty()

    fun getConnectionState(id: String): ConnectorState? = get(id)?.state()?.value

    fun getAuthenticationType(id: String): ConnectorAuthenticationType? =
        get(id)?.meta()?.authenticationType

    fun getRequiredPermissions(id: String): List<ConnectorPermissionLevel> =
        get(id)?.meta()?.capabilities.orEmpty().map { it.permission }.distinct()

    /** Convenience: connect every registered connector in parallel.
     *  Failures are isolated per connector (one bad connect does not
     *  stop the others). */
    fun connectAll() {
        for (c in store.values) {
            scope.launch { connect(c.id) }
        }
    }

    private fun recomputeMeta() {
        _meta.value = all().map { it.meta() }
    }
}
