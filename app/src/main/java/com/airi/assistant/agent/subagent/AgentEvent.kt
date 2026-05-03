package com.airi.assistant.agent.subagent

/**
 * Sealed hierarchy of events emitted by a [SubAgent] during execution.
 *
 * Consumers (UI, orchestrator) pattern-match on these to:
 *   - Display streaming progress ([Progress], [PartialResult])
 *   - Record tool invocations in the agent trace ([ToolCall])
 *   - Finalize results ([Complete], [Failed])
 *   - Delegate to another agent ([Delegate])
 *
 * Every [SubAgent.execute] flow must terminate with [Complete] or [Failed].
 */
sealed class AgentEvent {

    /**
     * Incremental status message. Show in the agent activity indicator.
     *
     * [percentComplete] is -1 when progress is indeterminate.
     */
    data class Progress(
        val message:         String,
        val percentComplete: Int    = -1,
        val stepName:        String = ""
    ) : AgentEvent()

    /**
     * Incremental text result chunk — stream to the chat response bubble.
     *
     * Multiple [PartialResult] events compose the final response text.
     * The orchestrator concatenates them in order.
     */
    data class PartialResult(
        val text:    String,
        val isFinal: Boolean = false
    ) : AgentEvent()

    /**
     * The agent is invoking a tool.
     *
     * Record in the agent trace and gate on user permission if required.
     */
    data class ToolCall(
        val toolName:  String,
        val params:    Map<String, String>,
        val reasoning: String = ""
    ) : AgentEvent()

    /**
     * The agent is invoking another sub-agent (delegation).
     *
     * The orchestrator resolves [targetAgentId] via [SubAgentRegistry]
     * and executes it as a nested task.
     */
    data class Delegate(
        val targetAgentId: String,
        val subInput:      String,
        val reason:        String = ""
    ) : AgentEvent()

    /**
     * Execution complete. Contains the full, final result text.
     *
     * [durationMs] is wall-clock time from execute() call to this event.
     * [toolsUsed] lists tool names invoked during execution.
     */
    data class Complete(
        val result:    String,
        val durationMs: Long,
        val toolsUsed:  List<String> = emptyList(),
        val tokenCount: Int          = 0
    ) : AgentEvent()

    /**
     * Execution failed with [reason].
     *
     * [recoverable] true: the orchestrator may retry or fallback.
     * [recoverable] false: the orchestrator should surface the error to UX.
     */
    data class Failed(
        val reason:      String,
        val recoverable: Boolean = false,
        val errorCode:   String  = ""
    ) : AgentEvent()
}
