package com.airi.assistant.agent.loop.tool

/**
 * Request-scoped terminal negotiation. It describes why terminal is visible or
 * blocked without making execution itself possible; the gateway remains the
 * final fail-closed authority.
 */
object TerminalCapabilityNegotiation {
    data class Projection(
        val availability: RuntimeCapabilityInventory.Availability,
        val detail: String,
        val inRequestCatalog: Boolean,
        val exposedToAgent: Boolean,
        val admittedToDispatcher: Boolean,
    )

    fun project(
        registered: Boolean,
        sandboxReady: Boolean,
        policyAllowed: Boolean,
        requested: Boolean,
        exposed: Boolean,
        admitted: Boolean,
    ): Projection {
        val availability = when {
            !registered || !sandboxReady -> RuntimeCapabilityInventory.Availability.CONFIGURATION_REQUIRED
            !policyAllowed -> RuntimeCapabilityInventory.Availability.BLOCKED
            else -> RuntimeCapabilityInventory.Availability.READY
        }
        val detail = when (availability) {
            RuntimeCapabilityInventory.Availability.CONFIGURATION_REQUIRED ->
                "Terminal is registered but no task-owned ephemeral workspace is available. Create a sandbox session before execution."
            RuntimeCapabilityInventory.Availability.BLOCKED ->
                "The terminal command is blocked by terminal governance; no process may be started."
            else ->
                "Terminal uses a task-owned ephemeral workspace and remains subject to gateway governance and output limits."
        }
        return Projection(
            availability = availability,
            detail = detail,
            inRequestCatalog = requested,
            exposedToAgent = exposed && availability == RuntimeCapabilityInventory.Availability.READY,
            admittedToDispatcher = admitted && availability == RuntimeCapabilityInventory.Availability.READY,
        )
    }
}
