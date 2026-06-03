package com.airi.assistant.agent.loop

import android.content.Context
import android.util.Log
import com.airi.assistant.agent.loop.tool.ToolDispatcher
import com.airi.assistant.agent.loop.tool.ToolSchema
import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.HybridOrchestrator
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.ExecutionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * AgentLoop — the real iterative LLM tool-calling loop.
 *
 * Architecture:
 *   1. Build system prompt with tool schemas injected.
 *   2. Call LLM via HybridOrchestrator (single inference entry point).
 *   3. Parse response: is it a tool_call JSON block or a final answer?
 *   4. If tool_call → ToolDispatcher.execute() → append result → go to 2.
 *   5. If final answer → done.
 *   6. Budget limits (maxSteps, timeoutMs) prevent infinite loops.
 *
 * The LLM is the planner. No regex. No pre-planned 20-step lists.
 * Every action is decided after observing the result of the previous one.
 */
class AgentLoop(
    private val orchestrator: HybridOrchestrator,
    private val dispatcher:   ToolDispatcher,
    private val appContext:   Context
) {
    companion object {
        private const val TAG         = "AIRI_AgentLoop"
        private const val MAX_STEPS   = 12
        private const val TIMEOUT_MS  = 60_000L

        // Sentinel that tells the LLM how to emit tool calls.
        // Kept as a string constant so it appears verbatim in every prompt.
        private const val TOOL_CALL_INSTRUCTION = """
When you need to use a tool, respond ONLY with this exact JSON (no markdown, no prose):
{"tool_call":{"name":"<tool_name>","args":{"<param>":"<value>"}}}

When you have a complete answer for the user, respond normally in plain text.
Do not mix tool_call JSON with prose in the same message.
"""
    }

    data class LoopResult(
        val finalAnswer:  String,
        val stepsUsed:    Int,
        val toolsInvoked: List<String>,
        val timedOut:     Boolean = false,
        val cancelled:    Boolean = false
    )

    /**
     * Run the agent loop for a user [input].
     *
     * @param input          Raw user message.
     * @param systemPrompt   Base system prompt (persona, memory, etc.) — tool schemas appended.
     * @param tools          Tools available for this session. If empty, runs single-turn LLM.
     * @param onToken        Called with each streaming token (for live UI updates).
     * @param onStepComplete Called after each completed step (tool execution or partial answer).
     */
    suspend fun run(
        input:          String,
        systemPrompt:   String,
        tools:          List<ToolSchema>,
        onToken:        suspend (String) -> Unit,
        onStepComplete: suspend (StepEvent) -> Unit = {}
    ): LoopResult {
        val startMs      = System.currentTimeMillis()
        val toolsInvoked = mutableListOf<String>()
        val history      = mutableListOf<ConversationTurn>()
        var stepsUsed    = 0

        // If no tools provided, single-pass inference
        if (tools.isEmpty()) {
            val response = callLLM(input, systemPrompt, history, tools, onToken)
            return LoopResult(response, 1, emptyList())
        }

        val fullSystemPrompt = systemPrompt + "\n\n" + buildToolBlock(tools) + TOOL_CALL_INSTRUCTION
        history.add(ConversationTurn.User(input))

        ExecutionStatusBus.onGraphStarted(
            goalId      = "loop_${System.currentTimeMillis()}",
            description = input.take(80),
            totalNodes  = MAX_STEPS
        )

        try {
            while (stepsUsed < MAX_STEPS && coroutineContext.isActive) {
                if (System.currentTimeMillis() - startMs > TIMEOUT_MS) {
                    Log.w(TAG, "AgentLoop timed out after ${stepsUsed} steps")
                    ExecutionStatusBus.onGraphFailed("Loop timed out after $stepsUsed steps")
                    val partial = history.lastOrNull { it is ConversationTurn.Assistant }
                        ?.let { (it as ConversationTurn.Assistant).content }
                        ?: "Task timed out. Please try again."
                    return LoopResult(partial, stepsUsed, toolsInvoked, timedOut = true)
                }

                stepsUsed++
                ExecutionStatusBus.onNodeStarted("step_$stepsUsed", "Step $stepsUsed", stepsUsed)

                val tokenBuffer = StringBuilder()
                val rawResponse = callLLM(
                    prompt       = "", // history carries the full context
                    systemPrompt = fullSystemPrompt,
                    history      = history,
                    tools        = tools,
                    onToken      = { tok ->
                        tokenBuffer.append(tok)
                        onToken(tok)
                    }
                )

                Log.d(TAG, "Step $stepsUsed response: ${rawResponse.take(120)}")

                // Parse: tool_call block or final answer?
                val toolCall = parseToolCall(rawResponse)
                if (toolCall == null) {
                    // Final answer — LLM decided it's done
                    history.add(ConversationTurn.Assistant(rawResponse))
                    ExecutionStatusBus.onNodeCompleted("step_$stepsUsed", true)
                    ExecutionStatusBus.onGraphCompleted(stepsUsed)
                    onStepComplete(StepEvent.FinalAnswer(rawResponse, stepsUsed))
                    return LoopResult(rawResponse, stepsUsed, toolsInvoked)
                }

                // Execute the tool
                val toolName = toolCall.first
                val toolArgs = toolCall.second
                toolsInvoked.add(toolName)

                Log.i(TAG, "AIRI_PROOF TOOL_CALL step=$stepsUsed tool=$toolName args=${toolArgs.keys.joinToString()}")
                ExecutionStatusBus.onNodeStarted("tool_$toolName", "Tool: $toolName", stepsUsed)

                val toolResult = try {
                    dispatcher.execute(toolName, toolArgs, appContext)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Tool $toolName threw: ${e.message}")
                    ToolDispatcher.ToolResult.Error("Tool failed: ${e.message}")
                }

                val resultText = when (toolResult) {
                    is ToolDispatcher.ToolResult.Success -> toolResult.output
                    is ToolDispatcher.ToolResult.Error   -> "Error: ${toolResult.message}"
                }

                Log.i(TAG, "AIRI_PROOF TOOL_RESULT tool=$toolName success=${toolResult is ToolDispatcher.ToolResult.Success} len=${resultText.length}")

                ExecutionStatusBus.onNodeCompleted("tool_$toolName", toolResult is ToolDispatcher.ToolResult.Success)
                onStepComplete(StepEvent.ToolExecuted(toolName, toolArgs, resultText, stepsUsed))

                // Append the assistant's tool_call + tool result to history so the LLM
                // sees what it asked for and what it got back.
                history.add(ConversationTurn.Assistant(rawResponse))
                history.add(ConversationTurn.ToolResult(toolName, resultText))
            }

            // Exhausted step budget — ask LLM to summarise what it has
            Log.w(TAG, "AgentLoop exhausted $MAX_STEPS steps — asking LLM to summarise")
            history.add(ConversationTurn.User("You have reached your step limit. Summarise what you have done and what the final answer is."))
            val summary = callLLM("", fullSystemPrompt, history, emptyList(), onToken)
            ExecutionStatusBus.onGraphCompleted(stepsUsed)
            return LoopResult(summary, stepsUsed, toolsInvoked)

        } catch (e: CancellationException) {
            ExecutionStatusBus.onGraphFailed("Cancelled")
            val last = history.lastOrNull { it is ConversationTurn.Assistant }
                ?.let { (it as ConversationTurn.Assistant).content } ?: ""
            return LoopResult(last.ifBlank { "Task cancelled." }, stepsUsed, toolsInvoked, cancelled = true)
        }
    }

    // ── LLM call (always through HybridOrchestrator) ───────────────────────────

    private suspend fun callLLM(
        prompt:       String,
        systemPrompt: String,
        history:      List<ConversationTurn>,
        tools:        List<ToolSchema>,
        onToken:      suspend (String) -> Unit
    ): String {
        // Build the full prompt from history
        val fullPrompt = when {
            history.isEmpty() -> prompt
            else -> buildString {
                for (turn in history) {
                    when (turn) {
                        is ConversationTurn.User       -> append("User: ${turn.content}\n")
                        is ConversationTurn.Assistant  -> append("Assistant: ${turn.content}\n")
                        is ConversationTurn.ToolResult -> append("[Tool ${turn.toolName} returned: ${turn.result.take(500)}]\n")
                    }
                }
                if (prompt.isNotBlank()) append("User: $prompt\n")
                append("Assistant:")
            }
        }

        val buf = StringBuilder()
        var error: String? = null

        // Stop generation immediately if the model emits the start of a tool_call block —
        // we don't need to stream it token-by-token to the user.
        var inToolCall = false

        orchestrator.executeStream(
            request    = ExecutionRequest(
                prompt           = fullPrompt,
                systemPrompt     = systemPrompt,
                maxTokens        = 512,
                temperature      = 0.3f,   // low temp for structured decisions
                requiresStreaming = true,
                sessionTag       = "agent_loop"
            ),
            context    = appContext,
            onToken    = { tok ->
                buf.append(tok)
                // Only stream to UI if we're not in the middle of a tool_call JSON block
                if (!inToolCall) {
                    if (buf.contains("{\"tool_call\"")) {
                        inToolCall = true
                    } else {
                        onToken(tok)
                    }
                }
            },
            onComplete = { text, _, _ -> if (text.isNotBlank()) { buf.clear(); buf.append(text) } },
            onError    = { msg, _ -> error = msg }
        )

        if (error != null) throw RuntimeException(error)
        return buf.toString().trim()
    }

    // ── Tool call parsing ──────────────────────────────────────────────────────

    /**
     * Extract tool name and args from the model's response.
     * Returns null if the response is a plain-text final answer.
     *
     * Accepted formats (model might wrap in prose before the JSON):
     *   {"tool_call":{"name":"calendar_read","args":{"days":"7"}}}
     */
    private fun parseToolCall(response: String): Pair<String, Map<String, String>>? {
        val start = response.indexOf("{\"tool_call\"")
        if (start == -1) return null
        return try {
            // Find the matching closing brace
            var depth = 0
            var end = start
            for (i in start until response.length) {
                when (response[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            val jsonStr = response.substring(start, end + 1)
            val root    = JSONObject(jsonStr).getJSONObject("tool_call")
            val name    = root.getString("name")
            val argsObj = root.optJSONObject("args") ?: org.json.JSONObject()
            val args    = mutableMapOf<String, String>()
            for (key in argsObj.keys()) args[key] = argsObj.optString(key)
            name to args
        } catch (e: Exception) {
            Log.w(TAG, "parseToolCall failed: ${e.message} | response=${response.take(80)}")
            null
        }
    }

    // ── Tool schema → system prompt block ─────────────────────────────────────

    private fun buildToolBlock(tools: List<ToolSchema>): String = buildString {
        appendLine("AVAILABLE TOOLS:")
        for (tool in tools) {
            appendLine("• ${tool.name}: ${tool.description}")
            if (tool.parameters.isNotEmpty()) {
                appendLine("  Parameters: ${tool.parameters.entries.joinToString(", ") { "${it.key} (${it.value.type}${if (it.value.required) ", required" else ""})" }}")
            }
        }
        appendLine()
    }

    // ── Conversation model ─────────────────────────────────────────────────────

    sealed class ConversationTurn {
        data class User(val content: String) : ConversationTurn()
        data class Assistant(val content: String) : ConversationTurn()
        data class ToolResult(val toolName: String, val result: String) : ConversationTurn()
    }

    sealed class StepEvent {
        data class ToolExecuted(
            val toolName: String,
            val args:     Map<String, String>,
            val result:   String,
            val step:     Int
        ) : StepEvent()
        data class FinalAnswer(val text: String, val steps: Int) : StepEvent()
    }
}
