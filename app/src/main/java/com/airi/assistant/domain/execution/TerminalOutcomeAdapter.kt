package com.airi.assistant.domain.execution

import java.util.concurrent.atomic.AtomicReference

/** Canonical terminal classification shared by agent, tool, and graph execution. */
enum class TerminalStatus { SUCCESS, FAILURE, TIMEOUT, CANCELLED }

data class TerminalOutcome(
    val status: TerminalStatus,
    val reason: String? = null,
)

/**
 * Delivers one terminal outcome per execution identity. Late success/failure/cancel callbacks
 * are ignored rather than producing contradictory terminal trace events.
 */
class TerminalOutcomeAdapter(
    private val onTerminal: (TerminalOutcome) -> Unit = {},
) {
    private val delivered = AtomicReference<TerminalOutcome?>(null)

    fun publish(status: TerminalStatus, reason: String? = null): Boolean {
        val outcome = TerminalOutcome(status, reason?.takeIf { it.isNotBlank() })
        if (!delivered.compareAndSet(null, outcome)) return false
        runCatching { onTerminal(outcome) }
        return true
    }

    fun outcome(): TerminalOutcome? = delivered.get()
}
