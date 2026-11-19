package com.airi.assistant.agent.loop.tool

import android.content.Context
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.agent.execution.command.AccessibilityCommandBridge
import com.airi.assistant.agent.execution.node.NodeScanner
import com.airi.assistant.accessibility.service.ScreenContextHolder
import com.airi.assistant.accessibility.security.AccessibilityActionGateway
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.tools.execution.AlarmTool
import com.airi.assistant.tools.execution.CalendarTool
import com.airi.assistant.tools.execution.NotesTool
import com.airi.assistant.tools.execution.SearchTool
import com.airi.assistant.torch.TorchController
import com.airi.assistant.torch.TorchResult
import com.airi.assistant.ai.skills.SkillManifest
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.SkillConfig
import com.airi.assistant.domain.customskill.SkillProposalStore
import com.airi.assistant.domain.customskill.ProposalSource
import com.airi.assistant.domain.customskill.SkillType
import com.airi.assistant.marketplace.GitHubSkillImporter
import com.airi.assistant.execution.privacy.PrivacyGuard
import com.airi.assistant.core.ExecutionTraceGateway
import com.airi.assistant.agent.loop.AgentLoopTaskRuntime
import com.airi.assistant.agent.loop.AgentLoopExecutionContext
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.domain.tool.TypedArgumentContract
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import android.net.Uri
import com.airi.assistant.attachments.GeneratedArtifactService
import com.airi.assistant.attachments.MediaArtifactService
import com.airi.assistant.workspace.ArtifactManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * ToolDispatcher — maps AgentLoop tool_call names to real implementations.
 *
 * Every tool here has:
 *   - a real execution body (no placeholders)
 *   - a logged AIRI entry for auditing
 *   - a cooperative per-tool deadline; caller cancellation remains cancellation
 *
 * Adding a new tool: add it to [BuiltinTools], add dispatch case here.
 *
 * Tools whose names start with "skill_" are automatically forwarded to the
 * [SkillToolBridge] which routes them to the matching registered [AiriSkill].
 */
