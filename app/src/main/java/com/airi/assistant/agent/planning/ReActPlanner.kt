package com.airi.assistant.agent.planning

import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ReAct Planner — Reasoning + Acting loop.
 *
 * Implements the Yao et al. "ReAct: Synergizing Reasoning and Acting in
 * Language Models" (ICLR 2023) pattern adapted for the AIRI sub-agent stack.
 *
 * LOOP:
 *   while not done and steps < maxSteps:
 *     1. THINK  — [CoTEngine] produces / expands the thought chain.
 *     2. ACT    — derive the next concrete action from the top thought.
 *     3. OBSERVE — record the result of the action into the thought chain.
 *   ANSWER — synthesise the final response from the completed chain.
 *
 * The planner is a [Flow]-producing function so it streams partial results
 * to the UI exactly like any other [SubAgent].
 *
 * REAL EXECUTION:
 *   - Uses [CoTEngine] for all chain-of-thought manipulation.
 *   - Each action type maps to a real tool call (SEARCH, RECALL, COMPUTE …).
 *   - Tool results are injected back as OBSERVATION tokens.
 *   - Falls back gracefully to pure LLM delegation when a tool is unavailable.
 */
class ReActPlanner(
    private val cotEngine: CoTEngine = CoTEngine(),
    private val maxSteps:  Int       = 6
) {

    companion object {
        private const val TAG = "ReActPlanner"
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Run the full ReAct loop for [goal] and emit [AgentEvent]s to the caller.
     *
     * @param goal     The user's natural-language goal.
     * @param context  Sub-agent execution context (privacy, session, etc.).
     * @param tools    Map of tool names → suspend lambdas. The planner resolves
     *                 [ThoughtType] → tool name and calls the lambda with the
     *                 ACTION INPUT string. Return "" to indicate the tool
     *                 produced no observation (will be noted in the chain).
     */
    fun plan(
        goal:    String,
        context: SubAgentContext,
        tools:   Map<String, suspend (String) -> String> = emptyMap()
    ): Flow<AgentEvent> = flow {
        val start   = System.currentTimeMillis()
        Log.i(TAG, "ReAct start goal='${goal.take(80)}' maxSteps=$maxSteps")

        emit(AgentEvent.Progress("Planning: \"${goal.take(60)}\"", 5, "react_seed"))

        var session = cotEngine.seed(goal, context.sessionId)
        var stepIdx = 0

        while (!session.isComplete && stepIdx < maxSteps) {
            stepIdx++
            val pct = (stepIdx * 100 / maxSteps).coerceAtMost(90)

            val currentStep = session.steps.lastOrNull()
            val actionType  = currentStep?.type ?: ThoughtType.REASON
            val actionInput = currentStep?.input ?: goal

            Log.d(TAG, "ReAct step=$stepIdx action=${actionType.name} input='${actionInput.take(60)}'")
            emit(AgentEvent.Progress(
                "[Step $stepIdx/${maxSteps}] ${actionType.name}: ${actionInput.take(50)}",
                pct,
                "react_step_$stepIdx"
            ))
            emit(AgentEvent.ToolCall(
                toolName  = actionType.name.lowercase(),
                params    = mapOf("input" to actionInput, "step" to stepIdx.toString()),
                reasoning = currentStep?.thought ?: "Executing ReAct step $stepIdx"
            ))

            // Resolve and execute the action
            val observation = executeAction(actionType, actionInput, context, tools)
            Log.d(TAG, "ReAct step=$stepIdx observation='${observation.take(80)}'")

            // Expand session with the observation injected as a synthetic LLM response
            val llmLike = buildObservationFragment(actionType, actionInput, observation)
            session = cotEngine.expandStep(session, llmLike)

            if (observation.isNotBlank()) {
                emit(AgentEvent.PartialResult(observation, isFinal = false))
            }
        }

        // Final synthesis
        val summary = cotEngine.summarise(session)
        Log.i(TAG, "ReAct complete steps=$stepIdx coherence=${session.coherence}")

        emit(AgentEvent.Progress("Synthesising answer…", 92, "react_synthesise"))
        emit(AgentEvent.Delegate(
            targetAgentId = "llm_backend",
            subInput      = buildSynthesisPrompt(goal, summary),
            reason        = "ReAct chain complete — delegating to LLM for final answer"
        ))

        emit(AgentEvent.Complete(
            result     = "[ReAct plan complete — $stepIdx steps, coherence=${session.coherence}]",
            durationMs = System.currentTimeMillis() - start,
            toolsUsed  = session.steps.map { it.type.name.lowercase() }.distinct()
        ))
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private suspend fun executeAction(
        type:    ThoughtType,
        input:   String,
        context: SubAgentContext,
        tools:   Map<String, suspend (String) -> String>
    ): String {
        if (context.privacyLevel == SubAgentContext.PRIVACY_MAXIMUM &&
            type == ThoughtType.SEARCH) {
            return "[SEARCH skipped — privacy=MAXIMUM]"
        }
        val toolKey = type.name.lowercase()
        val tool    = tools[toolKey] ?: tools["default"]
        return if (tool != null) {
            runCatching { tool(input) }.getOrElse { e ->
                Log.w(TAG, "Tool $toolKey failed: ${e.message}")
                "[Tool error: ${e.message?.take(60)}]"
            }
        } else {
            "[No tool registered for $toolKey — observation pending LLM]"
        }
    }

    private fun buildObservationFragment(
        type:        ThoughtType,
        input:       String,
        observation: String
    ): String = """
THOUGHT: Executed $type on "$input". Reviewing the result.
ACTION: ${type.name}
INPUT: $input
OBSERVATION: $observation
    """.trimIndent()

    private fun buildSynthesisPrompt(goal: String, chain: String): String = """
You are AIRI. The user's request was: "$goal"

You reasoned through it step-by-step:
$chain

Using the above reasoning chain, give the user a clear, accurate, and concise
final answer. If any step produced an observation, incorporate it. Be honest
about what you found versus what you inferred.
    """.trimIndent()
}
