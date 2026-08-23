package com.airi.assistant.agent.loop

/**
 * Exact durable ownership coordinates for one typed AgentLoop side effect.
 *
 * This object deliberately contains identifiers and policy facts only. Raw chat
 * input, prompt history, tool arguments, provider payloads, credentials, and
 * callbacks must stay outside the durable task record and continuation JSON.
 */
data class AgentLoopExecutionContext(
    val taskId: String,
    val missionId: String,
    val projectId: String?,
    val runId: String,
    val stepId: String,
    val agentId: String,
    val sourceSessionId: String
) {
    fun isStructurallyValid(): Boolean =
        taskId.matches(SAFE_IDENTIFIER) &&
            missionId.matches(SAFE_IDENTIFIER) &&
            runId.matches(SAFE_IDENTIFIER) &&
            stepId.matches(SAFE_IDENTIFIER) &&
            agentId == AGENT_LOOP_PRINCIPAL &&
            sourceSessionId.matches(SAFE_IDENTIFIER) &&
            (projectId == null || projectId.matches(SAFE_IDENTIFIER))

    companion object {
        const val AGENT_LOOP_PRINCIPAL = "agent_loop"
        private val SAFE_IDENTIFIER = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}

/** A runtime can create one exact durable context on demand for a typed tool. */
fun interface AgentLoopExecutionContextFactory {
    fun createFor(toolName: String): AgentLoopExecutionContext?
}
