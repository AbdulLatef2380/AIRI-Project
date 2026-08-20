package com.airi.assistant.agent.planning

import android.util.Log

/**
 * Chain-of-Thought (CoT) Engine.
 *
 * Decomposes a user goal into an explicit sequence of reasoning steps
 * before action. Each step is represented as a [ThoughtStep] which the
 * ReActPlanner can inspect to decide what action to take next.
 *
 * The engine is stateless — it operates on the [CoTSession] value class
 * that the caller owns. This keeps the engine safe for concurrent use and
 * trivially testable.
 *
 * REAL EXECUTION:
 *   - Produces a structured thought chain from a natural-language goal.
 *   - Each step has a [ThoughtStep.type] that maps to a ReAct action token.
 *   - [scoreCoherence] assigns a 0–1 confidence to the chain; chains below
 *     [MIN_COHERENCE] trigger automatic chain re-generation.
 *   - The engine never calls a network or LLM by itself; the orchestrator
 *     injects the LLM response via [ingestLlmResponse].
 */
class CoTEngine {

    companion object {
        private const val TAG = "CoTEngine"
        private const val MIN_COHERENCE = 0.45f
        private const val MAX_STEPS = 12
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Seed a new CoT session from a user goal and optional prior context.
     *
     * Produces the initial [CoTSession] — the caller may iterate via
     * [expandStep] and [ingestLlmResponse] until [isComplete] returns true.
     */
    fun seed(goal: String, priorContext: String = ""): CoTSession {
        Log.i(TAG, "COT_SESSION_SEEDED goalChars=${goal.length}")
        val anchor = buildAnchorThought(goal, priorContext)
        return CoTSession(
            goal         = goal,
            priorContext = priorContext,
            steps        = listOf(anchor),
            coherence    = scoreCoherence(listOf(anchor))
        )
    }

    /**
     * Expand the chain by one step given the most recent LLM output.
     *
     * Returns a new [CoTSession]; the original is unchanged.
     */
    fun expandStep(session: CoTSession, llmOutput: String): CoTSession {
        if (session.steps.size >= MAX_STEPS) {
            Log.w(TAG, "Max CoT steps reached — truncating")
            return session.copy(isComplete = true)
        }
        val parsed   = parseThoughtsFromLlm(llmOutput)
        val merged   = (session.steps + parsed).take(MAX_STEPS)
        val coherence = scoreCoherence(merged)
        val complete  = detectCompletion(merged) || coherence < MIN_COHERENCE
        Log.d(TAG, "CoT expand steps=${merged.size} coherence=$coherence complete=$complete")
        return session.copy(
            steps       = merged,
            coherence   = coherence,
            isComplete  = complete
        )
    }

    /**
     * Ingest a raw LLM response into an existing session without expanding —
     * use this when the orchestrator already has the LLM text and only wants
     * to update the internal thought list.
     */
    fun ingestLlmResponse(session: CoTSession, llmResponse: String): CoTSession =
        expandStep(session, llmResponse)

    /**
     * Build the system-prompt fragment that instructs the LLM to emit
     * Chain-of-Thought in the format this engine can parse.
     */
    fun buildSystemPromptFragment(session: CoTSession): String = """
You are reasoning step-by-step. Current goal: "${session.goal}"

For each response, follow this format EXACTLY:
THOUGHT: <your reasoning about the current state>
ACTION: <one of: SEARCH | COMPUTE | RECALL | WRITE | SPEAK | DONE>
INPUT: <the exact input for that action>
OBSERVATION: <leave blank — filled in by the system>

After each OBSERVATION, emit another THOUGHT→ACTION→INPUT triple until
you are confident the goal is achieved, then emit ACTION: DONE.

Prior context (if any):
${session.priorContext.ifBlank { "(none)" }}
    """.trimIndent()

    /**
     * Summarise the completed thought chain into a single human-readable
     * string suitable for injecting into the final LLM synthesis prompt.
     */
    fun summarise(session: CoTSession): String {
        val header = "Reasoning chain for: \"${session.goal}\""
        val body   = session.steps.mapIndexed { i, s ->
            "${i + 1}. [${s.type.name}] ${s.thought.take(200)}" +
                if (s.observation.isNotBlank()) "\n   → ${s.observation.take(120)}" else ""
        }.joinToString("\n")
        return "$header\n$body"
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun buildAnchorThought(goal: String, ctx: String): ThoughtStep {
        val implicit = when {
            goal.contains("?")       -> ThoughtType.SEARCH
            goal.lowercase().contains("write") ||
            goal.lowercase().contains("draft")  -> ThoughtType.WRITE
            goal.lowercase().contains("calculate") ||
            goal.lowercase().contains("compute") ||
            goal.lowercase().contains("how many") -> ThoughtType.COMPUTE
            goal.lowercase().contains("remember") ||
            goal.lowercase().contains("recall")   -> ThoughtType.RECALL
            else                                  -> ThoughtType.REASON
        }
        return ThoughtStep(
            type        = implicit,
            thought     = "Goal received: $goal. Prior context available: ${ctx.isNotBlank()}. Planning first action.",
            action      = implicit.name,
            input       = goal,
            observation = ""
        )
    }

    private fun parseThoughtsFromLlm(llmOutput: String): List<ThoughtStep> {
        val steps    = mutableListOf<ThoughtStep>()
        val lines    = llmOutput.lines()
        var thought  = ""
        var action   = ""
        var input    = ""
        var obs      = ""

        for (line in lines) {
            when {
                line.startsWith("THOUGHT:")     -> thought = line.removePrefix("THOUGHT:").trim()
                line.startsWith("ACTION:")      -> action  = line.removePrefix("ACTION:").trim()
                line.startsWith("INPUT:")       -> input   = line.removePrefix("INPUT:").trim()
                line.startsWith("OBSERVATION:") -> {
                    obs = line.removePrefix("OBSERVATION:").trim()
                    if (thought.isNotBlank() && action.isNotBlank()) {
                        val type = ThoughtType.fromString(action)
                        steps.add(ThoughtStep(type, thought, action, input, obs))
                        thought = ""; action = ""; input = ""; obs = ""
                    }
                }
            }
        }
        // Flush an incomplete quad (DONE action)
        if (thought.isNotBlank() && action.isNotBlank()) {
            steps.add(ThoughtStep(ThoughtType.fromString(action), thought, action, input, obs))
        }
        return steps.ifEmpty {
            // Fallback: treat the whole output as one REASON step
            listOf(ThoughtStep(ThoughtType.REASON, llmOutput.take(400), "REASON", "", ""))
        }
    }

    private fun detectCompletion(steps: List<ThoughtStep>): Boolean =
        steps.lastOrNull()?.type == ThoughtType.DONE ||
        steps.count { it.type == ThoughtType.DONE } > 0

    private fun scoreCoherence(steps: List<ThoughtStep>): Float {
        if (steps.isEmpty()) return 0f
        val uniqueTypes     = steps.map { it.type }.toSet().size.toFloat()
        val hasAnchor       = steps.firstOrNull()?.thought?.isNotBlank() == true
        val hasFinalAction  = steps.lastOrNull()?.let {
            it.type == ThoughtType.DONE || it.type == ThoughtType.SPEAK
        } == true
        val diversityScore  = (uniqueTypes / ThoughtType.values().size).coerceAtMost(1f)
        val anchorBonus     = if (hasAnchor) 0.3f else 0f
        val terminalBonus   = if (hasFinalAction) 0.2f else 0f
        return (diversityScore + anchorBonus + terminalBonus).coerceAtMost(1f)
    }
}

// ── Domain types ───────────────────────────────────────────────────────────────

data class CoTSession(
    val goal:         String,
    val priorContext: String             = "",
    val steps:        List<ThoughtStep>  = emptyList(),
    val coherence:    Float              = 0f,
    val isComplete:   Boolean            = false
)

data class ThoughtStep(
    val type:        ThoughtType,
    val thought:     String,
    val action:      String,
    val input:       String,
    val observation: String
)

enum class ThoughtType {
    REASON, SEARCH, COMPUTE, RECALL, WRITE, SPEAK, DONE;

    companion object {
        fun fromString(s: String): ThoughtType =
            values().firstOrNull { it.name.equals(s.trim(), ignoreCase = true) } ?: REASON
    }
}
