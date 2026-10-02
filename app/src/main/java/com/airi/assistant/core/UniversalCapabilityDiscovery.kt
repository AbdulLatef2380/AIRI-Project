package com.airi.assistant.core

/**
 * PHASE 1 minimal capability discovery. This is a pure, read-only normalizer:
 * it consumes observations already available in the runtime and never connects,
 * authenticates, executes, or mutates a provider.
 */
object UniversalCapabilityDiscovery {

    fun snapshot(observations: Iterable<CapabilityObservation>): List<CapabilitySnapshotEntry> =
        observations
            .filter { it.id.isNotBlank() }
            .groupBy { it.id.trim() }
            .map { (id, candidates) ->
                val selected = candidates.last()
                CapabilitySnapshotEntry(
                    id = id,
                    kind = selected.kind,
                    exists = candidates.any { it.exists == true },
                    registered = candidates.any { it.registered == true },
                    connected = selected.connected,
                    authenticated = selected.authenticated,
                    healthy = selected.healthy,
                    permitted = selected.permitted,
                    executable = selected.executable,
                    modelCompatible = selected.modelCompatible,
                    exposed = selected.exposed,
                    selected = selected.selected,
                    executed = selected.executed,
                    resultReturned = selected.resultReturned,
                )
            }
            .sortedBy { it.id }

    fun classify(entry: CapabilitySnapshotEntry): CapabilityDiscoveryOutcome = when {
        entry.exists != true -> CapabilityDiscoveryOutcome.NOT_FOUND
        entry.registered != true -> CapabilityDiscoveryOutcome.NOT_REGISTERED
        entry.connected == false || entry.healthy == false -> CapabilityDiscoveryOutcome.NOT_READY
        entry.authenticated == false -> CapabilityDiscoveryOutcome.AUTH_REQUIRED
        entry.permitted == false -> CapabilityDiscoveryOutcome.PERMISSION_DENIED
        entry.executable == false -> CapabilityDiscoveryOutcome.NOT_EXECUTABLE
        entry.modelCompatible == false -> CapabilityDiscoveryOutcome.MODEL_INCOMPATIBLE
        entry.exposed == false -> CapabilityDiscoveryOutcome.NOT_EXPOSED
        entry.selected == false -> CapabilityDiscoveryOutcome.NOT_SELECTED
        entry.executed == false -> CapabilityDiscoveryOutcome.NOT_EXECUTED
        entry.resultReturned == false -> CapabilityDiscoveryOutcome.RESULT_NOT_RETURNED
        else -> CapabilityDiscoveryOutcome.READY_OR_UNKNOWN
    }

    fun outcomeCounts(entries: Iterable<CapabilitySnapshotEntry>): Map<CapabilityDiscoveryOutcome, Int> =
        entries.groupingBy(::classify).eachCount()
}

data class CapabilityObservation(
    val id: String,
    val kind: String,
    val exists: Boolean? = null,
    val registered: Boolean? = null,
    val connected: Boolean? = null,
    val authenticated: Boolean? = null,
    val healthy: Boolean? = null,
    val permitted: Boolean? = null,
    val executable: Boolean? = null,
    val modelCompatible: Boolean? = null,
    val exposed: Boolean? = null,
    val selected: Boolean? = null,
    val executed: Boolean? = null,
    val resultReturned: Boolean? = null,
)

enum class CapabilityDiscoveryOutcome {
    NOT_FOUND,
    NOT_REGISTERED,
    NOT_READY,
    AUTH_REQUIRED,
    PERMISSION_DENIED,
    NOT_EXECUTABLE,
    MODEL_INCOMPATIBLE,
    NOT_EXPOSED,
    NOT_SELECTED,
    NOT_EXECUTED,
    RESULT_NOT_RETURNED,
    READY_OR_UNKNOWN,
}
