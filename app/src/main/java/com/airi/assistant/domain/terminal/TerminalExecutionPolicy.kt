package com.airi.assistant.domain.terminal

/**
 * Product gate for terminal execution.
 * PR-10 deliberately keeps ProcessBuilder-backed execution disabled until an
 * isolated Android service and its IPC/FD contract are proven.
 */
object TerminalExecutionPolicy {
    const val DISABLED_REASON =
        "Terminal execution is disabled until an isolated process boundary is proven"

    data class Decision(val allowed: Boolean, val reason: String)

    fun evaluate(command: String): Decision = when {
        command.isBlank() -> Decision(false, "Terminal command is empty")
        command.length > MAX_COMMAND_CHARS -> Decision(false, "Terminal command exceeds the input limit")
        else -> Decision(false, DISABLED_REASON)
    }

    private const val MAX_COMMAND_CHARS = 8_192
}
