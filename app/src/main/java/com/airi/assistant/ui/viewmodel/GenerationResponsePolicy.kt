package com.airi.assistant.ui.viewmodel

import com.airi.assistant.agent.loop.AgentLoop

/** Terminal classification for one user-visible generation. */
enum class GenerationResponseStatus {
    SUCCESS,
    INCOMPLETE,
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

    /**
     * A provider may report a normal terminal callback while the payload still
     * contains an unclosed fenced artifact. Keep that distinction visible to
     * the UI instead of presenting a cut-off program as a finished answer.
     */
    fun classify(terminalState: AgentLoop.TerminalState, content: String): GenerationResponseStatus {
        val base = classify(terminalState)
        return if (base == GenerationResponseStatus.SUCCESS && hasUnclosedFence(content)) {
            GenerationResponseStatus.INCOMPLETE
        } else base
    }

    fun hasUnclosedFence(content: String): Boolean =
        content.countOccurrences("```") % 2 == 1

    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            val found = indexOf(needle, index)
            if (found < 0) return count
            count++
            index = found + needle.length
        }
    }

    /** Preserve useful streamed content on failure/timeout without treating it as a completed answer. */
    fun shouldPersistIncompleteResponse(status: GenerationResponseStatus, content: String): Boolean =
        content.isNotBlank() && (status == GenerationResponseStatus.INCOMPLETE || status == GenerationResponseStatus.FAILURE || status == GenerationResponseStatus.TIMEOUT)
}
