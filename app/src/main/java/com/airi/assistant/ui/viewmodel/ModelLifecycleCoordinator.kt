package com.airi.assistant.ui.viewmodel

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns idempotent lifecycle boundaries for model-owned resources.
 *
 * The coordinator intentionally exposes commands rather than a mutable UI
 * state. ChatViewModel remains the presentation owner and supplies the
 * Android composition-root actions through constructor-injected callbacks.
 */
internal class ModelLifecycleCoordinator(
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    fun startOnce() {
        if (stopped.get()) return
        if (started.compareAndSet(false, true)) {
            onStart()
        }
    }

    fun stopOnce() {
        if (stopped.compareAndSet(false, true) && started.get()) {
            onStop()
        }
    }
}
