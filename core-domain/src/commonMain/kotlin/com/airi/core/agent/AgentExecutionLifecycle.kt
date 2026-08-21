package com.airi.core.agent

enum class AgentExecutionPhase {
    IDLE,
    RUNNING,
    CANCELLATION_REQUESTED,
    CANCELLED,
    SUCCEEDED,
    FAILED
}

data class AgentExecutionLifecycle(
    val generationId: Long = 0L,
    val phase: AgentExecutionPhase = AgentExecutionPhase.IDLE
) {
    val isCancellationRequested: Boolean
        get() = phase == AgentExecutionPhase.CANCELLATION_REQUESTED ||
            phase == AgentExecutionPhase.CANCELLED

    fun beginGeneration(): AgentExecutionLifecycle = copy(
        generationId = generationId + 1,
        phase = AgentExecutionPhase.RUNNING
    )

    fun requestCancellation(): AgentExecutionLifecycle = copy(
        phase = AgentExecutionPhase.CANCELLATION_REQUESTED
    )

    fun markCancelled(): AgentExecutionLifecycle = when (phase) {
        AgentExecutionPhase.RUNNING,
        AgentExecutionPhase.CANCELLATION_REQUESTED -> copy(phase = AgentExecutionPhase.CANCELLED)

        else -> this
    }

    fun markSucceeded(): AgentExecutionLifecycle = when (phase) {
        AgentExecutionPhase.RUNNING -> copy(phase = AgentExecutionPhase.SUCCEEDED)
        else -> this
    }

    fun markFailed(): AgentExecutionLifecycle = when (phase) {
        AgentExecutionPhase.RUNNING -> copy(phase = AgentExecutionPhase.FAILED)
        else -> this
    }

    fun resumeCurrentGeneration(): AgentExecutionLifecycle = when (phase) {
        AgentExecutionPhase.CANCELLATION_REQUESTED,
        AgentExecutionPhase.CANCELLED -> copy(phase = AgentExecutionPhase.RUNNING)

        else -> this
    }

    fun accepts(candidateGenerationId: Long): Boolean =
        candidateGenerationId == generationId && phase == AgentExecutionPhase.RUNNING
}
