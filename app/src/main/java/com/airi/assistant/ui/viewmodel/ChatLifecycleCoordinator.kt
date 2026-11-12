package com.airi.assistant.ui.viewmodel

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the outer lifecycle boundary of one ChatViewModel instance.
 *
 * Inner owners (conversation execution and model lifecycle) remain responsible
 * for their own cleanup; this boundary guarantees that they and Android-facing
 * adapters are closed exactly once and that no cleanup is published twice.
 */
internal class ChatLifecycleCoordinator(
    private val onStart: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun startOnce() {
        if (closed.get()) return
        if (started.compareAndSet(false, true)) onStart()
    }

    fun closeOnce() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}
