package com.airi.assistant.agent.loop

import android.content.Context
import android.util.Log
import com.airi.assistant.ai.agent.SelfHealingExecutor
import com.airi.assistant.agent.calendar.CalendarCreateRuntime
import com.airi.assistant.agent.loop.tool.AgentLoopSideEffectPolicy
import com.airi.assistant.agent.loop.tool.ToolDispatcher
import com.airi.assistant.agent.loop.tool.ToolSchema
import com.airi.assistant.ai.QueryType
import com.airi.assistant.ai.context.ContextBudget
import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.HybridOrchestrator
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.ExecutionStage
import kotlinx.coroutines.CancellationException
import java.util.UUID
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
 *
 * SPRINT 1: [contextBudgetProvider] replaces the hardcoded 8_192 token threshold
 * for long-context routing. The threshold now scales with the loaded model:
 *   1536-token model  → routes cloud when prompt > 768  tokens
 *   8192-token model  → routes cloud when prompt > 4096 tokens
 *   32K-token model   → routes cloud when prompt > 16K  tokens
 *
 * Default is [ContextBudget.UNLOADED] (1536-token budget) so existing callers
 * that don't pass a provider behave conservatively rather than breaking.
 */
class AgentLoop(
    private val orchestrator:          HybridOrchestrator,
    private val dispatcher:            ToolDispatcher,
    private val appContext:            Context,
    private val contextBudgetProvider: () -> ContextBudget = { ContextBudget.UNLOADED },
    /**
     * : Optional sandbox wrapper. When non-null, every tool dispatch is
     * routed through [agentSandbox.execute] so permission checks, workspace
     * logging, and rollback-on-violation are applied to every tool call.
     * Null keeps the legacy direct-dispatch path for callers that have not
     * yet been updated (conservative default).
     */
    private val agentSandbox: com.airi.assistant.security.AgentSandbox? = null,
    /** Typed local proposal runtime; only calendar creation is eligible today. */
    private val calendarCreateRuntime: CalendarCreateRuntime? = null
) {
    companion object {
        private const val TAG              = "AIRI_AgentLoop"
        private const val MAX_STEPS        = 16
        // Timeout increased from 60s to 120s. Agent loops with tool calls
        // (file ops, web search, code execution) can easily exceed 60s on
        // local inference, especially with retries and multi-step reasoning.
        private const val TIMEOUT_MS       = 120_000L
        /**
         * : Stable principal registered in [ScopedPermissionRegistry] for the
         * agent loop's tool-dispatch sandbox context. All tool calls on behalf of
         * the user-facing loop share this identity — permissions are granted to
         * "agent_loop" via [ServiceLocator.agentSandbox] setup, not per-tool.
         */
        const val SANDBOX_AGENT_ID = "agent_loop"

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
     * @param queryType      Classified intent from [QueryClassifier], forwarded into every
     *                       [ExecutionRequest] so [OpenRouterAdapter.selectModel] can apply
     *                       task-based model routing (ANALYTICAL → DeepSeek R1, etc.).
     * @param onToken        Called with each streaming token (for live UI updates).
     * @param onStepComplete Called after each completed step (tool execution or partial answer).
     *                       Return a non-null String to REPLACE the tool result that the LLM sees.
     *                       This is used by ChatViewModel to inject a user confirmation decision
     *                       when the agent calls ask_confirmation (P0-2 fix).
     */
    suspend fun run(
        input:          String,
        systemPrompt:   String,
        tools:          List<ToolSchema>,
        queryType:     QueryType              = QueryType.UNKNOWN,
        modelId:        String                 = "",
        onToken:        suspend (String) -> Unit,
        onStepComplete: suspend (StepEvent) -> String? = { null },
        executionContextFactory: AgentLoopExecutionContextFactory? = null
    ): LoopResult {
        val startMs      = System.currentTimeMillis()
        val toolsInvoked = mutableListOf<String>()
        val history      = mutableListOf<ConversationTurn>()
        var stepsUsed    = 0
        val executionId   = UUID.randomUUID().toString()
        var isPlanPublished = false
        var durableExecutionContext: AgentLoopExecutionContext? = null

        // If no tools provided, single-pass inference
        if (tools.isEmpty()) {
            val response = callLLM(input, systemPrompt, history, tools, queryType, modelId, onToken)
            return LoopResult(response, 1, emptyList())
        }

        val fullSystemPrompt = systemPrompt + "\n\n" + buildToolBlock(tools) + TOOL_CALL_INSTRUCTION
        history.add(ConversationTurn.User(input))

        try {
            while (stepsUsed < MAX_STEPS && coroutineContext.isActive) {
                if (System.currentTimeMillis() - startMs > TIMEOUT_MS) {
                    Log.w(TAG, "AgentLoop timed out after ${stepsUsed} steps")
                    ExecutionStatusBus.onGraphCompleted(false, executionId = executionId)
                    val partial = history.lastOrNull { it is ConversationTurn.Assistant }
                        ?.let { (it as ConversationTurn.Assistant).content }
                        ?: "Task timed out. Please try again."
                    return LoopResult(partial, stepsUsed, toolsInvoked, timedOut = true)
                }

                stepsUsed++

                val tokenBuffer = StringBuilder()
                val rawResponse = callLLM(
                    prompt       = "", // history carries the full context
                    systemPrompt = fullSystemPrompt,
                    history      = history,
                    tools        = tools,
                    queryType    = queryType,
                    modelId       = modelId,
                    onToken       = { tok ->
                        tokenBuffer.append(tok)
                        onToken(tok)
                    }
                )

                Log.d(TAG, "Agent step completed: step=$stepsUsed responseChars=${rawResponse.length}")

                // Parse: tool_call block or final answer?
                var toolCall = parseToolCall(rawResponse)

                // Retry: if the response looks like a malformed tool call (contains
                // "tool_call" text but JSON parsing failed), ask the model to re-emit
                // only the JSON. This handles cases where the model wraps the JSON in
                // prose or uses a slightly wrong format on the first attempt.
                if (toolCall == null && rawResponse.contains("tool_call") && stepsUsed < MAX_STEPS) {
                    Log.w(TAG, "AIRI TOOL_CALL_PARSE_RETRY step=$stepsUsed — response had 'tool_call' text but parse failed; retrying")
                    val retryPrompt = "[SYSTEM] Your previous response contained a tool_call but the JSON was malformed. " +
                        "Reply with ONLY a valid JSON object in this exact format, no other text:\n" +
                        "{\"tool_call\":{\"name\":\"<tool_name>\",\"args\":{\"param\":\"value\"}}}"
                    val retryHistory = history.toMutableList().also {
                        it.add(ConversationTurn.Assistant(rawResponse))
                        it.add(ConversationTurn.User(retryPrompt))
                    }
                    val retryResponse = try {
                        callLLM(
                            prompt       = "",
                            systemPrompt = fullSystemPrompt,
                            history      = retryHistory,
                            tools        = tools,
                            queryType      = queryType,
                            modelId        = modelId,
                            onToken        = {}   // don't stream retry to UI
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Tool call retry callLLM failed: ${e.message}")
                        ""
                    }
                    if (retryResponse.isNotBlank()) {
                        toolCall = parseToolCall(retryResponse)
                        if (toolCall != null) {
                            Log.i(TAG, "AIRI TOOL_CALL_RETRY_OK step=$stepsUsed tool=${toolCall.first}")
                        }
                    }
                }

                if (toolCall == null) {
                    // Final answer — LLM decided it's done (or retry also failed)
                    history.add(ConversationTurn.Assistant(rawResponse))
                    ExecutionStatusBus.onGraphCompleted(true, executionId = executionId)
                    onStepComplete(StepEvent.FinalAnswer(rawResponse, stepsUsed))
                    return LoopResult(rawResponse, stepsUsed, toolsInvoked)
                }

                // Execute the tool
                val toolName = toolCall.first
                val toolArgs = toolCall.second
                toolsInvoked.add(toolName)

                Log.i(TAG, "AIRI TOOL_CALL step=$stepsUsed tool=$toolName args=${toolArgs.keys.joinToString()}")
                if (!isPlanPublished) {
                    ExecutionStatusBus.onGraphStarted(
                        goalDescription = input.take(80),
                        // Actions are discovered incrementally; the ceiling is not a plan.
                        totalNodes = 0,
                        executionId = executionId,
                    )
                    isPlanPublished = true
                }
                val toolStepId = "tool_${stepsUsed}_$toolName"
                ExecutionStatusBus.onWaveStarted(
                    nodeIds = listOf(toolStepId),
                    nodeActions = listOf(toolName),
                    executionId = executionId,
                )

                // Direct chat retains a fail-closed boundary. The first allowed
                // mutation is a typed calendar proposal, and it is admitted only
                // after a foreground task owner has created an exact task/run/step.
                if (
                    toolName == "calendar_create" &&
                    durableExecutionContext == null &&
                    calendarCreateRuntime != null
                ) {
                    durableExecutionContext = executionContextFactory?.createFor(toolName)
                }
                val sideEffectDecision = AgentLoopSideEffectPolicy.decide(
                    toolName = toolName,
                    hasDurableExecutionContext = durableExecutionContext != null
                )
                val toolResult = when (sideEffectDecision) {
                    AgentLoopSideEffectPolicy.Decision.DURABLE_CONTEXT_REQUIRED -> {
                        Log.w(TAG, "AIRI TOOL_BLOCKED_NO_DURABLE_CONTEXT tool=$toolName")
                        ToolDispatcher.ToolResult.Error(AgentLoopSideEffectPolicy.blockedMessage(toolName))
                    }
                    AgentLoopSideEffectPolicy.Decision.ALLOW_TYPED_CALENDAR_CREATE -> {
                        val execution = durableExecutionContext
                        val runtime = calendarCreateRuntime
                        if (execution == null || runtime == null) {
                            ToolDispatcher.ToolResult.Error(AgentLoopSideEffectPolicy.blockedMessage(toolName))
                        } else {
                            when (val created = runtime.createProposal(
                                execution = execution,
                                title = toolArgs["title"],
                                startTime = toolArgs["start_time"],
                                durationText = toolArgs["duration_min"]
                            )) {
                                is CalendarCreateRuntime.ProposalResult.Created -> when (
                                    val pending = runtime.requestApproval(created.proposal.id)
                                ) {
                                    is CalendarCreateRuntime.ProposalResult.ApprovalPending ->
                                        ToolDispatcher.ToolResult.Success(
                                            "Calendar proposal is awaiting explicit review and approval in Trust Center. Do not retry the operation."
                                        )
                                    is CalendarCreateRuntime.ProposalResult.Rejected -> ToolDispatcher.ToolResult.Error(pending.reason)
                                    is CalendarCreateRuntime.ProposalResult.Failed -> ToolDispatcher.ToolResult.Error(pending.reason)
                                    else -> ToolDispatcher.ToolResult.Error("Calendar proposal could not enter approval")
                                }
                                is CalendarCreateRuntime.ProposalResult.Rejected -> ToolDispatcher.ToolResult.Error(created.reason)
                                is CalendarCreateRuntime.ProposalResult.Failed -> ToolDispatcher.ToolResult.Error(created.reason)
                                else -> ToolDispatcher.ToolResult.Error("Calendar proposal could not be created")
                            }
                        }
                    }
                    AgentLoopSideEffectPolicy.Decision.ALLOW_READ -> try {
                        // Route read-only tools through AgentSandbox when available so
                        // permission checks and workspace logging remain applied.
                        if (agentSandbox != null) {
                            agentSandbox.execute(agentId = SANDBOX_AGENT_ID) { ctx ->
                                ctx.guardTool(toolName)
                                dispatcher.execute(toolName, toolArgs, appContext)
                            }
                        } else {
                            dispatcher.execute(toolName, toolArgs, appContext)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: com.airi.assistant.security.AgentSandbox.SandboxViolationException) {
                        Log.w(TAG, "AIRI SANDBOX_VIOLATION tool=$toolName: ${e.message}")
                        ToolDispatcher.ToolResult.Error("Permission denied for tool: $toolName")
                    } catch (e: Exception) {
                        Log.w(TAG, "Tool $toolName threw: ${e.message}")
                        ToolDispatcher.ToolResult.Error("Tool failed: ${e.message}")
                    }
                }

                val resultText = when (toolResult) {
                    is ToolDispatcher.ToolResult.Success -> toolResult.output
                    is ToolDispatcher.ToolResult.Error   -> "Error: ${toolResult.message}"
                }

                Log.i(TAG, "AIRI TOOL_RESULT tool=$toolName success=${toolResult is ToolDispatcher.ToolResult.Success} len=${resultText.length}")

                ExecutionStatusBus.onNodeCompleted(
                    toolStepId,
                    stepsUsed,
                    executionId = executionId,
                )
                // P0-2: onStepComplete may return a non-null String to replace the tool result
                // (used when the agent calls ask_confirmation and ChatViewModel suspends for user input)
                val effectiveResult = onStepComplete(StepEvent.ToolExecuted(toolName, toolArgs, resultText, stepsUsed))
                    ?: resultText

                // Append the assistant's tool_call + tool result to history so the LLM
                // sees what it asked for and what it got back.
                history.add(ConversationTurn.Assistant(rawResponse))
                history.add(ConversationTurn.ToolResult(toolName, effectiveResult))
                if (toolResult is ToolDispatcher.ToolResult.Error && stepsUsed < MAX_STEPS) {
                    val healing = SelfHealingExecutor.recoverFromToolError(
                        failedToolName = toolName,
                        errorMessage = toolResult.message.take(300),
                        originalInput = ""
                    )
                    history.add(
                        ConversationTurn.User(
                            "[RECOVERY] ${healing.correctedPromptOrInput.trim()} " +
                                "Do not repeat a side effect unless the user has confirmed it."
                        )
                    )
                    Log.i(TAG, "AIRI SELF_HEALING_QUEUED tool=$toolName step=$stepsUsed")
                }
            }

            // Exhausted step budget — ask LLM to summarise what it has
            Log.w(TAG, "AgentLoop exhausted $MAX_STEPS steps — asking LLM to summarise")
            history.add(ConversationTurn.User("You have reached your step limit. Summarise what you have done and what the final answer is."))
            val summary = callLLM("", fullSystemPrompt, history, emptyList(), queryType, modelId, onToken)
            ExecutionStatusBus.onGraphCompleted(true, executionId = executionId)
            return LoopResult(summary, stepsUsed, toolsInvoked)

        } catch (e: CancellationException) {
            ExecutionStatusBus.onGraphCancelled(executionId)
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
        queryType:      QueryType = QueryType.UNKNOWN,
        modelId:        String = "",
        onToken:       suspend (String) -> Unit
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

        // SPRINT 1: Estimate token count and derive the long-context threshold from
        // the live ContextBudget (LlamaNative.getNCtx() → ContextBudget.longContextThreshold)
        // rather than the former hardcoded constant of 8_192.
        // For a 1536-token model: threshold = 768; for 32K: threshold = 16384.
        val estimatedTokens = fullPrompt.length / 4
        val longContextThreshold = contextBudgetProvider().longContextThreshold

        val buf = StringBuilder()
        var error: String? = null

        // Stop generation immediately if the model emits the start of a tool_call block —
        // we don't need to stream it token-by-token to the user.
        var inToolCall = false

        orchestrator.executeStream(
            request    = ExecutionRequest(
                prompt                = fullPrompt,
                systemPrompt          = systemPrompt,
                maxTokens             = 1024,   // : was 512 — too low for complex tool JSON + reasoning
                temperature           = 0.3f,   // low temp for structured decisions
                queryType             = queryType,
                requiresStreaming      = true,
                requiresLongContext   = estimatedTokens > longContextThreshold,
                estimatedPromptTokens = estimatedTokens,
                sessionTag            = "agent_loop",
                requestedModelId      = modelId,

                conversationHistory   = history.mapNotNull { turn ->
                    when (turn) {
                        is ConversationTurn.User ->
                            ExecutionRequest.ConversationTurn("user", turn.content)
                        is ConversationTurn.Assistant ->
                            ExecutionRequest.ConversationTurn("assistant", turn.content)
                        is ConversationTurn.ToolResult ->
                            ExecutionRequest.ConversationTurn(
                                "user", "[Tool ${turn.toolName}: ${turn.result.take(400)}]"
                            )
                    }
                }
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
        // P1-3: Strip markdown code fences before searching for JSON.
        // Some models wrap their tool_call JSON in ```json ... ``` or ``` ... ```.
        // The regex removes the opening fence (with optional language tag) and the
        // closing fence, leaving the raw JSON for the brace-depth parser below.
        val cleaned = response
            .replace(Regex("```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()

        val start = cleaned.indexOf("{\"tool_call\"")
        if (start == -1) return null
        return try {
            // Find the matching closing brace
            var depth = 0
            var end = start
            for (i in start until cleaned.length) {
                when (cleaned[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            val jsonStr = cleaned.substring(start, end + 1)
            val root    = JSONObject(jsonStr).getJSONObject("tool_call")
            val name    = root.getString("name")
            val argsObj = root.optJSONObject("args") ?: org.json.JSONObject()
            val args    = mutableMapOf<String, String>()
            for (key in argsObj.keys()) args[key] = argsObj.optString(key)
            name to args
        } catch (e: Exception) {
            Log.w(TAG, "Tool-call parsing failed: ${e.javaClass.simpleName}")
            null
        }
    }

    // ── Tool schema → system prompt block ─────────────────────────────────────

    /**
     * Format tool schemas into a system-prompt block, budget-trimmed.
     *
     * SPRINT 2 / Phase B: The char cap is now computed by
     * [ContributorBudgetPolicy.toolCharsCap] — the hardcoded 25% fraction
     * and 512-char floor live exclusively in the policy object, not here.
     */
    private fun buildToolBlock(tools: List<ToolSchema>): String {
        val raw = buildString {
            appendLine("AVAILABLE TOOLS:")
            for (tool in tools) {
                appendLine("• ${tool.name}: ${tool.description}")
                if (tool.parameters.isNotEmpty()) {
                    appendLine("  Parameters: ${tool.parameters.entries.joinToString(", ") {
                        "${it.key} (${it.value.type}${if (it.value.required) ", required" else ""})"
                    }}")
                }
            }
            appendLine()
        }
        val budget = contextBudgetProvider()
        val maxChars = com.airi.assistant.ai.prompt.budget.ContributorBudgetPolicy
            .toolCharsCap(budget.availableForContent)
        return if (raw.length <= maxChars) {
            raw
        } else {
            Log.w(TAG,
                "AIRI TOOL_BLOCK_TRIMMED raw=${raw.length}chars max=${maxChars}chars " +
                "nCtx=${budget.nCtx} tools=${tools.size}")
            raw.take(maxChars) + "\n[...tools trimmed by ContextBudget]"
        }
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
