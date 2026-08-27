package com.airi.assistant.core

/**
 * Owns tool-action lifecycle admission for one active execution trace.
 *
 * This class intentionally has no Android or UI dependency so stale events,
 * duplicate terminal events, and invalid action identities can be tested on
 * the JVM before they are projected into a user-visible trace.
 */
class ExecutionToolTraceLifecycle {
    private var activeExecutionId: String? = null
    private val activeActionIds = mutableSetOf<String>()
    private val terminalActionIds = mutableSetOf<String>()

    @Synchronized
    fun begin(executionId: String) {
        require(executionId.isNotBlank()) { "executionId is required" }
        activeExecutionId = executionId
        activeActionIds.clear()
        terminalActionIds.clear()
    }

    /** Admits exactly one start event for an action belonging to the active execution. */
    @Synchronized
    fun admitStart(executionId: String, actionId: String): Boolean {
        if (!belongsToActiveExecution(executionId) || actionId.isBlank()) return false
        if (actionId in activeActionIds || actionId in terminalActionIds) return false
        activeActionIds += actionId
        return true
    }

    /** Admits one terminal event only after its matching start event. */
    @Synchronized
    fun admitTerminal(executionId: String, actionId: String): Boolean {
        if (!belongsToActiveExecution(executionId) || actionId.isBlank()) return false
        if (!activeActionIds.remove(actionId)) return false
        return terminalActionIds.add(actionId)
    }

    @Synchronized
    private fun belongsToActiveExecution(executionId: String): Boolean =
        executionId.isNotBlank() && executionId == activeExecutionId
}
