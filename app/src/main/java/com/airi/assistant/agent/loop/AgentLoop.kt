package com.airi.assistant.agent.loop

import android.content.Context
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.ai.agent.SelfHealingExecutor
import com.airi.assistant.agent.calendar.CalendarCreateRuntime
import com.airi.assistant.agent.loop.tool.ToolDispatcher
import com.airi.assistant.agent.loop.tool.ToolSchema
import com.airi.assistant.agent.loop.tool.ToolSpecAdapter
import com.airi.assistant.accessibility.security.AccessibilityPolicyGuard
import com.airi.assistant.ai.OutputBudgetPolicy
import com.airi.assistant.ai.QueryType
import com.airi.assistant.ai.ResponseDepth
import com.airi.assistant.ai.ResponseDepthPolicy
import com.airi.assistant.ai.ResponseOptimizer
import com.airi.assistant.ai.context.ContextBudget
import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.core.UniversalRuntimeTraceRecorder
import com.airi.assistant.execution.CompletionMetadata
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.AgentPromptTokenEstimator
import com.airi.assistant.execution.ExecutionIdentity
import com.airi.assistant.execution.ChatExecutionIdentityContract
import com.airi.assistant.execution.ConversationRequestPolicy
import com.airi.assistant.execution.ToolCallFingerprint
import com.airi.assistant.execution.ToolCallLedger
import com.airi.assistant.execution.stableArgumentsHash
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.AgentOrchestrator
import com.airi.assistant.domain.tool.StrictArgumentValidator
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.ExecutionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val orchestrator:          AgentOrchestrator,
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
        //
        // Report 1 / stage 8: this is now a *stall* timeout, not a wall-clock
        // limit on the whole answer. It is measured from the last sign of
        // progress (a streamed token, or a finished tool call), so a long but
        // healthy answer is no longer cut at 120s. Time to the first token is
        // allowed twice this long because on-device prefill is slow.
        private const val TIMEOUT_MS       = 120_000L
        /** Absolute ceiling for one request, however much progress it makes. */
        private const val HARD_CAP_MS      = 15 * 60_000L
        private const val MAX_PLATFORM_SNAPSHOT_CHARS = 12_000
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
When independent tools are required in the same step, use one ordered envelope:
{"tool_calls":[{"id":"call-1","name":"<tool_name>","args":{}},{"id":"call-2","name":"<tool_name>","args":{}}]}

