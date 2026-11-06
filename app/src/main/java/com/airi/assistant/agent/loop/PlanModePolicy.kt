package com.airi.assistant.agent.loop

/**
 * Guards Plan Mode against a false terminal state.
 *
 * Plan Mode is useful only when a numbered plan is followed by execution. A
 * model that emits the plan and stops has not completed the requested work, so
 * the loop must give it one explicit continuation turn instead of publishing
 * the plan as the final answer.
 */
internal object PlanModePolicy {
    private val numberedStep = Regex("(?m)^\\s*(?:\\d+[.)]|[-*])\\s+\\S+")

    fun requiresExecutionContinuation(response: String, step: Int): Boolean {
        if (step != 1 || response.contains("\"tool_call\"")) return false
        val normalized = response.trim()
        if (normalized.isBlank()) return false
        return numberedStep.findAll(normalized).count() >= 2
    }

    const val EXECUTION_CONTINUATION_PROMPT: String =
        "[PLAN MODE] Your plan was recorded but no tool was called. Continue with the first executable step now. " +
            "Use the required tool-call JSON format only; do not repeat the plan or claim completion before all steps are verified."
}
