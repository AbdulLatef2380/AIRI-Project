package com.airi.assistant.execution

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class ExecutionGenerationGate {

    private val generationId = AtomicLong(0L)
    private val cancelled = AtomicBoolean(false)

    fun beginGeneration(): Long {
        cancelled.set(false)
        return generationId.incrementAndGet()
    }

    fun cancel() {
        cancelled.set(true)
    }

    fun resetCancel() {
        cancelled.set(false)
    }

    fun currentGenerationId(): Long = generationId.get()

    fun isCancelled(): Boolean = cancelled.get()

    fun accepts(candidateGenerationId: Long): Boolean =
        candidateGenerationId == generationId.get() && !cancelled.get()
}
