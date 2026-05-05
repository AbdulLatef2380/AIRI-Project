package com.airi.assistant.agent

import android.util.Log

/**
 * AgentLifecycleState — unified 9-state lifecycle enum for the AIRI autonomous agent.
 *
 * This is the single source of truth for agent lifecycle across all layers:
 *  - [com.airi.assistant.agent.execution.runtime.AgentExecutor]
 *  - [com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator]
 *  - [com.airi.assistant.core.UnifiedCognitiveLoop]
 *  - [com.airi.assistant.agent.planning.ReActPlanner]
 *  - [com.airi.assistant.ui.viewmodel.ChatViewModel]
 *
 * State transition graph:
 *
 *   IDLE ──────────────────────────────────────────────────────────────────────┐
 *     │                                                                         │
 *   THINKING (model reasoning about goal)                                       │
 *     │                                                                         │
 *   PLANNING (PlanGenerator + TypedPlanGraph DAG construction)                  │
 *     │                                                                         │
 *   EXECUTING (CommandRouter → ConnectorActionBridge / SubAgentRegistry)        │
 *     │                                                                         │
 *   OBSERVING (ObservationEngine validates step output)                         │
 *     │                                                                         │
 *   FIXING ──► EXECUTING (self-correction via RecoveryBranch)                  │
 *     │                                                                         │
 *   COMPLETED ───────────────────────────────────────────────────────────────►─┤
 *   FAILED ──────────────────────────────────────────────────────────────────►─┤
 *   INTERRUPTED ─────────────────────────────────────────────────────────────►─┘
 *
 * UI display strings are provided by [label] for direct use in composables
 * without a when-expression at every call site.
 *
 * [isTerminal] marks states after which the state machine resets to IDLE.
 * [isWorking]  marks states where the agent is actively consuming resources
 *              (used by AgentExecutionPanel visibility gate).
 */
enum class AgentLifecycleState(
    val label:      String,
    val isWorking:  Boolean,
    val isTerminal: Boolean,
) {
    /** Agent is idle — no task active. Default state on app start and after terminal states. */
    IDLE(
        label      = "Idle",
        isWorking  = false,
        isTerminal = false,
    ),

    /**
     * Model is actively generating a reasoning chain (CoT / ReAct THINK step).
     * llama.cpp tokens are streaming; [InferenceManager] queue depth > 0.
     */
    THINKING(
        label      = "Thinking...",
        isWorking  = true,
        isTerminal = false,
    ),

    /**
     * [PlanGenerator.createDAGPlanFromLLM] is constructing a [TypedPlanGraph].
     * The model has produced a JSON action plan; the DAG is being validated
     * and topologically sorted into execution waves.
     */
    PLANNING(
        label      = "Planning...",
        isWorking  = true,
        isTerminal = false,
    ),

    /**
     * [UnifiedCognitiveLoop.executeGraph] / [ProductionAgentOrchestrator.executeAll]
     * is driving a TypedPlanGraph wave. [CommandRouter] is dispatching steps
     * through Tier 1 (accessibility) → Tier 1.5 (connector) → Tier 2 (sub-agent).
     */
    EXECUTING(
        label      = "Executing...",
        isWorking  = true,
        isTerminal = false,
    ),

    /**
     * [ObservationEngine] is validating the output of a completed step.
     * Determines whether to proceed, fix, retry, skip, or abort.
     */
    OBSERVING(
        label      = "Observing...",
        isWorking  = true,
        isTerminal = false,
    ),

    /**
     * A step failed validation. [RecoveryBranch] selected RETRY or FALLBACK.
     * The agent is patching the plan and will transition back to EXECUTING.
     */
    FIXING(
        label      = "Fixing...",
        isWorking  = true,
        isTerminal = false,
    ),

    /** All DAG nodes reached DONE. Final synthesis complete. */
    COMPLETED(
        label      = "Completed",
        isWorking  = false,
        isTerminal = true,
    ),

    /**
     * A terminal node returned FAILED after all retry attempts exhausted,
     * or [RecoveryBranch.Abort] was chosen. No further execution.
     */
    FAILED(
        label      = "Failed",
        isWorking  = false,
        isTerminal = true,
    ),

    /**
     * User or system requested cancellation via [LlamaManager.cancelStream] /
     * [InferenceManager.cancel]. The native `g_cancel_requested` atomic is set.
     * Execution halts at the next [TypedPlanGraph] wave boundary.
     */
    INTERRUPTED(
        label      = "Interrupted",
        isWorking  = false,
        isTerminal = true,
    );

    companion object {
        private const val TAG = "AgentLifecycle"

        /** Log a state transition to AIRI_PROOF with structured fields. */
        fun transition(from: AgentLifecycleState, to: AgentLifecycleState, reason: String = "") {
            if (from == to) return
            val msg = buildString {
                append("LIFECYCLE_TRANSITION from=${from.name} to=${to.name}")
                if (reason.isNotBlank()) append(" reason=$reason")
            }
            when {
                to == FAILED      || to == INTERRUPTED -> Log.w("AIRI_PROOF", msg)
                to == FIXING                           -> Log.w("AIRI_PROOF", msg)
                else                                   -> Log.i("AIRI_PROOF", msg)
            }
        }
    }
}
