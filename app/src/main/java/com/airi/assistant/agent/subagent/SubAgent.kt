package com.airi.assistant.agent.subagent

import kotlinx.coroutines.flow.Flow

/**
 * Base interface for all AIRI specialized sub-agents.
 *
 * Sub-agents are execution units with a specific domain specialization.
 * They receive a task input, execute it (potentially using tools, cloud APIs,
 * or on-device resources), and emit a sequence of [AgentEvent] to the caller.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * LIFECYCLE
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   1. [ProductionAgentOrchestrator] queries [capability] for keyword routing.
 *   2. [canHandle] is called for fine-grained confirmation.
 *   3. [execute] is called — emits [AgentEvent] to the caller's flow.
 *   4. The caller's scope is cancelled on timeout or user interrupt.
 *      Implementations MUST check [kotlinx.coroutines.isActive] in loops.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * CANCELLATION CONTRACT
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   - [execute] returns a [Flow] — cancellation happens by cancelling the
 *     collecting coroutine. The implementation must NOT spawn non-supervised
 *     child coroutines that outlive the calling scope.
 *   - Cleanup (releasing resources, cancelling HTTP calls) must happen in
 *     the flow's onCompletion / finally block.
 *   - Emitting [AgentEvent.Failed] is NOT a substitute for proper coroutine
 *     cancellation — always let the structured cancellation propagate.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * STREAMING RESULTS
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   Emit [AgentEvent.PartialResult] for each incremental text chunk so the
 *   UI can stream results like a chat response. Always terminate with either
 *   [AgentEvent.Complete] or [AgentEvent.Failed].
 */
interface SubAgent {

    /** Capability declaration — must be a stable, immutable object. */
    val capability: SubAgentCapability

    /**
     * Fine-grained pre-flight check.
     *
     * Called after keyword routing matches. Should be fast (< 50 ms).
     * Returns true if this agent can handle [input] given [context].
     *
     * Examples of reasons to return false:
     *   - Required permissions not yet granted
     *   - Cloud key missing for a cloud-required agent
     *   - Input clearly outside this agent's domain on closer inspection
     */
    suspend fun canHandle(input: String, context: SubAgentContext): Boolean

    /**
     * Execute the agent task and emit [AgentEvent] to the collector.
     *
     * The returned [Flow] is cold — it starts on first collection.
     * Cancelling the collection scope cancels execution.
     *
     * Guaranteed termination: EVERY execution path must eventually emit
     * [AgentEvent.Complete] or [AgentEvent.Failed]. Infinite flows are
     * only permitted for live monitoring agents, which must document this.
     */
    fun execute(input: String, context: SubAgentContext): Flow<AgentEvent>
}