class ToolDispatcher(
    private val memoryManager:      MemoryManager? = null,
    // P1-1: session context for semantic memory search
    private val sessionIdProvider:  (() -> String)? = null,
    // Brave Search API key — injected from SecureApiKeyStore at construction time
    private val braveApiKeyProvider: (() -> String?)? = null,
    // Optional skill tool bridge — handles all "skill_*" tool names
    private val skillToolBridge: com.airi.assistant.ai.skills.SkillToolBridge? = null,
    // Canonical live connector bridge — handles dynamically exposed connector_* tools
    private val connectorToolBridge: com.airi.assistant.connector.ConnectorToolBridge? = null,
    private val artifactManager: ArtifactManager? = null,
) {
    companion object {
        private const val TAG = "AIRI_ToolDispatcher"
        private const val TOOL_TIMEOUT_MS = 120_000L
    }

    sealed class ToolResult {
        /** Structured, redacted execution evidence for trace and verification layers. */
        data class Evidence(
            val kind: Kind,
            val exitCode: Int? = null,
            val changedFiles: List<String> = emptyList(),
            val artifactId: String? = null,
            val durationMs: Long? = null,
            val provenance: ToolProvenance = ToolProvenance.BUILTIN,
            val untrusted: Boolean = false,
            /** True only when the handler checked the effect, not merely produced text. */
            val semanticVerified: Boolean = false,
            /** Bounded, redacted identity of the state/artifact checked by the handler. */
            val semanticReference: String? = null,
        ) {
            enum class Kind { GENERIC, TERMINAL, FILE_DIFF, BUILD, TEST, SKILL, CONNECTOR }
        }

        data class Success(
            val output: String,
            val provenance: ToolProvenance = ToolProvenance.BUILTIN,
            val evidence: Evidence = Evidence(kind = Evidence.Kind.GENERIC, provenance = provenance),
        ) : ToolResult()

        data class Error(
            val message: String,
            val code: String = ToolErrorCodes.EXECUTION_FAILED,
            val retryable: Boolean = false,
            val provenance: ToolProvenance = ToolProvenance.BUILTIN,
            val approval: ToolApprovalContext? = null,
            val evidence: Evidence = Evidence(kind = Evidence.Kind.GENERIC, provenance = provenance),
        ) : ToolResult()
    }

    suspend fun execute(
        toolName: String,
        args: Map<String, String>,
        context: Context,
        executionId: String? = null,
        agentId: String = "terminal",
        actionId: String? = null,
        traceId: String? = null,
        sessionId: String? = null,
        messageId: String? = null,
        durableExecutionContext: AgentLoopExecutionContext? = null,
        userApproved: Boolean = false,
    ): ToolResult = try {
        withTimeout(TOOL_TIMEOUT_MS) {
            executeWithinDeadline(
                toolName = toolName,
                args = args,
                context = context,
                executionId = executionId,
                agentId = agentId,
                actionId = actionId,
                traceId = traceId,
                sessionId = sessionId,
                messageId = messageId,
                durableExecutionContext = durableExecutionContext,
                userApproved = userApproved,
            )
        }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        ToolResult.Error(
            message = "Tool exceeded its ${TOOL_TIMEOUT_MS / 1_000}s execution deadline.",
            code = ToolErrorCodes.TIMEOUT,
            retryable = true,
        )
    }

    private suspend fun executeWithinDeadline(
        toolName: String,
        args:     Map<String, String>,
        context:  Context,
        /** Explicit owner supplied by the runtime; never inferred from UI state. */
        executionId: String? = null,
        agentId: String = "terminal",
        /** Stable identity shared by the status trace and compatibility activity feed. */
        actionId: String? = null,
        traceId: String? = null,
        sessionId: String? = null,
        messageId: String? = null,
        durableExecutionContext: AgentLoopExecutionContext? = null,
        userApproved: Boolean = false,
    ): ToolResult {
        val schema = BuiltinTools.ALL.firstOrNull { it.name == toolName }
            ?: skillToolBridge?.asToolSchemas()?.firstOrNull { it.name == toolName }
            ?: connectorToolBridge?.asToolSchemas(includeUnavailable = true)?.firstOrNull { it.name == toolName }
        schema?.let {
            val validation = TypedArgumentContract.validate(TypedArgumentAdapters.fromSchema(it), args)
            if (!validation.isValid) {
                val failure = validation.failure!!
                return ToolResult.Error(
                    message = "${failure.message} ${failure.repairHint}",
                    code = ToolErrorCodes.INVALID_ARGUMENT,
                    retryable = false,
                )
            }
            if ((toolName == "write_file" || toolName in AgentLoopTaskRuntime.ARTIFACT_TOOLS) &&
                (durableExecutionContext == null || !userApproved)
            ) {
                return ToolResult.Error(
                    "This file or artifact operation requires a task-owned context and explicit approval.",
                    code = ToolErrorCodes.PERMISSION_DENIED,
                )
            }
            if (it.actionClass == ToolSchema.ActionClass.HIGH_IMPACT &&
                toolName != "calendar_create" && !userApproved) {
                return ToolResult.Error(
                    "This high-impact action requires explicit approval before execution.",
                    code = ToolErrorCodes.APPROVAL_REQUIRED,
                )
            }
        }
        LoggingService.info(TAG, "TOOL_DISPATCH tool=$toolName argCount=${args.size}")
        ExecutionTraceGateway.emitExecution(
            executionId = executionId,
            actionId = actionId,
            phase = "dispatched",
            message = PrivacyGuard.redactForTrace("Tool dispatched: $toolName"),
            category = ActivityCategory.TOOL,
            machineTag = "tool_dispatch_compat:${when (toolName) {
                    "read_screen", "web_search", "fetch_url" -> "page_viewed"
                    "terminal_execute" -> "command_executed"
                    else -> "tool_executed"
                }}",
        )

        return when (toolName) {

            // ── Screen observation ─────────────────────────────────────────────
            "read_screen" -> {
                val service = ScreenContextHolder.serviceInstance
                if (service == null) {
                    ToolResult.Error(
                        "Accessibility service not connected. Enable AIRI in Accessibility settings.",
                        code = ToolErrorCodes.NOT_CONNECTED,
                    )
                } else {
                    val activePackage = try { service.rootInActiveWindow?.packageName?.toString().orEmpty() }
                    catch (_: Exception) { "" }
                    when (val gate = AccessibilityActionGateway.authorize(activePackage, "read_screen")) {
                        is AccessibilityActionGateway.Decision.Denied -> return ToolResult.Error(gate.reason, code = ToolErrorCodes.UNSUPPORTED)
                        is AccessibilityActionGateway.Decision.NeedsConfirmation -> return ToolResult.Error(gate.reason, code = ToolErrorCodes.UNSUPPORTED)
                        AccessibilityActionGateway.Decision.Allowed -> Unit
                    }
                    // P4: explicit, per-read approval (fail-closed without a UI to ask on).
                    if (!com.airi.assistant.accessibility.security.ScreenReadConsent.request(activePackage)) {
                        return ToolResult.Error(
                            "Reading the screen needs your approval and it was not given.",
                            code = ToolErrorCodes.UNSUPPORTED,
                        )
                    }
                    val root = service.rootInActiveWindow
                    if (root == null) {
                        ToolResult.Error("No active window available", code = ToolErrorCodes.UNSUPPORTED)
                    } else {
                        val nodes = NodeScanner.collectAllNodes(root)
                        val texts = NodeScanner.readableTexts(nodes)
                            .distinct()
                            .take(40)
                        val pkg   = service.rootInActiveWindow?.packageName?.toString() ?: "unknown"
                        val summary = "App: $pkg\nVisible text:\n${texts.joinToString("\n").take(800)}"
                        LoggingService.info(TAG, "AIRI READ_SCREEN pkg=$pkg nodes=${nodes.size} textItems=${texts.size}")
                        ToolResult.Success(summary)
                    }
                }
            }

            // ── App launch ─────────────────────────────────────────────────────
            "open_app" -> {
                val appName = args["app_name"] ?: return ToolResult.Error("Missing app_name", code = ToolErrorCodes.INVALID_ARGUMENT)
                val result = AccessibilityCommandBridge.launchApp(appName, userApproved)
                if (result.success) ToolResult.Success("Launched $appName", evidence = ToolResult.Evidence(ToolResult.Evidence.Kind.GENERIC, semanticVerified = true, semanticReference = appName.take(128)))
                else ToolResult.Error(result.message ?: "", code = ToolErrorCodes.EXECUTION_FAILED)
            }

            // ── UI interaction ─────────────────────────────────────────────────
            "tap" -> {
                val target = args["target"] ?: return ToolResult.Error("Missing target", code = ToolErrorCodes.INVALID_ARGUMENT)
                val result = AccessibilityCommandBridge.click(target, userApproved)
                if (result.success) ToolResult.Success("Tapped: $target", evidence = ToolResult.Evidence(ToolResult.Evidence.Kind.GENERIC, semanticVerified = true, semanticReference = target.take(128)))
                else ToolResult.Error(result.message ?: "", code = ToolErrorCodes.EXECUTION_FAILED)
            }

            "type_text" -> {
                val text = args["text"] ?: return ToolResult.Error("Missing text", code = ToolErrorCodes.INVALID_ARGUMENT)
                val result = AccessibilityCommandBridge.typeText(text, userApproved)
                if (result.success) ToolResult.Success("Typed: ${text.take(60)}", evidence = ToolResult.Evidence(ToolResult.Evidence.Kind.GENERIC, semanticVerified = true, semanticReference = "focused_input"))
                else ToolResult.Error(result.message ?: "", code = ToolErrorCodes.EXECUTION_FAILED)
            }

            "scroll_down" -> {
                val result = AccessibilityCommandBridge.scrollDown(userApproved)
                if (result.success) ToolResult.Success("Scrolled down", evidence = ToolResult.Evidence(ToolResult.Evidence.Kind.GENERIC, semanticVerified = true, semanticReference = "scroll_down")) else ToolResult.Error(result.message ?: "")
            }

            "go_back" -> {
                val result = AccessibilityCommandBridge.performBack(userApproved)
                if (result.success) ToolResult.Success("Pressed back", evidence = ToolResult.Evidence(ToolResult.Evidence.Kind.GENERIC, semanticVerified = true, semanticReference = "back_navigation")) else ToolResult.Error(result.message ?: "")
            }

            // ── Search ─────────────────────────────────────────────────────────
            "web_search" -> {
                val query = args["query"] ?: return ToolResult.Error("Missing query", code = ToolErrorCodes.INVALID_ARGUMENT)
                val searchTool = SearchTool(context, braveApiKey = braveApiKeyProvider?.invoke())

                // Try Brave Search first (real web results + Jina content extraction)
                val braveKey = braveApiKeyProvider?.invoke()
                if (!braveKey.isNullOrBlank()) {
                    val brave = searchTool.searchBrave(query, count = 5, enrich = true)
                    if (brave.success) {
                        LoggingService.info(TAG, "WEB_SEARCH_COMPLETE provider=brave queryChars=${query.length} results=${brave.results.size} hasContent=${brave.topContent != null}")
                        return ToolResult.Success(brave.toAgentString())
                    }
                    LoggingService.warn(TAG, "WEB_SEARCH_FALLBACK from=brave to=duckduckgo")
                }

                // Fallback: DDG Instant Answers (~30% coverage but always free)
                val ddg = searchTool.searchDuckDuckGo(query)
                if (ddg.success) {
                    LoggingService.info(TAG, "WEB_SEARCH_COMPLETE provider=duckduckgo queryChars=${query.length} resultChars=${ddg.summary.length}")
                    return ToolResult.Success(ddg.summary)
                }

                // Last resort: open browser (no content returned, but user can see it)
                LoggingService.warn(TAG, "WEB_SEARCH_FALLBACK from=duckduckgo to=browser queryChars=${query.length}")
                searchTool.searchViaIntent(query)
                ToolResult.Error(
                    "Search opened in browser for: $query. No result was returned to AIRI. " +
                        "Configure Brave Search in Settings to enable in-agent results.",
                    code = ToolErrorCodes.NETWORK_UNAVAILABLE,
                    retryable = false,
                )
            }

            "fetch_url" -> {
                val url = args["url"] ?: return ToolResult.Error("Missing url parameter", code = ToolErrorCodes.INVALID_ARGUMENT)
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return ToolResult.Error("Invalid URL — must start with http:// or https://", code = ToolErrorCodes.INVALID_ARGUMENT)
                }
                val searchTool = SearchTool(context, braveApiKey = braveApiKeyProvider?.invoke())
                LoggingService.info(TAG, "FETCH_URL urlChars=${url.length}")

                // Try Jina Reader first (clean markdown, handles JS-rendered pages)
                val jina = searchTool.fetchViaJina(url, maxChars = 4000)
                if (jina.success && jina.content.isNotBlank()) {
                    LoggingService.info(TAG, "AIRI FETCH_URL_JINA_OK chars=${jina.content.length}")
                    return ToolResult.Success(
                        "Content from ${url}:\n\n${jina.content}"
                    )
                }

                // Fallback: direct HTTP fetch with HTML stripping
                val direct = searchTool.fetchPageContent(url)
                if (direct.success && direct.content.isNotBlank()) {
                    LoggingService.info(TAG, "AIRI FETCH_URL_DIRECT_OK chars=${direct.content.length}")
                    return ToolResult.Success("Content from ${url}:\n\n${direct.content}")
                }

                ToolResult.Error(
                    "Could not fetch content from: $url (tried Jina Reader and direct fetch)",
                    code = ToolErrorCodes.NETWORK_UNAVAILABLE,
                    retryable = true,
                )
            }

            // ── Memory ─────────────────────────────────────────────────────────
            "memory_recall" -> {
                val query = args["query"] ?: return ToolResult.Error("Missing query", code = ToolErrorCodes.INVALID_ARGUMENT)
                val manager = memoryManager
                if (manager == null) {
                    ToolResult.Error(
                        "Memory not available in this session",
                        code = ToolErrorCodes.MEMORY_UNAVAILABLE,
                    )
                } else {
                    // P1-1: Use semantic search when embedding model is ready;
                    // fall back to recent messages when no embedding model is loaded.
                    val sessionId = sessionIdProvider?.invoke().orEmpty()
                    if (manager.isSemanticMemoryReady() && sessionId.isNotEmpty()) {
                        val ranked = manager.semanticSearch(sessionId, query, k = 5)
                        LoggingService.info(TAG, "MEMORY_RECALL mode=semantic queryChars=${query.length} hits=${ranked.size}")
                        if (ranked.isEmpty()) {
                            ToolResult.Success("No memories found for: $query")
                        } else {
                            val formatted = ranked.joinToString("\n") { item ->
                                "• ${item.message.content.take(200)}"
                            }
                            ToolResult.Success("Memory results (semantic):\n$formatted")
                        }
                    } else if (sessionId.isBlank()) {
                        // Never fall back to a database-wide recent query. A
                        // missing session identity is an authorization failure,
                        // not permission to read another conversation.
                        LoggingService.warn(TAG, "MEMORY_RECALL_BLOCKED reason=missing_session_scope")
                        ToolResult.Error(
                            "Memory session is unavailable; refusing an unscoped recall.",
                            code = ToolErrorCodes.MEMORY_SCOPE_UNAVAILABLE,
                        )
                    } else {
                        val recent = manager.getRecentMessages(sessionId, 5)
                        LoggingService.info(TAG, "MEMORY_RECALL mode=recent queryChars=${query.length} hits=${recent.size}")
                        if (recent.isEmpty()) {
                            ToolResult.Success("No memories found for: $query")
                        } else {
                            val formatted = recent.joinToString("\n") { "• ${it.content.take(200)}" }
                            ToolResult.Success("Memory results (recent, no embedding model):\n$formatted")
                        }
                    }
                }
            }

            // ── Calendar ───────────────────────────────────────────────────────
            "calendar_read" -> {
                val days = args["days"]?.toIntOrNull() ?: 7
                val cal  = CalendarTool(context)
                val events = cal.getUpcomingEvents(days)
                if (events.isEmpty()) {
                    ToolResult.Success("No events found in the next $days days.")
                } else {
                    val formatted = cal.summarize(events.take(10))
                    LoggingService.info(TAG, "AIRI CALENDAR_READ days=$days events=${events.size}")
                    ToolResult.Success("Upcoming events:\n$formatted")
                }
            }

            "current_time" -> {
                val now = java.time.ZonedDateTime.now()
                val zone = java.time.ZoneId.systemDefault()
                ToolResult.Success(
                    "Current device time: ${now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} " +
                        "(${zone.id}, UTC${now.offset})"
                )
            }

            "flashlight" -> {
                val enabled = args["enabled"]?.toBooleanStrictOrNull()
                    ?: return ToolResult.Error("enabled must be exactly true or false", code = ToolErrorCodes.INVALID_ARGUMENT)
                when (val result = TorchController(context).setEnabled(enabled)) {
                    is TorchResult.Success -> ToolResult.Success(if (result.enabled) "Torch is on" else "Torch is off", evidence = ToolResult.Evidence(ToolResult.Evidence.Kind.GENERIC, semanticVerified = true, semanticReference = "torch:${result.enabled}"))
                    TorchResult.PermissionRequired -> ToolResult.Error("Camera permission is required to control the torch", code = ToolErrorCodes.PERMISSION_DENIED)
                    TorchResult.NotReady -> ToolResult.Error("A rear camera torch is unavailable or busy", code = ToolErrorCodes.UNSUPPORTED)
                    TorchResult.UnknownOutcome -> ToolResult.Error("Torch state was not confirmed before timeout; check the device before retrying", code = ToolErrorCodes.TIMEOUT)
                    is TorchResult.Failed -> ToolResult.Error("Torch operation failed: ${result.reason}", code = ToolErrorCodes.EXECUTION_FAILED)
                }
            }

            "calendar_create" -> {
                // Calendar creation is intentionally not a generic dispatcher action.
                // AgentLoop must use CalendarCreateRuntime, which owns a private
                // proposal plus task/run/step-bound one-shot approval before one
                // provider insert. This prevents raw arguments and live writes from
                // bypassing durable ownership, review, recovery and evidence.
                ToolResult.Error(
                    "Calendar creation requires a task-owned approval session before it can run.",
                    code = ToolErrorCodes.APPROVAL_REQUIRED,
                )
            }

            // ── Alarm ──────────────────────────────────────────────────────────
            "set_alarm" -> {
                val time  = args["time"]  ?: return ToolResult.Error("Missing time", code = ToolErrorCodes.INVALID_ARGUMENT)
                val label = args["label"] ?: "AIRI Alarm"
                val alarm = AlarmTool(context)
                val timePair = alarm.parseTime(time)
                    ?: return ToolResult.Error("Could not parse time '$time'. Use format like '7:30am' or '14:00'", code = ToolErrorCodes.INVALID_ARGUMENT)
                val result = alarm.setAlarmViaIntent(timePair.first, timePair.second, label)
                if (result.success) {
                    LoggingService.info(TAG, "ALARM_SET timeChars=${time.length} labelChars=${label.length}")
                    ToolResult.Success("Alarm set for $time: $label", evidence = ToolResult.Evidence(ToolResult.Evidence.Kind.GENERIC, semanticVerified = true, semanticReference = "alarm:${time.take(128)}"))
                } else {
                    ToolResult.Error(result.message, code = ToolErrorCodes.EXECUTION_FAILED)
                }
            }

            // ── Notes ──────────────────────────────────────────────────────────
            "create_note" -> {
                val title   = args["title"]   ?: return ToolResult.Error("Missing title", code = ToolErrorCodes.INVALID_ARGUMENT)
                val content = args["content"] ?: return ToolResult.Error("Missing content", code = ToolErrorCodes.INVALID_ARGUMENT)
                val notes   = NotesTool(context)
                val note    = notes.createNote(title, content)
                LoggingService.info(TAG, "NOTE_CREATED titleChars=${title.length} contentChars=${content.length}")
                ToolResult.Success("Note created: ${note.title}", evidence = ToolResult.Evidence(ToolResult.Evidence.Kind.GENERIC, semanticVerified = true, semanticReference = "note:${note.title.take(128)}"))
            }

            // ── Bounded agent terminal ────────────────────────────────────────
            // The gateway owns the task workspace, policy, governance and executor.
            "terminal_execute" -> {
                val command = args["command"]?.trim().orEmpty()
                if (command.isBlank()) return ToolResult.Error("Missing command", code = ToolErrorCodes.INVALID_ARGUMENT)
                val execution = durableExecutionContext
                    ?: return ToolResult.Error(
                        "Terminal execution requires a durable task correlation context.",
                        code = ToolErrorCodes.PERMISSION_DENIED,
                    )
                val terminalExecutionId = executionId?.takeIf { it.isNotBlank() }
                    ?: return ToolResult.Error("Missing execution correlation", code = ToolErrorCodes.PERMISSION_DENIED)
                val terminalActionId = actionId?.takeIf { it.isNotBlank() }
                    ?: return ToolResult.Error("Missing action correlation", code = ToolErrorCodes.PERMISSION_DENIED)
                val terminalTraceId = traceId?.takeIf { it.isNotBlank() }
                    ?: return ToolResult.Error("Missing trace correlation", code = ToolErrorCodes.PERMISSION_DENIED)
                val terminalSessionId = sessionId?.takeIf { it.isNotBlank() }
                    ?: return ToolResult.Error("Missing session correlation", code = ToolErrorCodes.PERMISSION_DENIED)
                if (execution.sourceSessionId != terminalSessionId) {
                    return ToolResult.Error(
                        "Terminal session correlation does not match the durable task owner.",
                        code = ToolErrorCodes.PERMISSION_DENIED,
                    )
                }
                val terminalMessageId = messageId?.takeIf { it.isNotBlank() }
                    ?: return ToolResult.Error("Missing message correlation", code = ToolErrorCodes.PERMISSION_DENIED)
                when (val result = com.airi.assistant.core.ServiceLocator.terminalExecutionGateway
                    .executeForAgent(
                        request = com.airi.assistant.terminal.TerminalExecutionRequest(
                            command = command,
                            context = execution,
                            executionId = terminalExecutionId,
                            actionId = terminalActionId,
                            traceId = terminalTraceId,
                            messageId = terminalMessageId,
                        ),
                        seedFilesFrom = java.io.File(context.filesDir, AgentFileWriter.DIRECTORY_NAME),
                    )) {
                    is com.airi.assistant.terminal.TerminalExecutionGateway.Result.Disabled -> {
                        if (!result.requiresApproval) {
                            ToolResult.Error(result.reason, code = ToolErrorCodes.PERMISSION_DENIED)
                        } else {
                            val approvalId = com.airi.assistant.core.ServiceLocator.permissionGovernanceLayer.requestApproval(
                                action = "terminal_expanded_session",
                                description = "Approve expanded terminal command for this task run: ${com.airi.assistant.security.CommandRedactor.redact(command).take(240)}",
                                riskLevel = com.airi.assistant.security.PermissionGovernanceLayer.RiskLevel.HIGH,
                                sourceSessionId = execution.sourceSessionId,
                                taskId = execution.taskId,
                                runId = execution.runId,
                                stepId = terminalActionId,
                            )
                            ToolResult.Error(
                                result.reason,
                                code = ToolErrorCodes.APPROVAL_REQUIRED,
                                approval = ToolApprovalContext(
                                    approvalId = approvalId,
                                    taskId = execution.taskId,
                                    runId = execution.runId,
                                    stepId = terminalActionId,
                                ),
                            )
                        }
                    }
                    is com.airi.assistant.terminal.TerminalExecutionGateway.Result.Denied ->
                        ToolResult.Error(result.reason, code = ToolErrorCodes.PERMISSION_DENIED)
                    is com.airi.assistant.terminal.TerminalExecutionGateway.Result.Completed -> when (val execution = result.result) {
                        is com.airi.assistant.agent.sandbox.SandboxExecutor.ExecutionResult.Success -> {
                            LoggingService.info(TAG, "TERMINAL_TOOL_COMPLETE commandChars=${command.length} outputChars=${execution.output.length}")
                            ToolResult.Success(
                                execution.output.ifBlank { "Command completed without output." },
                                evidence = ToolResult.Evidence(
                                    kind = ToolResult.Evidence.Kind.TERMINAL,
                                    exitCode = execution.exitCode,
                                    provenance = ToolProvenance.BUILTIN,
                                    semanticVerified = execution.exitCode == 0,
                                    semanticReference = "exit:${execution.exitCode}",
                                ),
                            )
                        }
                        is com.airi.assistant.agent.sandbox.SandboxExecutor.ExecutionResult.Timeout ->
                            ToolResult.Error(
                                "Command timed out",
                                code = ToolErrorCodes.EXECUTION_FAILED,
                                evidence = ToolResult.Evidence(
                                    kind = ToolResult.Evidence.Kind.TERMINAL,
                                    provenance = ToolProvenance.BUILTIN,
                                ),
                            )
                        is com.airi.assistant.agent.sandbox.SandboxExecutor.ExecutionResult.Failure ->
                            ToolResult.Error(
                                execution.error,
                                code = ToolErrorCodes.EXECUTION_FAILED,
                                evidence = ToolResult.Evidence(
                                    kind = ToolResult.Evidence.Kind.TERMINAL,
                                    exitCode = execution.exitCode,
                                    provenance = ToolProvenance.BUILTIN,
                                ),
                            )
                        is com.airi.assistant.agent.sandbox.SandboxExecutor.ExecutionResult.SecurityViolation ->
                            ToolResult.Error(
                                execution.reason,
                                code = ToolErrorCodes.PERMISSION_DENIED,
                                evidence = ToolResult.Evidence(
                                    kind = ToolResult.Evidence.Kind.TERMINAL,
                                    provenance = ToolProvenance.SYSTEM,
                                    untrusted = true,
                                ),
                            )
                        com.airi.assistant.agent.sandbox.SandboxExecutor.ExecutionResult.UnsupportedOnDevice ->
                            ToolResult.Error(
                                "Command is unsupported on this device",
                                code = ToolErrorCodes.UNSUPPORTED,
                                evidence = ToolResult.Evidence(
                                    kind = ToolResult.Evidence.Kind.TERMINAL,
                                    provenance = ToolProvenance.SYSTEM,
                                ),
                            )
                    }
                }
            }

            // ── Bounded file writer ───────────────────────────────────────────
            // AgentLoop admits this only with a task-owned durable context; the
            // governance layer is consulted here and the writer validates every field.
            "write_file" -> {
                val filename = AgentFileWriter.filenameFromArgs(args)
                val content = AgentFileWriter.contentFromArgs(args)
                val overwrite = args["overwrite"]?.toBooleanStrictOrNull() ?: false
                val decision = runCatching {
                    com.airi.assistant.core.ServiceLocator.permissionGovernanceLayer.evaluate(
                        actionType = "file_write",
                        actionDesc = "Write file (${filename.length} chars name, ${content.length} chars)",
                        agentId = agentId,
                        payload = filename,
                    )
                }.getOrElse { return ToolResult.Error("File write governance failed closed", code = ToolErrorCodes.PERMISSION_DENIED) }
                if (!decision.allowed) return ToolResult.Error(decision.reason, code = ToolErrorCodes.PERMISSION_DENIED)
                val directory = java.io.File(context.filesDir, AgentFileWriter.DIRECTORY_NAME)
                // Part 5 §4: the version being replaced, kept for the run screen's Original/Diff tabs.
                val previousContent = if (overwrite) AgentFileHistory.readExisting(directory, filename) else null
                when (val written = AgentFileWriter.write(directory, filename, content, overwrite)) {
                    is AgentFileWriter.Result.Written -> {
                        LoggingService.info(TAG, "FILE_WRITTEN chars=${written.chars}")
                        // Best effort: a failed snapshot never fails or delays the write itself.
                        if (!executionId.isNullOrBlank()) {
                            runCatching {
                                AgentFileHistory.record(
                                    root = java.io.File(context.filesDir, AgentFileHistory.DIRECTORY_NAME),
                                    executionId = executionId,
                                    fileName = written.file.name,
                                    before = previousContent,
                                    after = content,
                                    nowMs = System.currentTimeMillis(),
                                )
                            }
                        }
                        ToolResult.Success(
                            AgentFileWriter.savedMessage(written.file.name, written.chars, written.sha256),
                            evidence = ToolResult.Evidence(
                                kind = ToolResult.Evidence.Kind.FILE_DIFF,
                                changedFiles = listOf(written.file.name),
                                provenance = ToolProvenance.BUILTIN,
                                semanticVerified = written.file.isFile,
                                semanticReference = written.file.name.take(128),
                            ),
                        )
                    }
                    is AgentFileWriter.Result.Rejected ->
                        ToolResult.Error(written.reason, code = ToolErrorCodes.INVALID_ARGUMENT)
                }
            }

            // ── Unified verified artifact generation ───────────────────────────
            "generate_document", "generate_spreadsheet", "generate_pdf", "compress_files", "generate_image", "generate_image_from_prompt", "generate_audio", "generate_video" -> {
                val manager = artifactManager ?: return ToolResult.Error("Artifact workspace is not available", code = ToolErrorCodes.NOT_CONNECTED)
                val ownerSession = sessionId?.takeIf { it.isNotBlank() } ?: args["session_id"]?.takeIf { it.isNotBlank() }
                    ?: return ToolResult.Error("Missing artifact session scope", code = ToolErrorCodes.PERMISSION_DENIED)
                val run = executionId ?: durableExecutionContext?.runId
                val trace = traceId
                val result = try {
                    when (toolName) {
                        "generate_document" -> GeneratedArtifactService(manager).generateDocument(ownerSession, args.getValue("filename"), args.getValue("content"), runId = run, traceId = trace)
                        "generate_spreadsheet" -> GeneratedArtifactService(manager).generateSpreadsheet(ownerSession, args.getValue("filename"), args.getValue("content"), run, trace)
                        "generate_pdf" -> GeneratedArtifactService(manager).generatePdf(ownerSession, args.getValue("filename"), args.getValue("content"), run, trace)
                        "compress_files" -> GeneratedArtifactService(manager).compressFiles(ownerSession, args.getValue("artifact_ids").split(',').map(String::trim).filter(String::isNotBlank), args["filename"] ?: "airi-artifacts.zip", run, trace)
                        "generate_image" -> MediaArtifactService(context, manager).processImage(ownerSession, Uri.parse(args.getValue("uri")), args["filename"] ?: "airi-image.jpg", runId = run, traceId = trace)
                            .also { check(manager.verifyArtifact(it.artifactId)) { "Image artifact was not verified on disk" } }
                        "generate_image_from_prompt" -> MediaArtifactService(context, manager).generateImageFromPrompt(ownerSession, args.getValue("prompt"), outputName = args["filename"] ?: "airi-generated-image.jpg", runId = run, traceId = trace)
                            .also { check(manager.verifyArtifact(it.artifactId)) { "Generated image artifact was not verified on disk" } }
                        "generate_audio" -> MediaArtifactService(context, manager).processAudio(ownerSession, Uri.parse(args.getValue("uri")), args["filename"] ?: "airi-audio.m4a", runId = run, traceId = trace)
                            .also { check(manager.verifyArtifact(it.artifactId)) { "Audio artifact was not verified on disk" } }
                        else -> MediaArtifactService(context, manager).processVideo(ownerSession, Uri.parse(args.getValue("uri")), args["filename"] ?: "airi-video.mp4", run, trace)
                            .also { check(manager.verifyArtifact(it.artifactId)) { "Video artifact was not verified on disk" } }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return ToolResult.Error(error.message ?: "Artifact generation failed", code = ToolErrorCodes.EXECUTION_FAILED)
                }
                val structured = when (result) {
                            is GeneratedArtifactService.GeneratedArtifactLink -> result.structured()
                            is MediaArtifactService.MediaArtifactLink -> result.structured()
                            else -> error("Unsupported artifact result")
                }
                val id = when (result) {
                            is GeneratedArtifactService.GeneratedArtifactLink -> result.artifactId
                            is MediaArtifactService.MediaArtifactLink -> result.artifactId
                            else -> null
                }
                ExecutionTraceGateway.emitExecution(
                            executionId = executionId,
                            actionId = actionId,
                            phase = "artifact_verified",
                            message = PrivacyGuard.redactForTrace("Artifact verified: ${id ?: "unknown"}"),
                            category = ActivityCategory.TOOL,
                            machineTag = "artifact_verified",
                )
                ToolResult.Success(structured, evidence = ToolResult.Evidence(kind = ToolResult.Evidence.Kind.FILE_DIFF, artifactId = id, semanticVerified = !id.isNullOrBlank(), semanticReference = id))
            }

            // ── Skill instructions on demand (report 3, phase 2) ──────────────
            "load_skill" -> {
                val requestedName = args["name"].orEmpty()
                val readiness = com.airi.assistant.agent.loop.skill.AgentSkillCatalog.find(requestedName)?.let { doc ->
                    val registry = SkillRegistry.shared(context)
                    val info = runCatching { registry.getAllSkillInfos().firstOrNull { it.id == doc.name } }.getOrNull()
                    val registered = runCatching {
                        registry.getRegisteredSkillsForRouting().firstOrNull { it.skillId == doc.name }
                    }.getOrNull()
                    when {
                        info?.isEnabled == false -> com.airi.assistant.agent.loop.skill.SkillLoadReadiness(
                            ready = false,
                            blockedReason = "Skill '${doc.name}' is disabled.",
                            nextAction = "enable_skill",
                        )
                        registered == null -> com.airi.assistant.agent.loop.skill.SkillLoadReadiness(
                            ready = false,
                            blockedReason = "skill '${doc.name}' is registered in the catalog but has no runtime executor",
                            nextAction = "enable_skill_runtime",
                        )
                        skillToolBridge == null -> com.airi.assistant.agent.loop.skill.SkillLoadReadiness(
                            ready = false,
                            blockedReason = "SkillToolBridge is not connected to the agent dispatcher",
                            nextAction = "configure_skill_dispatcher",
                        )
                        else -> when (val decision = skillToolBridge.readiness(registered)) {
                            is com.airi.assistant.ai.skills.SkillInvocationAccessPolicy.Decision.Allow ->
                                com.airi.assistant.agent.loop.skill.SkillLoadReadiness(ready = true)
                            is com.airi.assistant.ai.skills.SkillInvocationAccessPolicy.Decision.Deny ->
                                com.airi.assistant.agent.loop.skill.SkillLoadReadiness(
                                    ready = false,
                                    blockedReason = decision.userMessage,
                                    nextAction = when (decision.reason) {
                                        com.airi.assistant.ai.skills.SkillInvocationAccessPolicy.DenyReason.DISABLED -> "enable_skill"
                                        com.airi.assistant.ai.skills.SkillInvocationAccessPolicy.DenyReason.CONNECTOR_UNHEALTHY -> "connect_required_service"
                                        com.airi.assistant.ai.skills.SkillInvocationAccessPolicy.DenyReason.MISSING_PERMISSION -> "grant_required_permission"
                                        com.airi.assistant.ai.skills.SkillInvocationAccessPolicy.DenyReason.APPROVAL_UNAVAILABLE -> "configure_approval_flow"
                                        com.airi.assistant.ai.skills.SkillInvocationAccessPolicy.DenyReason.MEMORY_UNAVAILABLE -> "configure_memory"
                                        com.airi.assistant.ai.skills.SkillInvocationAccessPolicy.DenyReason.MODEL_UNAVAILABLE -> "load_model"
                                        com.airi.assistant.ai.skills.SkillInvocationAccessPolicy.DenyReason.INVALID_MANIFEST -> "inspect_skill_manifest"
                                    },
                                )
                        }
                    }
                }
                val loaded = com.airi.assistant.agent.loop.skill.AgentSkillCatalog.load(requestedName, readiness)
                loaded.fold(
                    onSuccess = { ToolResult.Success(it) },
                    onFailure = { ToolResult.Error(it.message ?: "Unknown skill", code = ToolErrorCodes.INVALID_ARGUMENT) },
                )
            }

            // ── Confirmation request (LLM asks user) ──────────────────────────
            // This tool does NOT execute an action — it signals AgentLoop that
            // the LLM wants to pause for user confirmation before continuing.
            // AgentLoop callers (ChatViewModel) must handle StepEvent.ToolExecuted
            // for "ask_confirmation" and show a dialog before resuming the loop.
            "ask_confirmation" -> {
                val action  = args["action"]  ?: "Proceed?"
                val details = args["details"] ?: ""
                // Return a special marker that ChatViewModel can detect
                ToolResult.Success("CONFIRMATION_REQUIRED|$action|$details")
            }

            // ── Structured GenUI projection ────────────────────────────────────
            // Validation is shared with the airi-ui fence fallback. The tool only
            // returns assistant content; it does not execute callbacks or actions.
            "render_ui" -> when (val rendered = RenderUiTool.render(args["document"].orEmpty(), args["text"])) {
                is RenderUiTool.Result.Accepted -> ToolResult.Success(
                    rendered.content,
                    evidence = ToolResult.Evidence(kind = ToolResult.Evidence.Kind.GENERIC),
                )
                is RenderUiTool.Result.Rejected -> ToolResult.Error(
                    rendered.reason,
                    code = ToolErrorCodes.INVALID_ARGUMENT,
                    retryable = false,
                )
            }

            "create_skill_proposal" -> {
                val name = args["name"].orEmpty().trim()
                val description = args["description"].orEmpty().trim()
                val endpoint = args["endpoint"].orEmpty().trim()
                val method = args["method"].orEmpty().trim().ifBlank { "POST" }.uppercase()
                val body = args["body_template"].orEmpty().ifBlank { "{\"input\": \"{{input}}\"}" }
                val permissions = args["permissions"].orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
                val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(64)
                if (id.length < 3 || name.length !in 3..80 || description.length !in 10..500 || endpoint.isBlank()) {
                    ToolResult.Error("Invalid skill proposal fields", code = ToolErrorCodes.INVALID_ARGUMENT)
                } else {
                    val manifest = SkillManifest(
                        id = id,
                        name = name,
                        description = description,
                        version = "1.0.0",
                        author = "AIRI",
                        permissions = permissions,
                        endpoint = endpoint,
                        requiresConfirmation = true,
                    )
                    val skill = CustomSkill(
                        id = id,
                        name = name,
                        description = description,
                        type = SkillType.API,
                        config = SkillConfig(endpoint = endpoint, method = method, bodyTemplate = body),
                        createdAt = System.currentTimeMillis(),
                    )
                    val registry = SkillRegistry.shared(context)
                    if (!registry.validateDynamicManifest(manifest, endpoint, method, body, SkillType.API)) {
                        ToolResult.Error("Skill proposal failed AIRI validation", code = ToolErrorCodes.INVALID_ARGUMENT)
                    } else {
                        val proposal = SkillProposalStore(context).create(
                            skill = skill,
                            manifest = manifest,
                            source = ProposalSource.AIRI_GENERATED,
                            sourceUrl = args["source_url"],
                        )
                        LoggingService.info(TAG, "AIRI SKILL_PROPOSAL_CREATED id=${proposal.id} source=generated")
                        ToolResult.Success("SKILL_PROPOSAL_REQUIRED|${proposal.id}", provenance = ToolProvenance.SKILL)
                    }
                }
            }

            "import_skill_proposal" -> {
                val url = args["url"].orEmpty().trim()
                if (!url.startsWith("https://")) {
                    ToolResult.Error("Skill import requires an HTTPS URL", code = ToolErrorCodes.INVALID_ARGUMENT)
                } else {
                    val imported = GitHubSkillImporter.importFromUrl(url)
                    val manifest = imported.manifest
                    val skill = imported.skill
                    if (!imported.success || manifest == null || skill == null) {
                        ToolResult.Error(imported.errors.joinToString("; ").ifBlank { "Skill import failed" }, code = ToolErrorCodes.EXECUTION_FAILED)
                    } else if (!SkillRegistry.shared(context).validateDynamicManifest(manifest, skill.config.endpoint, skill.config.method, skill.config.bodyTemplate, skill.type)) {
                        ToolResult.Error("Imported skill failed AIRI validation", code = ToolErrorCodes.INVALID_ARGUMENT)
                    } else {
                        val proposal = SkillProposalStore(context).create(
                            skill = skill,
                            manifest = manifest,
                            source = ProposalSource.AIRI_IMPORTED_FROM_INTERNET,
                            sourceUrl = url,
                            warnings = imported.warnings,
                        )
                        LoggingService.info(TAG, "AIRI SKILL_PROPOSAL_CREATED id=${proposal.id} source=internet")
                        ToolResult.Success("SKILL_PROPOSAL_REQUIRED|${proposal.id}", provenance = ToolProvenance.SKILL)
                    }
                }
            }

            // ── Skill invocations (skill_*) ────────────────────────────────────
            else -> {
                // Route any "skill_*" prefixed tool call through the SkillToolBridge
                val bridge = skillToolBridge
                if (bridge != null && bridge.handles(toolName)) {
                    LoggingService.info(TAG, "AIRI SKILL_TOOL_DISPATCH tool=$toolName")
                    val result = bridge.invoke(toolName, args, executionId = executionId)
                    val output = com.airi.assistant.ai.skills.SkillToolResultFormatter.format(result)
                    if (result.success) {
                        ToolResult.Success(output, provenance = ToolProvenance.SKILL, evidence = ToolResult.Evidence(kind = ToolResult.Evidence.Kind.SKILL, provenance = ToolProvenance.SKILL, semanticVerified = result.success && output.isNotBlank(), semanticReference = toolName.take(128)))
                    } else {
                        val code = ToolErrorCodes.fromSkillFailure(result.metadata, result.error)
                        ToolResult.Error(
                            output,
                            code = code,
                            provenance = ToolProvenance.SKILL,
                        )
                    }
                } else if (connectorToolBridge != null && connectorToolBridge.handles(toolName)) {
                    LoggingService.info(TAG, "AIRI CONNECTOR_TOOL_DISPATCH tool=$toolName path=ConnectorRuntimeManager")
                    when (val result = connectorToolBridge.invoke(
                        toolName,
                        args,
                        executionId = executionId,
                        traceId = traceId,
                        sessionId = sessionId,
                        messageId = messageId,
                        actionId = actionId,
                    )) {
                        is com.airi.assistant.connector.ConnectorOutput.Success ->
                            ToolResult.Success(result.text, provenance = ToolProvenance.CONNECTOR, evidence = ToolResult.Evidence(kind = ToolResult.Evidence.Kind.CONNECTOR, provenance = ToolProvenance.CONNECTOR, semanticVerified = result.text.isNotBlank(), semanticReference = toolName.take(128)))
                        is com.airi.assistant.connector.ConnectorOutput.Streaming -> ToolResult.Error(
                            "Streaming connector output is not supported by text ToolDispatcher yet.",
                            code = ToolErrorCodes.UNSUPPORTED,
                            provenance = ToolProvenance.CONNECTOR,
                        )
                        is com.airi.assistant.connector.ConnectorOutput.ApprovalRequired -> ToolResult.Error(
                            result.message,
                            code = ToolErrorCodes.APPROVAL_REQUIRED,
                            provenance = ToolProvenance.CONNECTOR,
                            approval = ToolApprovalContext(
                                approvalId = result.approvalId,
                                taskId = result.taskId,
                                runId = result.runId,
                                stepId = result.stepId,
                                expiresAtMs = result.expiresAtMs,
                            ),
                        )
                        is com.airi.assistant.connector.ConnectorOutput.Failure -> ToolResult.Error(
                            result.message,
                            code = ToolErrorCodes.fromConnector(result.code),
                            retryable = result.retryable,
                            provenance = ToolProvenance.CONNECTOR,
                        )
                    }
                } else {
                    LoggingService.warn(TAG, "Unknown tool: $toolName")
                    ToolResult.Error(
                        "Unknown tool: $toolName. Available tools: ${BuiltinTools.ALL.map { it.name }.joinToString()}",
                        code = ToolErrorCodes.TOOL_NOT_FOUND,
                    )
                }
            }
        }
    }

    // ── DateTime parsing ───────────────────────────────────────────────────────

    private fun parseDateTime(input: String): Long? {
        // Try ISO-8601 first
        runCatching {
            return java.time.Instant.parse(input).toEpochMilli()
        }
        runCatching {
            val dt = LocalDateTime.parse(input, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            return dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        // Natural language: "3pm tomorrow", "9am", "tomorrow at 10"
        val lower = input.lowercase().trim()
        val now   = java.util.Calendar.getInstance()
        runCatching {
            val cal = now.clone() as java.util.Calendar
            when {
                lower.contains("tomorrow") -> cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                lower.contains("next week") -> cal.add(java.util.Calendar.WEEK_OF_YEAR, 1)
            }
            // Extract HH:mm or h am/pm
            val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
            val match = timeRegex.find(lower) ?: return@runCatching
            var hour = match.groupValues[1].toInt()
            val min  = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3]
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
            cal.set(java.util.Calendar.MINUTE, min)
            cal.set(java.util.Calendar.SECOND, 0)
            return cal.timeInMillis
        }
        return null
    }

}
