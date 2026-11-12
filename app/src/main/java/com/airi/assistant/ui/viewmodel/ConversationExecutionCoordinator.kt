package com.airi.assistant.ui.viewmodel

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the command boundary for a conversation execution owner.
 *
 * The coordinator does not know Android, Compose, or StateFlow. It serializes
 * the lifecycle contract: dispatch/cancel are rejected after close, and
 * teardown is invoked exactly once. ChatViewModel remains the owner of the
 * visible state and supplies the execution adapter through callbacks.
 */
internal class ConversationExecutionCoordinator(
    private val execute: (Command) -> Boolean,
    private val cancelExecution: (clearQueuedRequest: Boolean) -> Unit,
    private val teardown: () -> Unit,
) {
    data class Command(
        val input: String,
        val cloudAttachmentConsent: Boolean,
    )

    private val closed = AtomicBoolean(false)

    fun dispatch(command: Command): Boolean {
        if (closed.get()) return false
        return execute(command)
    }

    fun cancel(clearQueuedRequest: Boolean): Boolean {
        if (closed.get()) return false
        cancelExecution(clearQueuedRequest)
        return true
    }

    fun closeOnce() {
        if (closed.compareAndSet(false, true)) {
            teardown()
        }
    }
}
