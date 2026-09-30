package com.airi.assistant.ui.viewmodel

import com.airi.assistant.agent.loop.AgentLoop

/** Terminal classification for one user-visible generation. */
enum class GenerationResponseStatus {
    SUCCESS,
    EMPTY_RESPONSE,
    CANCELLED,
    TIMEOUT,
    FAILURE,
}

object GenerationResponsePolicy {
    /** The semantic terminal state is authoritative; partial streamed text never upgrades a failure. */
    fun classify(terminalState: AgentLoop.TerminalState): GenerationResponseStatus = when (terminalState) {
        AgentLoop.TerminalState.SUCCESS -> GenerationResponseStatus.SUCCESS
        AgentLoop.TerminalState.NO_RESPONSE -> GenerationResponseStatus.EMPTY_RESPONSE
        AgentLoop.TerminalState.CANCELLED -> GenerationResponseStatus.CANCELLED
        AgentLoop.TerminalState.TIMEOUT -> GenerationResponseStatus.TIMEOUT
        AgentLoop.TerminalState.FAILURE -> GenerationResponseStatus.FAILURE
    }

    /** Preserve useful streamed content on failure/timeout without treating it as a completed answer. */
    fun shouldPersistIncompleteResponse(status: GenerationResponseStatus, content: String): Boolean =
        content.isNotBlank() && (status == GenerationResponseStatus.FAILURE || status == GenerationResponseStatus.TIMEOUT)
}
