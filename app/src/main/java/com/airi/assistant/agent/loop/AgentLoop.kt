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
import com.airi.assistant.core.UniversalRuntimeTraceRecorder
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.AgentPromptTokenEstimator
import com.airi.assistant.execution.ExecutionIdentity
import com.airi.assistant.execution.ChatExecutionIdentityContract
import com.airi.assistant.execution.ConversationRequestPolicy
import com.airi.assistant.execution.ToolCallFingerprint
import com.airi.assistant.execution.ToolCallLedger
import com.airi.assistant.execution.stableArgumentsHash
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.HybridOrchestrator
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.ExecutionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlinx.coroutines.isActive
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
    private val calendarCreateRuntime: CalendarCreateRuntime? = null,
    /** PHASE 0 diagnostics; null keeps existing callers behaviorally unchanged. */
    private val runtimeTrace: UniversalRuntimeTraceRecorder? = null,
    /** Generic runtime capability provider; it is diagnostic-only and connector-agnostic. */
    private val capabilitySnapshotProvider: () -> List<com.airi.assistant.core.CapabilitySnapshotEntry> = { emptyList() },
    private val timeoutMs: Long = TIMEOUT_MS
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
        val terminalState: TerminalState = TerminalState.SUCCESS,
        val failureMessage: String? = null,
    )

    enum class TerminalState { SUCCESS, FAILURE, TIMEOUT, CANCELLED, NO_RESPONSE }

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
        modelId:      String              = "",
        providerId:   String              = "",
        sessionId:      String                 = "",
        priorConversation: List<ExecutionRequest.ConversationTurn> = emptyList(),
        visionParts:    List<com.airi.assistant.execution.ExecutionRequest.ImagePart> = emptyList(),
        attachmentParts: List<com.airi.assistant.execution.ExecutionRequest.InlineDataPart> = emptyList(),
        attachmentTrace: com.airi.assistant.execution.AttachmentDeliveryTrace? = null,
        onToken:        suspend (String) -> Unit,
        onStepComplete: suspend (StepEvent) -> String? = { null },
        executionContextFactory: AgentLoopExecutionContextFactory? = null
    ): LoopResult {
        val startMs      = System.currentTimeMillis()
        val toolsInvoked = mutableListOf<String>()
        val history      = priorConversation.mapNotNull { turn ->
            when (turn.role.lowercase()) {
                "user" -> ConversationTurn.User(turn.content)
                "assistant" -> ConversationTurn.Assistant(turn.content)
                else -> null
            }
        }.toMutableList()
        val priorHistoryCount = history.size
        var stepsUsed    = 0
        val executionId   = UUID.randomUUID().toString()
        val requestIdentity = ChatExecutionIdentityContract.create(sessionId, executionId)
        val traceId = runtimeTrace?.begin(
            executionId = executionId,
            sessionId = sessionId,
            modelId = modelId,
            providerId = providerId,
            executionMode = if (providerId.isBlank()) "local_or_default" else "cloud_or_routed",
            input = input,
            tools = tools,
            additionalCapabilities = runCatching { capabilitySnapshotProvider() }.getOrDefault(emptyList()),
        )
        val toolLedger = ToolCallLedger()
        var isPlanPublished = false
        var durableExecutionContext: AgentLoopExecutionContext? = null
        var activeToolTrace: ActiveToolTrace? = null

        val fullSystemPrompt = systemPrompt + "\n\n" + buildToolBlock(tools) + TOOL_CALL_INSTRUCTION
        history.add(ConversationTurn.User(input))
        // Register ownership before any completion/cancellation event. The bus
        // rejects terminal events from unknown execution ids; without this start
        // event a previous run can leave isWorking=true on the chat screen.
        ExecutionStatusBus.onGraphStarted(
            goalDescription = input.take(80),
            totalNodes = if (tools.isEmpty()) 1 else MAX_STEPS,
            executionId = executionId,
        )
        isPlanPublished = true

        try {
            return withTimeout(timeoutMs.coerceAtLeast(1L)) {
            // If no tools provided, single-pass inference.
            if (tools.isEmpty()) {
                val response = callLLM(
                    prompt = input,
                    systemPrompt = systemPrompt,
                    history = history,
                    tools = tools,
                    queryType = queryType,
                    modelId = modelId,
                    providerId = providerId,
                    visionParts = visionParts,
                    attachmentParts = attachmentParts,
                    attachmentTrace = attachmentTrace,
                    onToken = onToken,
                    identity = requestIdentity,
                    localHistoryStartIndex = priorHistoryCount,
                )
                runtimeTrace?.modelResponse(
                    executionId = executionId,
                    sessionId = sessionId,
                    response = response,
                    containsToolCallCandidate = response.contains("tool_call"),
                    parserInputClassification = "single_pass"
                )
                runtimeTrace?.continuation(executionId, sessionId, continued = false, terminalState = if (response.isBlank()) "NO_RESPONSE" else "SUCCESS")
                return@withTimeout if (response.isBlank()) {
                    ExecutionStatusBus.onGraphCompleted(false, executionId = executionId)
                    LoopResult("", 1, emptyList(), terminalState = TerminalState.NO_RESPONSE)
                } else {
                    ExecutionStatusBus.onGraphCompleted(true, executionId = executionId)
                    LoopResult(response, 1, emptyList(), terminalState = TerminalState.SUCCESS)
                }
            }

            while (stepsUsed < MAX_STEPS && coroutineContext.isActive) {
                if (System.currentTimeMillis() - startMs >= timeoutMs) {
                    Log.w(TAG, "AgentLoop timed out after ${stepsUsed} steps")
                    ExecutionStatusBus.onGraphCompleted(false, executionId = executionId)
                    return@withTimeout LoopResult("", stepsUsed, toolsInvoked, terminalState = TerminalState.TIMEOUT)
                }

                stepsUsed++

                val tokenBuffer = StringBuilder()
                val rawResponse = callLLM(
                    prompt       = "", // history carries the full context
                    systemPrompt = fullSystemPrompt,
                    history      = history,
                    tools        = tools,
                    queryType    = queryType,
                    modelId = modelId,
                    providerId = providerId,
                    visionParts = visionParts,
                    attachmentParts = attachmentParts,
                    attachmentTrace = attachmentTrace,
                    onToken       = { tok ->
                        tokenBuffer.append(tok)
                        onToken(tok)
                    },
                    identity      = requestIdentity,
                    localHistoryStartIndex = priorHistoryCount,
                )

                Log.d(TAG, "Agent step completed: step=$stepsUsed responseChars=${rawResponse.length}")
                runtimeTrace?.modelResponse(
                    executionId = executionId,
                    sessionId = sessionId,
                    response = rawResponse,
                    containsToolCallCandidate = rawResponse.contains("tool_call"),
                    parserInputClassification = "agent_step"
                )
                if (rawResponse.isBlank()) {
                    runtimeTrace?.continuation(executionId, sessionId, continued = false, terminalState = "NO_RESPONSE")
                    ExecutionStatusBus.onGraphCompleted(false, executionId = executionId)
                    return@withTimeout LoopResult("", stepsUsed, toolsInvoked, terminalState = TerminalState.NO_RESPONSE)
                }

                // Parse: tool_call block or final answer?
                var parseResult = com.airi.assistant.agent.loop.tool.TextToolCallProtocol.parse(rawResponse)
                var toolCall = (parseResult as? com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Call)
                    ?.let { it.name to it.args }
                runtimeTrace?.parser(
                    executionId = executionId,
                    sessionId = sessionId,
                    parsed = toolCall != null,
                    toolName = toolCall?.first,
                    reason = when (parseResult) {
                        is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Invalid -> parseResult.reason.name
                        com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.NotAToolCall -> "NO_CANDIDATE"
                        is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Call -> null
                    },
                )

                // Retry: if the response looks like a malformed tool call (contains
                // "tool_call" text but JSON parsing failed), ask the model to re-emit
                // only the JSON. This handles cases where the model wraps the JSON in
                // prose or uses a slightly wrong format on the first attempt.
                if (parseResult is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Invalid &&
                    rawResponse.contains("tool_call") && stepsUsed < MAX_STEPS
                ) {
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
                            modelId = modelId,
                            providerId = providerId,
                            visionParts = visionParts,
                            attachmentParts = attachmentParts,
                            attachmentTrace = attachmentTrace,
                            onToken        = {},   // don't stream retry to UI
                            identity       = requestIdentity,
                            localHistoryStartIndex = priorHistoryCount,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Tool call retry callLLM failed: ${e.message}")
                        ""
                    }
                    if (retryResponse.isNotBlank()) {
                        parseResult = com.airi.assistant.agent.loop.tool.TextToolCallProtocol.parse(retryResponse)
                        toolCall = (parseResult as? com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Call)
                            ?.let { it.name to it.args }
                        runtimeTrace?.modelResponse(
                            executionId = executionId,
                            sessionId = sessionId,
                            response = retryResponse,
                            containsToolCallCandidate = retryResponse.contains("tool_call"),
                            parserInputClassification = "tool_call_retry",
                        )
                        runtimeTrace?.parser(
                            executionId = executionId,
                            sessionId = sessionId,
                            parsed = toolCall != null,
                            toolName = toolCall?.first,
                            reason = when (parseResult) {
                                is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Invalid ->
                                    "RETRY_${parseResult.reason.name}"
                                com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.NotAToolCall ->
                                    "RETRY_NO_CANDIDATE"
                                is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Call -> null
                            },
                        )
                        if (toolCall != null) {
                            Log.i(TAG, "AIRI TOOL_CALL_RETRY_OK step=$stepsUsed tool=${toolCall.first}")
                        }
                    }
                }

                if (toolCall == null) {
                    // Final answer — LLM decided it's done (or retry also failed)
                    history.add(ConversationTurn.Assistant(rawResponse))
                    onStepComplete(StepEvent.FinalAnswer(rawResponse, stepsUsed))
                    runtimeTrace?.continuation(executionId, sessionId, continued = false, terminalState = "SUCCESS")
                    ExecutionStatusBus.onGraphCompleted(true, executionId = executionId)
                    return@withTimeout LoopResult(rawResponse, stepsUsed, toolsInvoked)
                }

                // Execute the tool
                val toolName = toolCall.first
                val toolArgs = toolCall.second
                val toolValidationError = validateToolCall(toolName, toolArgs, tools)
                runtimeTrace?.selected(
                    executionId = executionId,
                    sessionId = sessionId,
                    toolName = toolName,
                    valid = toolValidationError == null,
                )
                val selectedToolSchema = tools.firstOrNull { it.name == toolName }
                if (selectedToolSchema != null) {
                    val actualPath = when {
                        toolName.startsWith("skill_") -> com.airi.assistant.core.RuntimeExecutionPath.SKILL_BRIDGE
                        toolName.startsWith("connector_") -> com.airi.assistant.core.RuntimeExecutionPath.CONNECTOR_RUNTIME
                        else -> com.airi.assistant.core.RuntimeExecutionPath.TOOL_DISPATCHER
                    }
                    runtimeTrace?.pathResolved(
                        executionId = executionId,
                        sessionId = sessionId,
                        route = com.airi.assistant.core.UniversalExecutionPathResolver.resolve(
                            tool = selectedToolSchema,
                            runtimePath = actualPath,
                        )
                    )
                }
                toolsInvoked.add(toolName)
                val toolStepId = "tool_${stepsUsed}_$toolName"
                val toolFingerprint = ToolCallFingerprint(
                    executionId = executionId,
                    toolName = toolName,
                    argumentsHash = stableArgumentsHash(
                        toolArgs.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
                    ),
                    parentStepId = toolStepId,
                )
                val duplicateToolCall = !toolLedger.shouldExecute(toolFingerprint)

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
                val toolStartedAtMs = System.currentTimeMillis()
                ExecutionStatusBus.onWaveStarted(
                    nodeIds = listOf(toolStepId),
                    nodeActions = listOf(toolName),
                    executionId = executionId,
                )

                ExecutionStatusBus.onToolStarted(
                    executionId = executionId,
                    actionId = toolStepId,
                    toolName = toolName,
                    detail = "Agent step $stepsUsed",
                )
                activeToolTrace = ActiveToolTrace(
                    executionId = executionId,
                    actionId = toolStepId,
                    toolName = toolName,
                    startedAtMs = toolStartedAtMs,
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
                val toolResult = if (toolValidationError != null) {
                    Log.w(TAG, "AIRI TOOL_REJECTED_INVALID_SCHEMA tool=$toolName reason=$toolValidationError")
                    ToolDispatcher.ToolResult.Error(toolValidationError)
                } else if (duplicateToolCall) {
                    Log.w(TAG, "AIRI TOOL_DUPLICATE_BLOCKED execution=$executionId tool=$toolName step=$toolStepId")
                    ToolDispatcher.ToolResult.Error("Duplicate tool call blocked for this execution step.")
                } else when (sideEffectDecision) {
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
                        runtimeTrace?.dispatched(executionId, sessionId, toolName, "agent_sandbox_or_tool_dispatcher")
                        // Route read-only tools through AgentSandbox when available so
                        // permission checks and workspace logging remain applied.
                        if (agentSandbox != null) {
                            agentSandbox.execute(agentId = SANDBOX_AGENT_ID) { ctx ->
                                ctx.guardTool(toolName)
                                dispatcher.execute(toolName, toolArgs, appContext, executionId)
                            }
                        } else {
                            dispatcher.execute(toolName, toolArgs, appContext, executionId)
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

                val toolDurationMs = System.currentTimeMillis() - toolStartedAtMs
                runtimeTrace?.executionCompleted(
                    executionId = executionId,
                    sessionId = sessionId,
                    toolName = toolName,
                    success = toolResult is ToolDispatcher.ToolResult.Success,
                    durationMs = toolDurationMs,
                    resultLength = resultText.length,
                    errorCategory = (toolResult as? ToolDispatcher.ToolResult.Error)?.message,
                )
                when (toolResult) {
                    is ToolDispatcher.ToolResult.Success -> ExecutionStatusBus.onToolCompleted(
                        executionId = executionId,
                        actionId = toolStepId,
                        toolName = toolName,
                        durationMs = toolDurationMs,
                        detail = "Tool returned a result.",
                    )
                    is ToolDispatcher.ToolResult.Error -> ExecutionStatusBus.onToolFailed(
                        executionId = executionId,
                        actionId = toolStepId,
                        toolName = toolName,
                        durationMs = toolDurationMs,
                        detail = "Tool reported an error.",
                    )
                }

                if (toolResult is ToolDispatcher.ToolResult.Success) {
                    toolLedger.markCompleted(toolFingerprint)
                }
                activeToolTrace = null
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
                runtimeTrace?.resultReturned(executionId, sessionId, toolName)
                runtimeTrace?.continuation(executionId, sessionId, continued = stepsUsed < MAX_STEPS, terminalState = "TOOL_RESULT_RETURNED")
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
            val summary = callLLM(
                prompt = "",
                systemPrompt = systemPrompt + "\n\nYou have no tools available for this final response. Synthesize the completed results and provide a concise answer to the user.",
                history = history,
                tools = emptyList(),
                queryType = queryType,
                modelId = modelId,
                providerId = providerId,
                visionParts = visionParts,
                attachmentParts = attachmentParts,
                attachmentTrace = attachmentTrace,
                onToken = onToken,
                identity = requestIdentity,
                localHistoryStartIndex = priorHistoryCount,
            )
            if (summary.isBlank()) {
                ExecutionStatusBus.onGraphCompleted(false, executionId = executionId)
                return@withTimeout LoopResult("", stepsUsed, toolsInvoked, terminalState = TerminalState.NO_RESPONSE)
            }
            ExecutionStatusBus.onGraphCompleted(true, executionId = executionId)
            return@withTimeout LoopResult(summary, stepsUsed, toolsInvoked, terminalState = TerminalState.SUCCESS)
            }

        } catch (e: TimeoutCancellationException) {
            activeToolTrace?.let { active ->
                ExecutionStatusBus.onToolCancelled(
                    executionId = active.executionId,
                    actionId = active.actionId,
                    toolName = active.toolName,
                    durationMs = System.currentTimeMillis() - active.startedAtMs,
                    detail = "Agent execution timed out.",
                )
            }
            ExecutionStatusBus.onGraphCompleted(false, executionId = executionId)
            Log.w(TAG, "AgentLoop reached its ${timeoutMs}ms execution deadline")
            return LoopResult(
                finalAnswer = "",
                stepsUsed = stepsUsed,
                toolsInvoked = toolsInvoked,
                terminalState = TerminalState.TIMEOUT,
                failureMessage = "Agent execution timed out.",
            )
        } catch (e: CancellationException) {
            activeToolTrace?.let { active ->
                ExecutionStatusBus.onToolCancelled(
                    executionId = active.executionId,
                    actionId = active.actionId,
                    toolName = active.toolName,
                    durationMs = System.currentTimeMillis() - active.startedAtMs,
                    detail = "Tool was cancelled.",
                )
            }
            ExecutionStatusBus.onGraphCancelled(executionId)
            return LoopResult("", stepsUsed, toolsInvoked, terminalState = TerminalState.CANCELLED)
        } catch (e: Exception) {
            ExecutionStatusBus.onGraphCompleted(false, executionId = executionId)
            Log.e(TAG, "AgentLoop failed type=${e.javaClass.simpleName}", e)
            return LoopResult(
                finalAnswer = "",
                stepsUsed = stepsUsed,
                toolsInvoked = toolsInvoked,
                terminalState = TerminalState.FAILURE,
                failureMessage = e.message,
            )
        }
    }

    // ── LLM call (always through HybridOrchestrator) ───────────────────────────

    private suspend fun callLLM(
        prompt:       String,
        systemPrompt: String,
        history:      List<ConversationTurn>,
        tools:        List<ToolSchema>,
        queryType:      QueryType = QueryType.UNKNOWN,
        modelId:      String = "",
        providerId:   String = "",
        visionParts:   List<com.airi.assistant.execution.ExecutionRequest.ImagePart> = emptyList(),
        attachmentParts: List<com.airi.assistant.execution.ExecutionRequest.InlineDataPart> = emptyList(),
        attachmentTrace: com.airi.assistant.execution.AttachmentDeliveryTrace? = null,
        onToken:       suspend (String) -> Unit,
        identity:      ExecutionIdentity,
        localHistoryStartIndex: Int = 0,
    ): String {
        val requestTurns = history.map { turn ->
            when (turn) {
                is ConversationTurn.User -> ExecutionRequest.ConversationTurn("user", turn.content)
                is ConversationTurn.Assistant -> ExecutionRequest.ConversationTurn("assistant", turn.content)
                is ConversationTurn.ToolResult -> ExecutionRequest.ConversationTurn(
                    "user", "[Tool ${turn.toolName}: ${turn.result.take(400)}]"
                )
            }
        }
        val currentPromptText = (history.lastOrNull() as? ConversationTurn.User)?.content
        val requestProjection = ConversationRequestPolicy.project(requestTurns, currentPromptText)
        val localTurns = history.drop(localHistoryStartIndex.coerceIn(0, history.size))
        val localPrompt = if (localTurns.isEmpty()) prompt else buildString {
            for (turn in localTurns) {
                when (turn) {
                    is ConversationTurn.User -> append("User: ${turn.content}\n")
                    is ConversationTurn.Assistant -> append("Assistant: ${turn.content}\n")
                    is ConversationTurn.ToolResult -> append("[Tool ${turn.toolName} returned: ${turn.result.take(500)}]\n")
                }
            }
            append("Assistant:")
        }

        // SPRINT 1: Estimate token count and derive the long-context threshold from
        // the live ContextBudget (LlamaNative.getNCtx() → ContextBudget.longContextThreshold)
        // rather than the former hardcoded constant of 8_192.
        // For a 1536-token model: threshold = 768; for 32K: threshold = 16384.
        val estimatedTokens = AgentPromptTokenEstimator.estimate(
            systemPrompt = systemPrompt,
            localPrompt = localPrompt,
            projection = requestProjection,
        )
        val longContextThreshold = contextBudgetProvider().longContextThreshold

        val buf = StringBuilder()
        var error: String? = null

        // Stop generation immediately if the model emits the start of a tool_call block —
        // we don't need to stream it token-by-token to the user.
        var inToolCall = false

        orchestrator.executeStream(
            request    = ExecutionRequest(
                prompt                = requestProjection.prompt,
                localPrompt           = localPrompt,
                systemPrompt          = systemPrompt,
                maxTokens             = 1024,   // : was 512 — too low for complex tool JSON + reasoning
                temperature           = 0.3f,   // low temp for structured decisions
                queryType             = queryType,
                requiresStreaming      = true,
                requiresLongContext   = estimatedTokens > longContextThreshold,
                estimatedPromptTokens = estimatedTokens,
                sessionTag            = "agent_loop",
                requestedProviderId   = providerId,
                requestedModelId      = modelId,
                requiresVision        = visionParts.isNotEmpty() || attachmentParts.isNotEmpty(),
                imageParts            = visionParts,
                inlineDataParts       = attachmentParts,
                attachmentTrace       = attachmentTrace,
                identity              = identity,

                conversationHistory   = requestProjection.conversationHistory
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

    /**
     * Validate model output against the exact capability set supplied to this
     * run. The prompt is advisory; this is the enforcement boundary.
     */
    private fun validateToolCall(
        toolName: String,
        args: Map<String, String>,
        tools: List<ToolSchema>,
    ): String? {
        val schema = tools.firstOrNull { it.name == toolName }
            ?: return "Tool '$toolName' is not available in this session."
        val unknown = args.keys - schema.parameters.keys
        if (unknown.isNotEmpty()) {
            return "Tool '$toolName' received unsupported parameters: ${unknown.sorted().joinToString(", ")}."
        }
        val missing = schema.parameters
            .filter { (name, parameter) -> parameter.required && args[name].isNullOrBlank() }
            .keys
        if (missing.isNotEmpty()) {
            return "Tool '$toolName' is missing required parameters: ${missing.sorted().joinToString(", ")}."
        }
        return null
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

    private data class ActiveToolTrace(
        val executionId: String,
        val actionId: String,
        val toolName: String,
        val startedAtMs: Long,
    )

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
