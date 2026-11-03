package com.airi.assistant.agent.loop.tool

/**
 * Decides, token by token, whether streamed text may be shown to the user or is the start of a
 * tool-call envelope that must stay hidden.
 *
 * Replaces the former `buf.contains("{\"tool_call\"")` check in `AgentLoop.callLLM`, which
 *  - re-scanned the whole accumulated reply on every token (quadratic cost, report item 20),
 *  - never matched the `{"tool_calls":[...]}` batch form, and
 *  - hid the rest of the reply for good once a literal marker appeared, leaving the UI frozen
 *    even when the text was ordinary prose or code and not a call (report item 19).
 *
 * This gate only inspects the last `MARKER.length - 1` characters plus the new token, so the work
 * per token is constant. Text held back is returned by [releaseIfNotToolCall] when the finished
 * reply turns out not to be a tool call.
 */
internal class ToolCallStreamGate {
    private val tail = StringBuilder()
    private val held = StringBuilder()

    /** True once a tool-call marker was seen and later tokens are being held back. */
    var suppressing: Boolean = false
        private set

    /** Returns the part of [token] that may be shown right now ("" when held back). */
    fun accept(token: String): String {
        if (token.isEmpty()) return ""
        if (suppressing) {
            held.append(token)
            return ""
        }
        val window = tail.toString() + token
        if (window.contains(MARKER)) {
            suppressing = true
            held.append(token)
            return ""
        }
        tail.setLength(0)
        tail.append(window.takeLast(MARKER.length - 1))
        return token
    }

    /**
     * Text that was withheld but belongs to a normal answer. Call once when the reply is complete.
     * Returns "" when nothing was held or when the reply really is (or tried to be) a tool call.
     */
    fun releaseIfNotToolCall(finalText: String): String {
        if (!suppressing) return ""
        val parsed = TextToolCallProtocol.parse(finalText)
        val isToolCallAttempt = parsed !is TextToolCallProtocol.ParseResult.NotAToolCall
        return if (isToolCallAttempt) "" else held.toString()
    }

    companion object {
        /** Prefix shared by `{"tool_call":` and `{"tool_calls":`. */
        const val MARKER = "{\"tool_call"
    }
}