When you have a complete answer for the user, respond normally in plain text.
Do not mix tool_call JSON with prose in the same message.
When a tool is present in the tool list, use it to verify the capability before
claiming that the capability is unavailable. Only report it as unavailable after
the tool returns an actual error.
"""
    }

    private fun ToolSchema.Param.toExecutionParameter(name: String): ExecutionRequest.ToolParameter =
        ExecutionRequest.ToolParameter(
            name = name,
            type = type,
            description = description,
            required = required,
            enumValues = allowedValues.toList(),
            items = items?.toExecutionParameter(name = "item"),
            properties = properties.map { (propertyName, property) ->
                property.toExecutionParameter(propertyName)
            },
        )

    data class LoopResult(
        val finalAnswer:  String,
        val stepsUsed:    Int,
        val toolsInvoked: List<String>,
        val terminalState: TerminalState = TerminalState.SUCCESS,
        val failureMessage: String? = null,
        val completionMetadata: CompletionMetadata = CompletionMetadata(),
    )

    enum class TerminalState { SUCCESS, FAILURE, TIMEOUT, CANCELLED, NO_RESPONSE }

    /** Raised inside a model call that produced no progress for too long. */
    private class StallTimeoutException(stallMs: Long) :
        CancellationException("No progress for ${stallMs}ms")

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
        executionContextFactory: AgentLoopExecutionContextFactory? = null,
        /** Request-scoped capability state produced by the canonical runtime catalog. */
        runtimeCapabilities: List<com.airi.assistant.core.CapabilitySnapshotEntry> = emptyList(),
        /** Redacted platform snapshot rendered into every agent-turn system prompt. */
        platformCapabilitySnapshot: String = "",
        /** Stable caller-owned identity used to join agent, runtime, and UI traces. */
        requestedExecutionId: String? = null,
        /** Optional message coordinate used by connector and artifact runtimes. */
        messageId: String = "",
        /** When enabled, a plan-only first turn must be followed by an execution turn. */
        planMode: Boolean = false,
    ): LoopResult {
        val startMs      = System.currentTimeMillis()
        // Last sign of progress; see TIMEOUT_MS. Streaming tokens and finished tool calls bump it.
        val progressMs   = AtomicLong(startMs)
        val permissionProfile = com.airi.assistant.core.AgentPermissionProfile.resolve(
            queryType = queryType,
            modelId = modelId,
            providerId = providerId,
            toolsRequested = tools.isNotEmpty(),
        )
        val permissionFilteredTools = AgentLoopToolAdmission.filter(
            tools = permissionProfile.filterTools(tools),
            hasExecutionContext = executionContextFactory != null,
            hasCalendarRuntime = calendarCreateRuntime != null,
            terminalEnabled = com.airi.assistant.domain.terminal.TerminalExecutionPolicy
                .evaluate("echo").allowed,
        )
        val toolPromptBudget = com.airi.assistant.ai.prompt.budget.ContributorBudgetPolicy
            .toolCharsCapFor(providerId, contextBudgetProvider().availableForContent)
        val toolSelection = com.airi.assistant.agent.loop.tool.ToolSchemaPromptBudget.select(
            permissionFilteredTools,
            toolPromptBudget,
        )
        // Schemas omitted by the context budget are not dispatcher-eligible.
        val effectiveTools = toolSelection.tools
        if (toolSelection.omittedToolNames.isNotEmpty()) {
            LoggingService.warn(
                TAG,
                "TOOL_BLOCK_TRIMMED kept=${effectiveTools.map { it.name }} omitted=${toolSelection.omittedToolNames} budgetChars=$toolPromptBudget"
            )
        }
        val toolsInvoked = mutableListOf<String>()
        // Report 3, S8: set once third-party content has entered this loop's context.
        var untrustedContentSeen = false
        var completionMetadata = CompletionMetadata()
        val history      = priorConversation.mapNotNull { turn ->
            when (turn.role.lowercase()) {
                "user" -> ConversationTurn.User(turn.content)
                "assistant" -> ConversationTurn.Assistant(turn.content)
                else -> null
            }
        }.toMutableList()
        val priorHistoryCount = history.size
        var stepsUsed    = 0
        val executionId   = requestedExecutionId?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        val traceId = "trace-$executionId"
        val requestIdentity = ChatExecutionIdentityContract.create(sessionId, executionId)
        runtimeTrace?.begin(
            executionId = executionId,
            sessionId = sessionId,
            modelId = modelId,
            providerId = providerId,
            executionMode = if (providerId.isBlank()) "local_or_default" else "cloud_or_routed",
            input = input,
            tools = effectiveTools,
            additionalCapabilities = runtimeCapabilities.ifEmpty {
                runCatching { capabilitySnapshotProvider() }.getOrDefault(emptyList())
            },
            permissionProfile = permissionProfile,
            requestedTraceId = traceId,
        )
        runtimeTrace?.intentStage(executionId, sessionId, "INTENT_RAW", "received")
        runtimeTrace?.intentStage(executionId, sessionId, "INTENT_NORMALIZED", "normalized")
        runtimeTrace?.intentStage(executionId, sessionId, "INTENT_DETECTED", "capability_detector")
        runtimeTrace?.intentStage(executionId, sessionId, "INTENT_RESOLVED", "agent_loop")
        runtimeTrace?.intentStage(executionId, sessionId, "CAPABILITY_REQUESTED", if (tools.isEmpty()) "none" else "requested")
        runtimeTrace?.intentStage(executionId, sessionId, "CATALOG_ASSEMBLED", if (effectiveTools.isEmpty()) "empty" else "assembled")
        effectiveTools.forEach { tool ->
            runtimeTrace?.intentStage(executionId, sessionId, "TOOL_EXPOSED", "exposed", capabilityId = tool.name)
            runtimeTrace?.intentStage(executionId, sessionId, "TOOL_ADMITTED", "admitted", capabilityId = tool.name)
        }
        val toolLedger = ToolCallLedger()
        var isPlanPublished = false
        var durableExecutionContext: AgentLoopExecutionContext? = null
        var activeToolTrace: ActiveToolTrace? = null
        val pendingToolCalls = ArrayDeque<com.airi.assistant.ai.intent.ToolCall>()

        val platformBlock = platformCapabilitySnapshot.trim().take(MAX_PLATFORM_SNAPSHOT_CHARS)
        val promptWithPlatformSnapshot = if (platformBlock.isBlank() || systemPrompt.contains("AIRI PLATFORM CAPABILITY SNAPSHOT")) systemPrompt else
            systemPrompt + "\n\n" + platformBlock
        val fullSystemPrompt = promptWithPlatformSnapshot + "\n\n" + toolSelection.promptBlock + TOOL_CALL_INSTRUCTION
        history.add(ConversationTurn.User(input))
        val capabilityIntent = com.airi.assistant.ai.CapabilityIntentDetector.detect(input)
        val admission = ExecutionAdmissionPolicy.decide(
            input = input,
            queryType = queryType,
            effectiveTools = effectiveTools,
            attachmentCount = visionParts.size + attachmentParts.size,
        )
        // A direct response must not create a visible plan. For admitted work,
        // publish only the real known count; 0 means "not declared" rather than
        // a fabricated estimate based on the loop ceiling or tool catalog size.
        if (admission.admitted) {
            ExecutionStatusBus.onGraphStarted(
                goalDescription = input.take(80),
                totalNodes = admission.plannedStepCount,
                executionId = executionId,
                sessionId = sessionId,
            )
            isPlanPublished = true
        }
        // Device time/date is a deterministic system fact. Execute it before
        // the first model turn so a short question cannot degrade into a
        // hallucinated refusal merely because the model did not emit a tool
        // call. The result is still returned through the normal conversation
        // context so the model formats the answer naturally.
        if (capabilityIntent.requires(
                com.airi.assistant.ai.CapabilityIntentDetector.Capability.CURRENT_TIME
        ) && effectiveTools.any { it.name == "current_time" }
        ) {
            val actionId = "tool_deterministic_current_time"
            val startedAtMs = System.currentTimeMillis()
            activeToolTrace = ActiveToolTrace(executionId, actionId, "current_time", startedAtMs)
            ExecutionStatusBus.onToolStarted(
                executionId = executionId,
                actionId = actionId,
                toolName = "current_time",
                detail = "Reading the device clock.",
                inputParams = emptyMap(),
            )
            val timeSchema = effectiveTools.first { it.name == "current_time" }
            val timeValidation = StrictArgumentValidator.validate(
                spec = ToolSpecAdapter.fromSchema(timeSchema),
                raw = emptyMap(),
            )
            val timeWasDispatched = timeValidation.isValid
            val timeResult = if (!timeValidation.isValid) {
                ToolDispatcher.ToolResult.Error(
                    timeValidation.failure?.message ?: "Invalid current_time arguments",
                    code = com.airi.assistant.agent.loop.tool.ToolErrorCodes.INVALID_ARGUMENT,
                )
            } else try {
                runtimeTrace?.pathResolved(
                    executionId = executionId,
                    sessionId = sessionId,
                    route = com.airi.assistant.core.UniversalExecutionPathResolver.resolve(
                        tool = timeSchema,
                        runtimePath = com.airi.assistant.core.RuntimeExecutionPath.TOOL_DISPATCHER,
                    ),
                )
                runtimeTrace?.dispatched(
                    executionId = executionId,
                    sessionId = sessionId,
                    toolName = "current_time",
                    path = "intent_gated_deterministic_builtin",
                )
                dispatcher.execute("current_time", timeValidation.arguments!!.canonical, appContext, executionId, actionId = actionId, traceId = traceId, sessionId = sessionId, messageId = messageId)
            } catch (cancelled: CancellationException) {
                ExecutionStatusBus.onToolCancelled(
                    executionId = executionId,
                    actionId = actionId,
                    toolName = "current_time",
                    durationMs = System.currentTimeMillis() - startedAtMs,
                    detail = "Time lookup was cancelled.",
                )
                activeToolTrace = null
                ExecutionStatusBus.onGraphCancelled(executionId)
                throw cancelled
            } catch (error: Exception) {
                ToolDispatcher.ToolResult.Error(
                    "Unable to read device time: ${error.message ?: "unknown error"}",
                    code = com.airi.assistant.agent.loop.tool.ToolErrorCodes.EXECUTION_FAILED,
                )
            }
            val timeText = when (timeResult) {
                is ToolDispatcher.ToolResult.Success -> timeResult.output
                is ToolDispatcher.ToolResult.Error -> "Error: ${timeResult.message}"
            }
            val durationMs = System.currentTimeMillis() - startedAtMs
            val timeSucceeded = timeResult is ToolDispatcher.ToolResult.Success
            if (timeWasDispatched) {
                runtimeTrace?.executionCompleted(
                    executionId = executionId,
                    sessionId = sessionId,
                    toolName = "current_time",
                    success = timeSucceeded,
                    durationMs = durationMs,
                    resultLength = timeText.length,
                    errorCategory = (timeResult as? ToolDispatcher.ToolResult.Error)?.code,
                )
                runtimeTrace?.capabilityStage(
                    executionId,
                    sessionId,
                    "current_time",
                    com.airi.assistant.core.CapabilityRuntimeStage.EXECUTED,
                    if (timeSucceeded) "deterministic_success" else "deterministic_failure",
                )
            }
            when (timeResult) {
                is ToolDispatcher.ToolResult.Success -> ExecutionStatusBus.onToolCompleted(
                    executionId, actionId, "current_time", durationMs, "Device time read completed.",
                )
                is ToolDispatcher.ToolResult.Error -> ExecutionStatusBus.onToolFailed(
                    executionId, actionId, "current_time", durationMs, "Device time read failed.",
                )
            }
            activeToolTrace = null
            toolsInvoked += "current_time"
            history.add(ConversationTurn.ToolResult("current_time", timeText))
            if (timeWasDispatched) {
                runtimeTrace?.resultReturned(executionId, sessionId, "current_time")
                runtimeTrace?.capabilityStage(
                    executionId,
                    sessionId,
                    "current_time",
                    com.airi.assistant.core.CapabilityRuntimeStage.RESULT_RETURNED,
                    "returned_to_model_context",
                )
            }
            LoggingService.info(TAG, "AIRI DETERMINISTIC_TOOL tool=current_time success=${timeResult is ToolDispatcher.ToolResult.Success}")
        }
        try {
            // Stall timeout is checked against progressMs inside the loop;
            // this outer timeout is the absolute request ceiling. maxOf()
            // would let a caller bypass the hard cap with a larger timeout.
            return withTimeout(HARD_CAP_MS) {
            // If no tools provided, single-pass inference.
            if (effectiveTools.isEmpty()) {
                val response = callLLM(
                    prompt = input,
                    systemPrompt = promptWithPlatformSnapshot,
                    history = history,
                    tools = effectiveTools,
                    queryType = queryType,
                    modelId = modelId,
                    providerId = providerId,
                    visionParts = visionParts,
                    attachmentParts = attachmentParts,
                    attachmentTrace = attachmentTrace,
                    onToken = onToken,
                    identity = requestIdentity,
                    localHistoryStartIndex = priorHistoryCount,
                    onCompletionMetadata = { completionMetadata = it },
                    progress = progressMs,
                    stallMs = timeoutMs,
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
                    LoopResult("", 1, emptyList(), terminalState = TerminalState.NO_RESPONSE, completionMetadata = completionMetadata)
                } else {
                    ExecutionStatusBus.onGraphCompleted(true, executionId = executionId)
                    LoopResult(response, 1, emptyList(), terminalState = TerminalState.SUCCESS, completionMetadata = completionMetadata)
                }
            }

            while (stepsUsed < MAX_STEPS && coroutineContext.isActive) {
                if (System.currentTimeMillis() - progressMs.get() >= timeoutMs) {
                    LoggingService.warn(TAG, "AgentLoop stalled after ${stepsUsed} steps")
                    ExecutionStatusBus.onGraphCompleted(false, executionId = executionId)
                    return@withTimeout LoopResult("", stepsUsed, toolsInvoked, terminalState = TerminalState.TIMEOUT)
                }

                stepsUsed++

                val tokenBuffer = StringBuilder()
                val queuedToolCall = pendingToolCalls.removeFirstOrNull()
                val rawResponse = queuedToolCall?.let {
                    com.airi.assistant.agent.loop.tool.TextToolCallProtocol.encode(it)
                } ?: callLLM(
                    prompt       = "", // history carries the full context
                    systemPrompt = fullSystemPrompt,
                    history      = history,
                    tools        = effectiveTools,
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
                    onCompletionMetadata = { completionMetadata = it },
                    progress = progressMs,
                    stallMs = timeoutMs,
                )

                LoggingService.debug(TAG, "Agent step completed: step=$stepsUsed responseChars=${rawResponse.length}")
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
                    return@withTimeout LoopResult("", stepsUsed, toolsInvoked, terminalState = TerminalState.NO_RESPONSE, completionMetadata = completionMetadata)
                }

                // Parse: tool_call block or final answer?
                val nativeEnvelope = if (queuedToolCall == null) {
                    com.airi.assistant.execution.cloud.NativeToolCallWire.decode(rawResponse)
                } else emptyList()
                var parseResult = if (queuedToolCall != null) {
                    com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Call(
                        queuedToolCall.toolName, queuedToolCall.params, queuedToolCall.id,
                    )
                } else {
                    if (nativeEnvelope.isNotEmpty()) {
                        com.airi.assistant.agent.loop.tool.TextToolCallProtocol.parse(
                            com.airi.assistant.execution.cloud.NativeToolCallWire.encode(nativeEnvelope)
                        )
                    } else {
                        com.airi.assistant.agent.loop.tool.TextToolCallProtocol.parse(rawResponse)
                    }
                }
                var parsedToolCalls = when (parseResult) {
                    is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Call -> listOf(parseResult.toolCall)
                    is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Calls -> parseResult.calls
                    else -> emptyList()
                }
                // P2: execute only independent read-only batches concurrently.
                // Writes and dependency-bearing calls continue through the
                // existing single-tool path below, preserving confirmation and
                // durable-context guarantees.
                val batchSchemas = parsedToolCalls.map { call ->
                    call to effectiveTools.firstOrNull { it.name == call.toolName }
                }
                if (batchSchemas.size > 1 && batchSchemas.all { it.second?.sideEffectPolicy == ToolSchema.SideEffectPolicy.READ_ONLY }) {
                    val batchRequests = batchSchemas.map { (call, schema) ->
                        com.airi.assistant.agent.loop.tool.ToolCallBatchExecutor.Request(
                            id = call.id.ifBlank { "call_${call.toolName}" },
                            toolName = call.toolName,
                            arguments = call.params,
                            schema = schema!!,
                        )
                    }
                    val batchFingerprints = batchRequests.associate { request ->
                        request.id to ToolCallFingerprint(
                            executionId = executionId,
                            toolName = request.toolName,
                            argumentsHash = stableArgumentsHash(
                                request.arguments.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
                            ),
                            parentStepId = "step_$stepsUsed",
                        )
                    }
                    val batchResults = com.airi.assistant.agent.loop.tool.ToolCallBatchExecutor.execute(batchRequests) { request ->
                        val validation = StrictArgumentValidator.validate(
                            spec = ToolSpecAdapter.fromSchema(request.schema),
                            raw = request.arguments,
                        )
                        if (!validation.isValid) {
                            ToolDispatcher.ToolResult.Error(
                                validation.failure?.message ?: "Invalid tool arguments.",
                                code = com.airi.assistant.agent.loop.tool.ToolErrorCodes.INVALID_ARGUMENT,
                            )
                        } else if (batchFingerprints[request.id]?.let { !toolLedger.tryClaim(it) } == true) {
                            ToolDispatcher.ToolResult.Error(
                                "Duplicate tool call blocked for this execution step.",
                                code = com.airi.assistant.agent.loop.tool.ToolErrorCodes.DUPLICATE_CALL,
                            )
                        } else if (agentSandbox != null) {
                            agentSandbox.execute(agentId = SANDBOX_AGENT_ID) { ctx ->
                                ctx.guardTool(request.toolName)
                                dispatcher.execute(request.toolName, validation.arguments?.canonical ?: request.arguments, appContext, executionId, actionId = "tool_batch_${request.id}", traceId = traceId, sessionId = sessionId, messageId = messageId)
                            }
                        } else {
                            dispatcher.execute(request.toolName, validation.arguments?.canonical ?: request.arguments, appContext, executionId, actionId = "tool_batch_${request.id}", traceId = traceId, sessionId = sessionId, messageId = messageId)
                        }
                    }
                    history.add(ConversationTurn.Assistant(rawResponse))
                    batchResults.forEach { outcome ->
                        val result = outcome.value
                        val resultText = when (result) {
                            is ToolDispatcher.ToolResult.Success -> result.output
                            is ToolDispatcher.ToolResult.Error -> "Error [${result.code}]: ${result.message}"
                        }
                        toolsInvoked += outcome.request.toolName
                        batchFingerprints[outcome.request.id]?.let { fingerprint ->
                            if (result is ToolDispatcher.ToolResult.Success) {
                                toolLedger.markCompleted(fingerprint)
                            } else {
                                toolLedger.release(fingerprint)
                            }
                        }
                        onStepComplete(
                            StepEvent.ToolExecuted(
                                outcome.request.toolName,
                                outcome.request.arguments,
                                resultText,
                                stepsUsed,
                            )
                        )
                        if (result is ToolDispatcher.ToolResult.Success &&
                            AccessibilityPolicyGuard.isExternalContentTool(outcome.request.toolName)
                        ) {
                            untrustedContentSeen = true
                        }
                        history.add(
                            ConversationTurn.ToolResult(
                                outcome.request.toolName,
                                untrustedAwareToolText(outcome.request.toolName, resultText, result is ToolDispatcher.ToolResult.Success),
                                outcome.request.id,
                            )
                        )
                        runtimeTrace?.dispatched(executionId, sessionId, outcome.request.toolName, "parallel_read_batch")
                        runtimeTrace?.executionCompleted(
                            executionId, sessionId, outcome.request.toolName,
                            result is ToolDispatcher.ToolResult.Success,
                            0L,
                            resultText.length,
                            (result as? ToolDispatcher.ToolResult.Error)?.code,
                        )
                        runtimeTrace?.resultReturned(executionId, sessionId, outcome.request.toolName)
                    }
                    progressMs.set(System.currentTimeMillis())
                    runtimeTrace?.continuation(executionId, sessionId, continued = stepsUsed < MAX_STEPS, terminalState = "PARALLEL_READ_BATCH")
                    continue
                }
                if (parsedToolCalls.isNotEmpty()) {
                    pendingToolCalls.addAll(parsedToolCalls.drop(1))
                }
                var toolCall = parsedToolCalls.firstOrNull()
                runtimeTrace?.parser(
                    executionId = executionId,
                    sessionId = sessionId,
                    parsed = toolCall != null,
                    toolName = toolCall?.toolName,
                    reason = when (parseResult) {
                        is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Invalid -> parseResult.reason.name
                        com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.NotAToolCall -> "NO_CANDIDATE"
                        is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Call -> null
                        is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Calls -> null
                    },
                    parserKind = if (nativeEnvelope.isNotEmpty()) "native_tool_envelope" else "text_json_tool_call",
                )

                // Retry: if the response looks like a malformed tool call (contains
                // "tool_call" text but JSON parsing failed), ask the model to re-emit
                // only the JSON. This handles cases where the model wraps the JSON in
                // prose or uses a slightly wrong format on the first attempt.
                if (parseResult is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Invalid &&
                    rawResponse.contains("tool_call") && stepsUsed < MAX_STEPS
                ) {
                    LoggingService.warn(TAG, "AIRI TOOL_CALL_PARSE_RETRY step=$stepsUsed — response had 'tool_call' text but parse failed; retrying")
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
                            tools        = effectiveTools,
                            queryType      = queryType,
                            modelId = modelId,
                            providerId = providerId,
                            visionParts = visionParts,
                            attachmentParts = attachmentParts,
                            attachmentTrace = attachmentTrace,
                            onToken        = {},   // don't stream retry to UI
                            identity       = requestIdentity,
                            localHistoryStartIndex = priorHistoryCount,
                            onCompletionMetadata = { completionMetadata = it },
                            progress       = progressMs,
                            stallMs        = timeoutMs,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LoggingService.warn(TAG, "Tool call retry failed type=${e.javaClass.simpleName}")
                        ""
                    }
                    if (retryResponse.isNotBlank()) {
                        val nativeRetryCalls = com.airi.assistant.execution.cloud.NativeToolCallWire.decode(retryResponse)
                        parseResult = if (nativeRetryCalls.isNotEmpty()) {
                            com.airi.assistant.agent.loop.tool.TextToolCallProtocol.parse(
                                com.airi.assistant.execution.cloud.NativeToolCallWire.encode(nativeRetryCalls)
                            )
                        } else {
                            com.airi.assistant.agent.loop.tool.TextToolCallProtocol.parse(retryResponse)
                        }
                        parsedToolCalls = when (parseResult) {
                            is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Call -> listOf(parseResult.toolCall)
                            is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Calls -> parseResult.calls
                            else -> emptyList()
                        }
                        pendingToolCalls.addAll(parsedToolCalls)
                        toolCall = pendingToolCalls.removeFirstOrNull()
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
                            toolName = toolCall?.toolName,
                            reason = when (parseResult) {
                                is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Invalid ->
                                    "RETRY_${parseResult.reason.name}"
                                com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.NotAToolCall ->
                                    "RETRY_NO_CANDIDATE"
                                is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Call -> null
                                is com.airi.assistant.agent.loop.tool.TextToolCallProtocol.ParseResult.Calls -> null
                            },
                        )
                        if (toolCall != null) {
                            LoggingService.info(TAG, "AIRI TOOL_CALL_RETRY_OK step=$stepsUsed tool=${toolCall.toolName}")
                        }
                    }
                }

                if (toolCall == null) {
                    if (planMode && PlanModePolicy.requiresExecutionContinuation(rawResponse, stepsUsed)) {
                        history.add(ConversationTurn.Assistant(rawResponse))
                        history.add(ConversationTurn.User(PlanModePolicy.EXECUTION_CONTINUATION_PROMPT))
                        runtimeTrace?.continuation(
                            executionId,
                            sessionId,
                            continued = true,
                            terminalState = "PLAN_REQUIRES_EXECUTION",
                        )
                        LoggingService.info(TAG, "AIRI PLAN_CONTINUATION_REQUIRED step=$stepsUsed")
                        continue
                    }
                    // Final answer — LLM decided it's done (or retry also failed)
                    history.add(ConversationTurn.Assistant(rawResponse))
                    onStepComplete(StepEvent.FinalAnswer(rawResponse, stepsUsed))
                    runtimeTrace?.continuation(executionId, sessionId, continued = false, terminalState = "SUCCESS")
                    ExecutionStatusBus.onGraphCompleted(true, executionId = executionId)
                    return@withTimeout LoopResult(rawResponse, stepsUsed, toolsInvoked, completionMetadata = completionMetadata)
                }

                // Execute the tool
                val toolName = toolCall.toolName
                val rawToolArgs = toolCall.params
                val selectedToolSchema = effectiveTools.firstOrNull { it.name == toolName }
                val strictValidation = selectedToolSchema?.let { schema ->
                    StrictArgumentValidator.validate(
                        spec = ToolSpecAdapter.fromSchema(schema),
                        raw = rawToolArgs,
                    )
                }
                val toolValidationError = when {
                    selectedToolSchema == null -> "Tool '$toolName' is not available in this session."
                    strictValidation?.failure != null -> strictValidation.failure?.message ?: "Invalid tool arguments."
                    else -> null
                }
                // Handlers receive only canonical values produced by the strict
                // validator. Raw model arguments never cross this boundary.
                val toolArgs = strictValidation?.arguments?.canonical ?: rawToolArgs
                runtimeTrace?.selected(
                    executionId = executionId,
                    sessionId = sessionId,
                    toolName = toolName,
                    valid = toolValidationError == null,
                )
                runtimeTrace?.capabilityStage(
                    executionId, sessionId, toolName,
                    com.airi.assistant.core.CapabilityRuntimeStage.MODEL_SELECTED,
                    if (toolValidationError == null) "selected" else "rejected",
                )
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
                val stepId = "step_$stepsUsed"
                val toolStepId = "tool_${stepsUsed}_$toolName"
                val toolFingerprint = ToolCallFingerprint(
                    executionId = executionId,
                    toolName = toolName,
                    argumentsHash = stableArgumentsHash(
                        toolArgs.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
                    ),
                    parentStepId = stepId,
                )
                val duplicateToolCall = if (toolValidationError == null) {
                    !toolLedger.tryClaim(toolFingerprint)
                } else {
                    false
                }

                LoggingService.info(TAG, "AIRI TOOL_CALL step=$stepsUsed tool=$toolName args=${toolArgs.keys.joinToString()}")
                if (!isPlanPublished) {
                    ExecutionStatusBus.onGraphStarted(
                        goalDescription = input.take(80),
                        // Actions are discovered incrementally; the ceiling is not a plan.
                        totalNodes = 0,
                        executionId = executionId,
                        sessionId = sessionId,
                    )
                    isPlanPublished = true
                }
                val toolStartedAtMs = System.currentTimeMillis()
                ExecutionStatusBus.onWaveStarted(
                    nodeIds = listOf(stepId),
                    nodeActions = listOf(toolName),
                    executionId = executionId,
                )

                ExecutionStatusBus.onToolStarted(
                    executionId = executionId,
                    actionId = toolStepId,
                    toolName = toolName,
                    detail = "Agent step $stepsUsed",
                    inputParams = toolArgs,
                    parentId = stepId,
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
                    (toolName == "calendar_create" || toolName == "terminal_execute" || toolName == "write_file") &&
                    durableExecutionContext == null &&
                    executionContextFactory != null
                ) {
                    durableExecutionContext = executionContextFactory?.createFor(toolName)
                }
                val sideEffectDecision = AgentLoopSideEffectPolicy.decide(
                    schema = selectedToolSchema ?: ToolSchema(name = toolName, description = "Unknown tool"),
                    hasDurableExecutionContext = durableExecutionContext != null
                )
                val toolResult = if (toolValidationError != null) {
                    LoggingService.warn(TAG, "AIRI TOOL_REJECTED_INVALID_SCHEMA tool=$toolName")
                    ToolDispatcher.ToolResult.Error(
                        toolValidationError,
                        code = if (selectedToolSchema == null) {
                            com.airi.assistant.agent.loop.tool.ToolErrorCodes.TOOL_NOT_FOUND
                        } else {
                            com.airi.assistant.agent.loop.tool.ToolErrorCodes.INVALID_ARGUMENT
                        },
                    )
                } else if (duplicateToolCall) {
                    LoggingService.warn(TAG, "AIRI TOOL_DUPLICATE_BLOCKED execution=$executionId tool=$toolName step=$toolStepId")
                    ToolDispatcher.ToolResult.Error(
                        "Duplicate tool call blocked for this execution step.",
                        code = com.airi.assistant.agent.loop.tool.ToolErrorCodes.DUPLICATE_CALL,
                    )
                } else if (
                    untrustedContentSeen &&
                    (toolName == "terminal_execute" || toolName == "write_file")
                ) {
                    // Report 3, S8: a write/exec tool must not run in the same loop that has
                    // just ingested web or third-party content; the user has to ask for it directly.
                    LoggingService.warn(TAG, "AIRI TOOL_BLOCKED_AFTER_UNTRUSTED_CONTENT tool=$toolName")
                    ToolDispatcher.ToolResult.Error(
                        "Blocked: $toolName cannot run after external web or connector content was read in this request. " +
                            "Ask the user to confirm and request it in a separate message.",
                        code = com.airi.assistant.agent.loop.tool.ToolErrorCodes.PERMISSION_DENIED,
                    )
                } else when (sideEffectDecision) {
                    AgentLoopSideEffectPolicy.Decision.DURABLE_CONTEXT_REQUIRED -> {
                        LoggingService.warn(TAG, "AIRI TOOL_BLOCKED_NO_DURABLE_CONTEXT tool=$toolName")
                        ToolDispatcher.ToolResult.Error(
                            AgentLoopSideEffectPolicy.blockedMessage(toolName),
                            code = com.airi.assistant.agent.loop.tool.ToolErrorCodes.PERMISSION_DENIED,
                        )
                    }
                    AgentLoopSideEffectPolicy.Decision.ALLOW_TYPED_CALENDAR_CREATE -> {
                        val execution = durableExecutionContext
                        val runtime = calendarCreateRuntime
                        if (execution == null || runtime == null) {
                            ToolDispatcher.ToolResult.Error(
                                AgentLoopSideEffectPolicy.blockedMessage(toolName),
                                code = com.airi.assistant.agent.loop.tool.ToolErrorCodes.PERMISSION_DENIED,
                            )
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
                                    is CalendarCreateRuntime.ProposalResult.ApprovalPending -> {
                                        ExecutionStatusBus.onApprovalWaiting(
                                            executionId = executionId,
                                            actionId = toolStepId,
                                            interruptId = pending.proposal.approvalId.orEmpty(),
                                            actionDescription = "Calendar creation requires explicit approval",
                                        )
                                        ToolDispatcher.ToolResult.Success(
                                            "Calendar proposal is awaiting explicit review and approval in Trust Center. Do not retry the operation."
                                        )
                                    }
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
                    AgentLoopSideEffectPolicy.Decision.ALLOW_TYPED_TERMINAL -> try {
                        runtimeTrace?.dispatched(executionId, sessionId, toolName, "agent_sandbox_terminal")
                        if (agentSandbox != null) {
                            agentSandbox.execute(agentId = SANDBOX_AGENT_ID, goalId = durableExecutionContext?.taskId.orEmpty()) { ctx ->
                                ctx.guardTool(toolName)
                                dispatcher.execute(toolName, toolArgs, appContext, executionId, SANDBOX_AGENT_ID, actionId = toolStepId, traceId = traceId, sessionId = sessionId, messageId = messageId)
                            }
                        } else {
                            dispatcher.execute(toolName, toolArgs, appContext, executionId, SANDBOX_AGENT_ID, actionId = toolStepId, traceId = traceId, sessionId = sessionId, messageId = messageId)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LoggingService.warn(TAG, "Terminal tool failed type=${e.javaClass.simpleName}")
                        ToolDispatcher.ToolResult.Error(
                            "Terminal execution failed: ${e.message}",
                            code = com.airi.assistant.agent.loop.tool.ToolErrorCodes.EXECUTION_FAILED,
                        )
                    }
                    AgentLoopSideEffectPolicy.Decision.ALLOW_TYPED_FILE_WRITE -> try {
                        runtimeTrace?.dispatched(executionId, sessionId, toolName, "agent_sandbox_file_write")
                        if (agentSandbox != null) {
                            agentSandbox.execute(agentId = SANDBOX_AGENT_ID, goalId = durableExecutionContext?.taskId.orEmpty()) { ctx ->
                                ctx.guardTool(toolName)
                                dispatcher.execute(toolName, toolArgs, appContext, executionId, SANDBOX_AGENT_ID, actionId = toolStepId, traceId = traceId, sessionId = sessionId, messageId = messageId)
                            }
                        } else {
                            dispatcher.execute(toolName, toolArgs, appContext, executionId, SANDBOX_AGENT_ID, actionId = toolStepId, traceId = traceId, sessionId = sessionId, messageId = messageId)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LoggingService.warn(TAG, "File write tool failed type=${e.javaClass.simpleName}")
                        ToolDispatcher.ToolResult.Error(
                            "File write failed: ${e.message}",
                            code = com.airi.assistant.agent.loop.tool.ToolErrorCodes.EXECUTION_FAILED,
                        )
                    }
                    AgentLoopSideEffectPolicy.Decision.ALLOW_READ -> try {
                        runtimeTrace?.dispatched(executionId, sessionId, toolName, "agent_sandbox_or_tool_dispatcher")
                        runtimeTrace?.capabilityStage(
                            executionId, sessionId, toolName,
                            com.airi.assistant.core.CapabilityRuntimeStage.EXECUTED,
                            "dispatch_started",
                        )
                        // Route read-only tools through AgentSandbox when available so
                        // permission checks and workspace logging remain applied.
                        if (agentSandbox != null) {
                            agentSandbox.execute(agentId = SANDBOX_AGENT_ID) { ctx ->
                                ctx.guardTool(toolName)
                                dispatcher.execute(toolName, toolArgs, appContext, executionId, actionId = toolStepId, traceId = traceId, sessionId = sessionId, messageId = messageId)
                            }
                        } else {
                            dispatcher.execute(toolName, toolArgs, appContext, executionId, actionId = toolStepId, traceId = traceId, sessionId = sessionId, messageId = messageId)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: com.airi.assistant.security.AgentSandbox.SandboxViolationException) {
                        LoggingService.warn(TAG, "AIRI SANDBOX_VIOLATION tool=$toolName type=${e.javaClass.simpleName}")
                        ToolDispatcher.ToolResult.Error(
                            "Permission denied for tool: $toolName",
                            code = com.airi.assistant.agent.loop.tool.ToolErrorCodes.PERMISSION_DENIED,
                            provenance = com.airi.assistant.agent.loop.tool.ToolProvenance.SYSTEM,
                        )
                    } catch (e: Exception) {
                        LoggingService.warn(TAG, "Tool $toolName failed type=${e.javaClass.simpleName}")
                        ToolDispatcher.ToolResult.Error(
                            "Tool failed: ${e.message}",
                            code = com.airi.assistant.agent.loop.tool.ToolErrorCodes.EXECUTION_FAILED,
                        )
                    }
                }

                val resultText = when (toolResult) {
                    is ToolDispatcher.ToolResult.Success -> toolResult.output
                    is ToolDispatcher.ToolResult.Error ->
                        "Error [${toolResult.code}${if (toolResult.retryable) ", retryable" else ""}]: ${toolResult.message}"
                }

                (toolResult as? ToolDispatcher.ToolResult.Error)?.approval?.let { approval ->
                    ExecutionStatusBus.onApprovalWaiting(
                        executionId = executionId,
                        actionId = toolStepId,
                        interruptId = approval.approvalId,
                        actionDescription = toolResult.message,
                    )
                }

                val toolDurationMs = System.currentTimeMillis() - toolStartedAtMs
                runtimeTrace?.executionCompleted(
                    executionId = executionId,
                    sessionId = sessionId,
                    toolName = toolName,
                    success = toolResult is ToolDispatcher.ToolResult.Success,
                    durationMs = toolDurationMs,
                    resultLength = resultText.length,
                    errorCategory = (toolResult as? ToolDispatcher.ToolResult.Error)?.code,
                )
                runtimeTrace?.capabilityStage(
                    executionId, sessionId, toolName,
                    com.airi.assistant.core.CapabilityRuntimeStage.EXECUTED,
                    if (toolResult is ToolDispatcher.ToolResult.Success) "success" else "failure",
                )
                val traceProvenance = when (toolResult) {
                    is ToolDispatcher.ToolResult.Success -> toolResult.provenance
                    is ToolDispatcher.ToolResult.Error -> toolResult.provenance
                }
                val safeTraceDetail = when (toolResult) {
                    is ToolDispatcher.ToolResult.Success ->
                        "Source: ${traceProvenance.name.lowercase()}; result length: ${resultText.length} chars."
                    is ToolDispatcher.ToolResult.Error ->
                        "Source: ${traceProvenance.name.lowercase()}; operation returned an error."
                }
                when (toolResult) {
                    is ToolDispatcher.ToolResult.Success -> ExecutionStatusBus.onToolCompleted(
                        executionId = executionId,
                        actionId = toolStepId,
                        toolName = toolName,
                        durationMs = toolDurationMs,
                        detail = safeTraceDetail,
                    )
                    is ToolDispatcher.ToolResult.Error -> ExecutionStatusBus.onToolFailed(
                        executionId = executionId,
                        actionId = toolStepId,
                        toolName = toolName,
                        durationMs = toolDurationMs,
                        detail = safeTraceDetail,
                        errorCode = toolResult.code,
                    )
                }

                if (toolResult is ToolDispatcher.ToolResult.Success) {
                    toolLedger.markCompleted(toolFingerprint)
                } else {
                    toolLedger.release(toolFingerprint)
                }
                activeToolTrace = null
                val resultCode = (toolResult as? ToolDispatcher.ToolResult.Error)?.code ?: "ok"
                val resultProvenance = when (toolResult) {
                    is ToolDispatcher.ToolResult.Success -> toolResult.provenance.name.lowercase()
                    is ToolDispatcher.ToolResult.Error -> toolResult.provenance.name.lowercase()
                }
                LoggingService.info(TAG, "AIRI TOOL_RESULT tool=$toolName success=${toolResult is ToolDispatcher.ToolResult.Success} code=$resultCode provenance=$resultProvenance len=${resultText.length}")

                ExecutionStatusBus.onNodeCompleted(
                    stepId,
                    stepsUsed,
                    executionId = executionId,
                )
                // P0-2: onStepComplete may return a non-null String to replace the tool result
                // (used when the agent calls ask_confirmation and ChatViewModel suspends for user input)
                val effectiveResult = onStepComplete(StepEvent.ToolExecuted(toolName, toolArgs, resultText, stepsUsed))
                    ?: resultText

                // Append the assistant's tool_call + tool result to history so the LLM
                // sees what it asked for and what it got back.
                val nativeToolCallId = nativeToolCallId(rawResponse)
                history.add(ConversationTurn.Assistant(rawResponse, nativeToolCallId))
                if (toolResult is ToolDispatcher.ToolResult.Success &&
                    AccessibilityPolicyGuard.isExternalContentTool(toolName)
                ) {
                    untrustedContentSeen = true
                }
                history.add(
                    ConversationTurn.ToolResult(
                        toolName,
                        untrustedAwareToolText(toolName, effectiveResult, toolResult is ToolDispatcher.ToolResult.Success),
                        nativeToolCallId,
                    )
                )
                runtimeTrace?.resultReturned(executionId, sessionId, toolName)
                runtimeTrace?.capabilityStage(
                    executionId, sessionId, toolName,
                    com.airi.assistant.core.CapabilityRuntimeStage.RESULT_RETURNED,
                    "returned_to_model_context",
                )
                progressMs.set(System.currentTimeMillis())
                runtimeTrace?.continuation(executionId, sessionId, continued = stepsUsed < MAX_STEPS, terminalState = "TOOL_RESULT_RETURNED")
                runtimeTrace?.capabilityStage(
                    executionId, sessionId, toolName,
                    com.airi.assistant.core.CapabilityRuntimeStage.MODEL_CONTINUED,
                    if (stepsUsed < MAX_STEPS) "continued" else "terminal",
                )
                val recoveryDecision = if (toolResult is ToolDispatcher.ToolResult.Error) {
                    SelfHealingExecutor.decision(
                        errorCode = toolResult.code,
                        sideEffectPolicy = selectedToolSchema?.sideEffectPolicy
                            ?: ToolSchema.SideEffectPolicy.REQUIRES_DURABLE_CONTEXT,
                        hasIdempotencyKey = toolArgs["idempotency_key"].orEmpty().isNotBlank(),
                    )
                } else SelfHealingExecutor.Decision.STOP_AND_EXPLAIN
                if (toolResult is ToolDispatcher.ToolResult.Error &&
                    toolResult.retryable &&
                    recoveryDecision == SelfHealingExecutor.Decision.RETRY_PARAMETER_REPAIR &&
                    stepsUsed < MAX_STEPS
                ) {
                    val healing = SelfHealingExecutor.recoverFromToolError(
                        failedToolName = toolName,
                        errorMessage = toolResult.message.take(300),
                        originalInput = input,
                    )
                    history.add(
                        ConversationTurn.User(
                            "[RECOVERY] ${healing.correctedPromptOrInput.trim()} " +
                                "Do not repeat a side effect unless the user has confirmed it."
                        )
                    )
                    LoggingService.info(TAG, "AIRI SELF_HEALING_QUEUED tool=$toolName step=$stepsUsed")
                }
            }

            // Exhausted step budget — ask LLM to summarise what it has
            LoggingService.warn(TAG, "AgentLoop exhausted $MAX_STEPS steps — asking LLM to summarise")
            history.add(ConversationTurn.User("You have reached your step limit. Summarise what you have done and what the final answer is."))
            val summary = callLLM(
                prompt = "",
                systemPrompt = systemPrompt +
                    if (platformBlock.isBlank() || systemPrompt.contains("AIRI PLATFORM CAPABILITY SNAPSHOT")) "" else "\n\n$platformBlock" +
                    "\n\nYou have no tools available for this final response. Synthesize the completed results and provide a complete answer at the natural length required by the user's request; do not compress useful context.",
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
                progress = progressMs,
                stallMs = timeoutMs,
            )
            if (summary.isBlank()) {
                ExecutionStatusBus.onGraphCompleted(false, executionId = executionId)
                return@withTimeout LoopResult("", stepsUsed, toolsInvoked, terminalState = TerminalState.NO_RESPONSE, completionMetadata = completionMetadata)
            }
            ExecutionStatusBus.onGraphCompleted(true, executionId = executionId)
            return@withTimeout LoopResult(summary, stepsUsed, toolsInvoked, terminalState = TerminalState.SUCCESS, completionMetadata = completionMetadata)
            }

        } catch (e: StallTimeoutException) {
            activeToolTrace?.let { active ->
                ExecutionStatusBus.onToolCancelled(
                    executionId = active.executionId,
                    actionId = active.actionId,
                    toolName = active.toolName,
                    durationMs = System.currentTimeMillis() - active.startedAtMs,
                    detail = "Agent execution stalled.",
                )
            }
            runtimeTrace?.terminalOutcome(executionId, sessionId, "TIMEOUT", e.message)
            ExecutionStatusBus.onGraphTimedOut(executionId, e.message ?: "Agent execution stalled.")
            LoggingService.warn(TAG, "AgentLoop stalled: no progress for ${timeoutMs}ms")
            return LoopResult(
                finalAnswer = "",
                stepsUsed = stepsUsed,
                toolsInvoked = toolsInvoked,
                terminalState = TerminalState.TIMEOUT,
                failureMessage = "Agent execution timed out.",
                completionMetadata = completionMetadata,
            )
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
            runtimeTrace?.terminalOutcome(executionId, sessionId, "TIMEOUT", e.message)
            ExecutionStatusBus.onGraphTimedOut(executionId, e.message ?: "Agent execution timed out.")
            LoggingService.warn(TAG, "AgentLoop reached its ${timeoutMs}ms execution deadline")
            return LoopResult(
                finalAnswer = "",
                stepsUsed = stepsUsed,
                toolsInvoked = toolsInvoked,
                terminalState = TerminalState.TIMEOUT,
                failureMessage = "Agent execution timed out.",
                completionMetadata = completionMetadata,
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
            runtimeTrace?.terminalOutcome(executionId, sessionId, "CANCELLED", e.message)
            ExecutionStatusBus.onGraphCancelled(executionId)
            return LoopResult("", stepsUsed, toolsInvoked, terminalState = TerminalState.CANCELLED, completionMetadata = completionMetadata)
        } catch (e: Exception) {
            runtimeTrace?.terminalOutcome(executionId, sessionId, "FAILURE", e.message)
            ExecutionStatusBus.onGraphCompleted(false, executionId = executionId)
            LoggingService.error(TAG, "AgentLoop failed type=${e.javaClass.simpleName}")
            return LoopResult(
                finalAnswer = "",
                stepsUsed = stepsUsed,
                toolsInvoked = toolsInvoked,
                terminalState = TerminalState.FAILURE,
                failureMessage = e.message,
                completionMetadata = completionMetadata,
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
        onCompletionMetadata: (CompletionMetadata) -> Unit = {},
        /** Shared progress clock; when set, a stalled model call is cancelled (see TIMEOUT_MS). */
        progress: AtomicLong? = null,
        stallMs: Long = 0L,
    ): String {
        val requestTurns = history.map { turn ->
            when (turn) {
                is ConversationTurn.User -> ExecutionRequest.ConversationTurn("user", turn.content)
                is ConversationTurn.Assistant -> ExecutionRequest.ConversationTurn(
                    role = "assistant",
                    content = turn.content,
                    toolCallId = turn.toolCallId,
                )
                is ConversationTurn.ToolResult -> ExecutionRequest.ConversationTurn(
                    role = "tool",
                    content = turn.result.take(4000),
                    kind = "tool_result",
                    toolName = turn.toolName,
                    toolCallId = turn.toolCallId,
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

        // Hide a tool_call block from the live stream. The gate does constant work per token and
        // hands held-back text back if the finished reply turns out not to be a tool call.
        val streamGate = com.airi.assistant.agent.loop.tool.ToolCallStreamGate()

        val sawToken = AtomicBoolean(false)
        val runStream: suspend () -> Unit = {
        runtimeTrace?.modelRequestBuilt(
            executionId = identity.executionId,
            sessionId = identity.sessionId,
            modelId = modelId,
            providerId = providerId,
            promptTokens = estimatedTokens,
            historyTurns = requestProjection.conversationHistory.size,
            toolCount = tools.size,
            requiresToolCalling = tools.isNotEmpty(),
            requiresLongContext = estimatedTokens > longContextThreshold,
        )
        orchestrator.executeStream(
            request    = ExecutionRequest(
                prompt                = requestProjection.prompt,
                localPrompt           = localPrompt,
                systemPrompt          = systemPrompt,
                // QueryType controls routing and tool policy, not a universal
                // brevity limit. Leave analytical and creative answers room to
                // finish instead of truncating them at the old fixed ceiling.
                maxTokens             = adaptiveOutputTokens(
                    input = currentPromptText?.takeIf { it.isNotBlank() } ?: prompt,
                    queryType = queryType,
                    estimatedPromptTokens = estimatedTokens,
                    providerId = providerId,
                ),
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
                toolDefinitions       = tools.map { schema ->
                    ExecutionRequest.ToolDefinition(
                        name = schema.name,
                        description = schema.description,
                        parameters = schema.parameters.map { (name, param) ->
                            param.toExecutionParameter(name)
                        },
                    )
                },

                conversationHistory   = requestProjection.conversationHistory,
                requiresToolCalling   = tools.isNotEmpty(),
            ),
            context    = appContext,
            onToken    = { tok ->
                sawToken.set(true)
                progress?.set(System.currentTimeMillis())
                buf.append(tok)
                val visible = streamGate.accept(tok)
                if (visible.isNotEmpty()) onToken(visible)
            },
            onComplete = { text, _, _, metadata ->
                onCompletionMetadata(metadata)
                if (text.isNotBlank()) { buf.clear(); buf.append(text) }
            },
            onError    = { msg, _ -> error = msg }
        )
        }
        if (progress != null && stallMs > 0L) {
            progress.set(System.currentTimeMillis())
            withStallGuard(stallMs, progress, sawToken) { runStream() }
        } else {
            runStream()
        }

        if (error != null) throw RuntimeException(error)
        val finalText = buf.toString().trim()
        // A literal marker in prose/code must not leave the stream frozen: show what was held back.
        val released = streamGate.releaseIfNotToolCall(finalText)
        if (released.isNotEmpty()) onToken(released)
        return finalText
    }

    /**
     * Runs [block] and cancels it when [progress] has not moved for too long.
     * Before the first token the allowance is doubled (on-device prefill);
     * afterwards it is [stallMs]. Works for local and cloud alike because it
     * cancels the coroutine instead of relying on the transport to notice.
     */
    private suspend fun <T> withStallGuard(
        stallMs: Long,
        progress: AtomicLong,
        sawToken: AtomicBoolean,
        block: suspend () -> T,
    ): T = coroutineScope {
        val work = async { block() }
        val watchdog = launch {
            val pollMs = (stallMs / 4).coerceIn(10L, 1_000L)
            while (true) {
                delay(pollMs)
                val allowed = if (sawToken.get()) stallMs else stallMs * 2
                if (System.currentTimeMillis() - progress.get() >= allowed) {
                    work.cancel(StallTimeoutException(allowed))
                    return@launch
                }
            }
        }
        try {
            work.await()
        } finally {
            watchdog.cancel()
        }
    }

    /**
     * Output length is a quality decision, not a routing shortcut. Cloud models
     * may use a larger budget for genuinely detailed work; local llama.cpp is
     * capped by the remaining live context so a long answer cannot evict the
     * prompt or exhaust the phone's context window.
     */
    private fun adaptiveOutputTokens(
        input: String,
        queryType: QueryType,
        estimatedPromptTokens: Int,
        providerId: String,
    ): Int = OutputBudgetPolicy.effectiveTokens(
        input = input,
        queryType = queryType,
        estimatedPromptTokens = estimatedPromptTokens,
        providerId = providerId,
        budget = runCatching { contextBudgetProvider() }.getOrDefault(ContextBudget.UNLOADED),
    )

    // ── Tool schema → system prompt block ─────────────────────────────────────

    /**
     * Format tool schemas into a system-prompt block, budget-trimmed.
     *
     * SPRINT 2 / Phase B: The char cap is now computed by
     * [ContributorBudgetPolicy.toolCharsCap] — the hardcoded 25% fraction
     * and 512-char floor live exclusively in the policy object, not here.
     */
    // ── Conversation model ─────────────────────────────────────────────────────

    private data class ActiveToolTrace(
        val executionId: String,
        val actionId: String,
        val toolName: String,
        val startedAtMs: Long,
    )

    /**
     * Report 3, S8: successful output of tools that return third-party content is
     * wrapped in an untrusted-data boundary before it enters the model context.
     */
    private fun untrustedAwareToolText(toolName: String, text: String, success: Boolean): String =
        if (success && AccessibilityPolicyGuard.isExternalContentTool(toolName)) {
            AccessibilityPolicyGuard.wrapUntrustedToolOutput(toolName, text)
        } else {
            text
        }

    private fun nativeToolCallId(rawResponse: String): String = runCatching {
        org.json.JSONObject(rawResponse.trim())
            .optJSONObject("tool_call")
            ?.optString("id", "")
            .orEmpty()
    }.getOrDefault("")

    sealed class ConversationTurn {
        data class User(val content: String) : ConversationTurn()
        data class Assistant(val content: String, val toolCallId: String = "") : ConversationTurn()
        data class ToolResult(val toolName: String, val result: String, val toolCallId: String = "") : ConversationTurn()
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
