package com.airi.assistant.core

import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivityEvent
import com.airi.assistant.ui.activity.ActivityMetadata
import com.airi.assistant.ui.activity.ActivityPhase
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.AgentActivityBus
import java.util.LinkedHashSet

/**
 * The single activity emission boundary for execution-owned runtime paths.
 *
 * ExecutionStatusBus remains the canonical structured lifecycle store. This
 * gateway is the compatibility/UI projection boundary: runtime components may
 * emit an execution activity here, but must not call AgentActivityBus directly.
 * The key is deliberately phase-aware so start/output/terminal evidence can
 * coexist while an accidental duplicate from another adapter is suppressed.
 */
object ExecutionTraceGateway {
    private const val MAX_DEDUPE_KEYS = 4_096
    private val lock = Any()
    private val emittedKeys = LinkedHashSet<String>()

    fun emitExecution(
        executionId: String?,
        actionId: String? = null,
        phase: String,
        message: String,
        category: ActivityCategory,
        severity: ActivitySeverity = ActivitySeverity.INFO,
        detail: String? = null,
        machineTag: String? = null,
        parentId: String? = null,
        emit: (ActivityEvent) -> Unit = AgentActivityBus::emit,
    ): Boolean {
        val normalizedExecution = executionId?.trim().orEmpty()
        val normalizedAction = actionId?.trim().orEmpty()
        val normalizedPhase = phase.trim().lowercase().ifBlank { "event" }
        val key = if (normalizedExecution.isNotBlank() && normalizedAction.isNotBlank()) {
            "$normalizedExecution\u0000$normalizedAction\u0000$normalizedPhase"
        } else {
            null
        }
        return emitActivity(
            event = ActivityEvent(
                message = message,
                detail = detail,
                executionId = normalizedExecution.takeIf { it.isNotBlank() },
                category = category,
                severity = severity,
                machineTag = machineTag ?: "trace_gateway:$normalizedPhase",
                metadata = ActivityMetadata(
                    phase = ActivityPhase.ACTION,
                    actionId = normalizedAction.takeIf { it.isNotBlank() },
                    parentId = parentId?.takeIf { it.isNotBlank() },
                ),
            ),
            dedupeKey = key,
            emit = emit,
        )
    }

    /** Routes an already-shaped event through the same single emission boundary. */
    fun emitActivity(
        event: ActivityEvent,
        dedupeKey: String? = null,
        emit: (ActivityEvent) -> Unit = AgentActivityBus::emit,
    ): Boolean {
        if (dedupeKey != null && !claim(dedupeKey)) return false
        emit(event)
        return true
    }

    private fun claim(key: String): Boolean = synchronized(lock) {
        if (!emittedKeys.add(key)) return false
        while (emittedKeys.size > MAX_DEDUPE_KEYS) {
            emittedKeys.iterator().apply { next(); remove() }
        }
        true
    }

    internal fun resetForTests() = synchronized(lock) { emittedKeys.clear() }
}
