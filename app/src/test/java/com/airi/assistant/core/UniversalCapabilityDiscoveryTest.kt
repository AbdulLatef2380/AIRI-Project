package com.airi.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalCapabilityDiscoveryTest {

    @Test
    fun snapshot_isGenericAndDeduplicatesByStableId() {
        val entries = UniversalCapabilityDiscovery.snapshot(
            listOf(
                CapabilityObservation(
                    id = "future_connector",
                    kind = "connector",
                    exists = true,
                    registered = false,
                    connected = false,
                    exposed = false,
                ),
                CapabilityObservation(
                    id = "future_connector",
                    kind = "connector",
                    exists = true,
                    registered = true,
                    connected = true,
                    healthy = true,
                    executable = true,
                    exposed = true,
                ),
                CapabilityObservation(
                    id = "local_tool",
                    kind = "tool",
                    exists = true,
                    registered = true,
                    exposed = true,
                ),
            )
        )

        assertEquals(listOf("future_connector", "local_tool"), entries.map { it.id })
        assertEquals(2, entries.size)
        assertEquals(true, entries.first().registered)
        assertEquals(true, entries.first().exposed)
    }

    @Test
    fun classify_distinguishesNotRegisteredNotReadyAndNotExposed() {
        val observations = listOf(
            CapabilityObservation("catalog_only", "connector", exists = true, registered = false),
            CapabilityObservation("offline", "connector", exists = true, registered = true, connected = false),
            CapabilityObservation("hidden", "connector", exists = true, registered = true, connected = true, healthy = true, executable = true, exposed = false),
        )
        val entries = UniversalCapabilityDiscovery.snapshot(observations)

        assertEquals(
            CapabilityDiscoveryOutcome.NOT_REGISTERED,
            UniversalCapabilityDiscovery.classify(entries.first { it.id == "catalog_only" })
        )
        assertEquals(
            CapabilityDiscoveryOutcome.NOT_READY,
            UniversalCapabilityDiscovery.classify(entries.first { it.id == "offline" })
        )
        assertEquals(
            CapabilityDiscoveryOutcome.NOT_EXPOSED,
            UniversalCapabilityDiscovery.classify(entries.first { it.id == "hidden" })
        )
    }

    @Test
    fun outcomeCounts_supportsReadOnlyMatrixAggregation() {
        val entries = UniversalCapabilityDiscovery.snapshot(
            listOf(
                CapabilityObservation("a", "connector", exists = true, registered = false),
                CapabilityObservation("b", "connector", exists = true, registered = false),
                CapabilityObservation("c", "tool", exists = true, registered = true, exposed = true),
            )
        )

        val counts = UniversalCapabilityDiscovery.outcomeCounts(entries)
        assertEquals(2, counts[CapabilityDiscoveryOutcome.NOT_REGISTERED])
        assertEquals(1, counts[CapabilityDiscoveryOutcome.READY_OR_UNKNOWN])
        assertTrue(counts.values.sum() == entries.size)
    }
}
