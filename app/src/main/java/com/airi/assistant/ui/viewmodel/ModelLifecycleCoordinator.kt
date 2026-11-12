package com.airi.assistant.ui.viewmodel

import com.airi.assistant.ai.ModelInfo
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
    private val onLoad: (ModelInfo) -> Unit,
    private val onUnload: () -> Unit,
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

    fun loadModel(model: ModelInfo) {
        if (started.get() && !stopped.get()) onLoad(model)
    }

    fun unloadModel() {
        if (started.get() && !stopped.get()) onUnload()
    }
}
