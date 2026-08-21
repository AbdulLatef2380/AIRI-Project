package com.airi.assistant.execution

import com.airi.core.agent.AgentExecutionLifecycle
import java.util.concurrent.atomic.AtomicReference

internal class ExecutionGenerationGate {

    private val lifecycle = AtomicReference(AgentExecutionLifecycle())

    fun beginGeneration(): Long = update { it.beginGeneration() }.generationId

    fun cancel() {
        update { it.requestCancellation() }
    }

    fun resetCancel() {
        update { it.resumeCurrentGeneration() }
    }

    fun currentGenerationId(): Long = lifecycle.get().generationId

    fun isCancelled(): Boolean = lifecycle.get().isCancellationRequested

    fun accepts(candidateGenerationId: Long): Boolean =
        lifecycle.get().accepts(candidateGenerationId)

    private fun update(
        transform: (AgentExecutionLifecycle) -> AgentExecutionLifecycle
    ): AgentExecutionLifecycle {
        while (true) {
            val current = lifecycle.get()
            val updated = transform(current)
            if (lifecycle.compareAndSet(current, updated)) return updated
        }
    }
}
