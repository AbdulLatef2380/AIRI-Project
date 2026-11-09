package com.airi.assistant.agent.loop.verification

import com.airi.assistant.ai.CapabilityIntentDetector.Capability

/**
 * Report أ-2: a plain-text reply used to be accepted as the final answer even when the request
 * asked for an action (run a command, save a file, operate the device) and no matching tool ran.
 *
 * This guard is asked before such a reply is accepted. It asks for ONE correction turn: call the
 * tool now, or say plainly why it cannot be done. It never forces a tool (the model may have a
 * good reason, such as a missing detail), and after the single correction the next reply is
 * accepted as it is, so it cannot loop. Code tasks keep their own stricter [CodeTaskGate].
 *
 * Pure Kotlin, unit-testable on the JVM.
 */
class ActionCompletionGuard(
    private val required: Set<Capability>,
    private val exposedTools: Set<String>,
) {
    sealed class Verdict {
        object Accept : Verdict()
        data class Correct(val missing: List<Capability>, val prompt: String) : Verdict()
    }

    private var corrected = false

    /** [toolsInvoked] is every tool name run so far in this loop. */
    fun onFinalText(toolsInvoked: Collection<String>): Verdict {
        if (corrected) return Verdict.Accept
        val missing = required.filter { capability ->
            val tools = TOOLS_FOR[capability].orEmpty()
            val offered = tools.filter { it in exposedTools }
            // Only demand what the model could actually do: at least one matching tool was exposed.
            offered.isNotEmpty() && toolsInvoked.none { it in tools || it == "ask_confirmation" }
        }
        if (missing.isEmpty()) return Verdict.Accept
        corrected = true
        val names = missing.flatMap { TOOLS_FOR[it].orEmpty() }.filter { it in exposedTools }.distinct()
        val prompt = "[SYSTEM] The user's request asks for an action, but you answered with text only and no tool " +
            "ran. If the action can be done, call the matching tool now (${names.joinToString(", ")}). " +
            "If it cannot be done, or you need a detail first, say so plainly in one or two sentences. " +
            "Do not claim the action was done unless a tool result shows it."
        return Verdict.Correct(missing, prompt)
    }

    companion object {
        private val TOOLS_FOR: Map<Capability, Set<String>> = mapOf(
            Capability.TERMINAL_EXECUTE to setOf("terminal_execute"),
            Capability.FILE_WRITE to setOf("write_file"),
            Capability.DEVICE_ACTION to setOf(
                "open_app", "tap", "type_text", "scroll_down", "go_back", "flashlight", "set_alarm",
                "calendar_create", "create_note",
            ),
        )

        /** The capabilities this guard enforces; read-only ones (search, memory, time) are not actions. */
        val ENFORCED: Set<Capability> = TOOLS_FOR.keys
    }
}
