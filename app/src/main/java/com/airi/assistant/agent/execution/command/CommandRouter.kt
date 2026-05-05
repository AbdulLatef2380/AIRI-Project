package com.airi.assistant.agent.execution.command

import android.util.Log
import com.airi.assistant.agent.planning.PlanStep
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.agent.subagent.SubAgentRegistry
import com.airi.assistant.connector.ConnectorActionBridge
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.prefs.ExecModePreferences

object CommandRouter {

    private const val TAG = "CommandRouter"

    suspend fun execute(step: PlanStep): CommandResult {
        Log.d(TAG, "Executing step: ${step.id} (${step::class.simpleName})")

        return when (step) {
            is PlanStep.OpenApp -> {
                if (step.appName.isEmpty()) {
                    CommandResult(false, "Missing app name")
                } else {
                    AccessibilityCommandBridge.launchApp(step.appName)
                }
            }

            is PlanStep.Search -> {
                if (step.query.isEmpty()) {
                    CommandResult(false, "Missing search query")
                } else {
                    AccessibilityCommandBridge.search(step.query)
                }
            }

            is PlanStep.Click -> {
                if (step.targetText.isEmpty()) {
                    CommandResult(false, "Missing click target")
                } else {
                    AccessibilityCommandBridge.click(step.targetText)
                }
            }

            is PlanStep.Type -> {
                if (step.text.isEmpty()) {
                    CommandResult(false, "Missing text to type")
                } else {
                    AccessibilityCommandBridge.typeText(step.text)
                }
            }

            is PlanStep.Navigate -> {
                when (step.direction) {
                    PlanStep.Navigate.NavigationDirection.BACK    -> AccessibilityCommandBridge.performBack()
                    PlanStep.Navigate.NavigationDirection.HOME    -> AccessibilityCommandBridge.performHome()
                    PlanStep.Navigate.NavigationDirection.RECENTS -> AccessibilityCommandBridge.performRecents()
                }
            }

            is PlanStep.Wait -> {
                val durationMs = step.durationMs ?: 1000L
                kotlinx.coroutines.delay(durationMs)
                CommandResult(true, "Waited ${durationMs}ms")
            }

            is PlanStep.Scroll -> {
                when (step.direction) {
                    PlanStep.Scroll.ScrollDirection.UP    -> AccessibilityCommandBridge.scrollUp()
                    PlanStep.Scroll.ScrollDirection.DOWN  -> AccessibilityCommandBridge.scrollDown()
                    PlanStep.Scroll.ScrollDirection.LEFT  -> AccessibilityCommandBridge.scrollLeft()
                    PlanStep.Scroll.ScrollDirection.RIGHT -> AccessibilityCommandBridge.scrollRight()
                }
            }

            is PlanStep.Custom -> executeCustom(step)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Custom action execution — three-tier dispatch
    //
    //   Tier 1: Accessibility action name aliases — map common action strings
    //           ("open", "search", "click", "type", "scroll", "navigate") to
    //           the real AccessibilityCommandBridge calls without requiring
    //           SubAgentRegistry overhead for simple UI gestures.
    //
    //   Tier 2: SubAgentRegistry routing — compose the action + parameters into
    //           an input string and route through the same keyword-score pipeline
    //           used by the main chat path. Covers tool agents (calendar, alarms,
    //           research, code, etc.) transparently.
    //
    //   Tier 3: Acknowledged pass-through — if no agent matches, log and return
    //           success so that graph execution continues past non-critical nodes
    //           rather than failing the entire plan on an unroutable generic step.
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun executeCustom(step: PlanStep.Custom): CommandResult {
        Log.i(TAG, "Custom action dispatch: '${step.action}' params=${step.parameters}")
        val actionLower = step.action.lowercase().trim()

        // ── Tier 1: accessibility alias mapping ────────────────────────────────
        val aliasResult: CommandResult? = when {
            actionLower.startsWith("open") || actionLower.startsWith("launch") -> {
                val app = step.parameters["app"]
                    ?: step.parameters["target"]
                    ?: step.parameters["appName"]
                    ?: actionLower.removePrefix("launch_").removePrefix("launch ").removePrefix("open_").removePrefix("open ").trim()
                if (app.isNotBlank()) AccessibilityCommandBridge.launchApp(app) else null
            }
            actionLower.startsWith("search") || actionLower.startsWith("find") -> {
                val query = step.parameters["query"]
                    ?: step.parameters["q"]
                    ?: step.parameters["term"]
                    ?: actionLower.removePrefix("search_").removePrefix("search ").removePrefix("find ").trim()
                if (query.isNotBlank()) AccessibilityCommandBridge.search(query) else null
            }
            actionLower.startsWith("click") || actionLower.startsWith("tap") -> {
                val target = step.parameters["target"]
                    ?: step.parameters["text"]
                    ?: step.parameters["label"]
                    ?: actionLower.removePrefix("click_").removePrefix("click ").removePrefix("tap_").removePrefix("tap ").trim()
                if (target.isNotBlank()) AccessibilityCommandBridge.click(target) else null
            }
            actionLower.startsWith("type") || actionLower.startsWith("input") || actionLower.startsWith("enter") -> {
                val text = step.parameters["text"]
                    ?: step.parameters["value"]
                    ?: step.parameters["input"]
                    ?: actionLower.removePrefix("type_").removePrefix("type ").removePrefix("input ").removePrefix("enter ").trim()
                if (text.isNotBlank()) AccessibilityCommandBridge.typeText(text) else null
            }
            actionLower == "back" || actionLower == "navigate_back" || actionLower == "go_back" ->
                AccessibilityCommandBridge.performBack()
            actionLower == "home" || actionLower == "navigate_home" || actionLower == "go_home" ->
                AccessibilityCommandBridge.performHome()
            actionLower == "recents" || actionLower == "navigate_recents" ->
                AccessibilityCommandBridge.performRecents()
            actionLower == "scroll_down" || actionLower == "swipe_down" ->
                AccessibilityCommandBridge.scrollDown()
            actionLower == "scroll_up" || actionLower == "swipe_up" ->
                AccessibilityCommandBridge.scrollUp()
            actionLower == "scroll_left" || actionLower == "swipe_left" ->
                AccessibilityCommandBridge.scrollLeft()
            actionLower == "scroll_right" || actionLower == "swipe_right" ->
                AccessibilityCommandBridge.scrollRight()

            // Phase 2: synthesize / respond — LLM-generated meta-actions that carry
            // their output text in a parameter. Return the text directly so the
            // TypedPlanGraph can propagate it as the step's CommandResult.message
            // rather than silently acknowledging the step with no useful output.
            actionLower == "synthesize" ||
            actionLower == "respond" ||
            actionLower == "converse" ||
            actionLower == "conversation" ||
            actionLower.startsWith("synthesize_") ||
            actionLower.startsWith("respond_") -> {
                val text = step.parameters["text"]
                    ?: step.parameters["content"]
                    ?: step.parameters["message"]
                    ?: step.parameters["response"]
                    ?: step.parameters.values.firstOrNull()
                    ?: ""
                if (text.isNotBlank()) CommandResult(true, text)
                else CommandResult(true, "custom:${step.action}:no-text")
            }

            else -> null
        }
        if (aliasResult != null) {
            Log.i(TAG, "Custom:tier1 alias='${step.action}' success=${aliasResult.success}")
            return aliasResult
        }

        // ── Tier 1.5: Connector dispatch ───────────────────────────────────────
        // Maps well-known connector action names (read_file, exec, http_get,
        // git_status, logcat_read, set_clipboard, battery_status, …) directly
        // to the registered [ConnectorRegistry] without going through the
        // SubAgentRegistry keyword scorer. This is the bridge that closes the
        // gap between the 13 wired connectors and the agent execution path.
        if (ConnectorActionBridge.handles(step.action)) {
            val connectorOutput = ConnectorActionBridge.dispatch(
                action = step.action,
                params = step.parameters,
                text   = step.parameters["text"]
                    ?: step.parameters["content"]
                    ?: step.parameters["command"]
                    ?: step.parameters["body"]
                    ?: "",
            )
            if (connectorOutput != null) {
                Log.i(TAG, "Custom:tier1.5 connector='${step.action}' success=${connectorOutput is ConnectorOutput.Success}")
                return when (connectorOutput) {
                    is ConnectorOutput.Success   ->
                        CommandResult(true, connectorOutput.text.ifBlank { "connector:${step.action}:ok" })
                    is ConnectorOutput.Failure   -> {
                        Log.w(TAG, "Custom:tier1.5 failure code=${connectorOutput.code}: ${connectorOutput.message.take(80)}")
                        CommandResult(false, connectorOutput.message)
                    }
                    is ConnectorOutput.Streaming ->
                        CommandResult(true, "connector:${step.action}:streaming")
                }
            }
            // connector was recognised but registry was unavailable — fall through
        }

        // ── Tier 2: SubAgentRegistry routing ──────────────────────────────────
        val routingInput = buildString {
            append(step.action.replace('_', ' '))
            if (step.parameters.isNotEmpty()) {
                append(" ")
                append(step.parameters.entries.joinToString(" ") { (k, v) -> "$k:$v" })
            }
        }
        val minimalContext = SubAgentContext(
            sessionId          = step.id,
            userId             = "command_router",
            worldState         = emptyMap(),
            grantedPermissions = SubAgentRegistry.activeCapabilities(),
            nestingDepth       = 1,
            dependencyResults  = step.parameters,
            // Security gate: honour the user's execution-mode preference so that
            // LOCAL_ONLY mode cannot be bypassed by graph steps routed here.
            privacyLevel       = resolvePrivacyLevel()
        )
        val agent = SubAgentRegistry.route(routingInput, minimalContext)
        if (agent != null) {
            Log.i(TAG, "Custom:tier2 routing='${step.action}' → agent=${agent.capability.agentId}")
            var resultText = ""
            var failed     = false
            var failReason = ""
            runCatching {
                agent.execute(routingInput, minimalContext).collect { event ->
                    when (event) {
                        is AgentEvent.Complete      -> resultText = event.result
                        is AgentEvent.PartialResult -> resultText += event.text
                        is AgentEvent.Failed        -> { failed = true; failReason = event.reason }
                        is AgentEvent.Delegate      -> {
                            // Bug-2 fix: previously `else -> Unit` swallowed Delegate events
                            // emitted by CodingAgent, ResearchAgent, CloudBrowserAgent, and
                            // ReActPlanner when those agents were routed here through Tier 2.
                            // The stub Complete result ("custom:action:done") was the only
                            // output propagated, making all LLM synthesis disappear silently.
                            // We now resolve the llmDelegate wired by ChatViewModel and use
                            // its result as the CommandResult text for this graph step.
                            if (event.targetAgentId == "llm_backend") {
                                val llmResult = runCatching {
                                    ServiceLocator.productionOrchestrator.llmDelegate
                                        ?.invoke(event.subInput) ?: ""
                                }.getOrElse { "" }
                                if (llmResult.isNotBlank()) resultText = llmResult
                            }
                        }
                        else -> Unit
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "Custom:tier2 agent threw: ${e.message}")
                failed = true; failReason = e.message ?: "agent exception"
            }
            return if (failed) CommandResult(false, failReason)
            else CommandResult(true, resultText.ifBlank { "custom:${step.action}:done" })
        }

        // ── Tier 3: explicit failure for unroutable steps ──────────────────────
        //
        // Phase 2: Changed from silent success to explicit UNKNOWN_ACTION failure.
        // The TypedPlanGraph RecoveryBranch decides whether to abort the whole plan
        // (critical steps) or continue (non-critical steps) based on this signal.
        // Returning success here was masking real routing failures and making the
        // execution log misleading — a step that does nothing should not report success.
        Log.w(TAG, "Custom:tier3 UNKNOWN_ACTION='${step.action}' params=${step.parameters.keys} — no alias, no agent match")
        return CommandResult(false, "UNKNOWN_ACTION:${step.action}")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Reads the user's active [ExecutionMode] from [ExecModePreferences] and
     * maps it to the appropriate [SubAgentContext] privacy level.
     *
     * This prevents LOCAL_ONLY mode from being bypassed when graph steps are
     * routed through CommandRouter → SubAgentRegistry → cloud agents.
     *
     * Falls back to [SubAgentContext.PRIVACY_STANDARD] when context is
     * unavailable (e.g. during unit tests with no Application).
     */
    private fun resolvePrivacyLevel(): Int = runCatching {
        val ctx = ServiceLocator.context ?: return@runCatching SubAgentContext.PRIVACY_STANDARD
        val mode = ExecModePreferences(ctx).effectiveMode
        if (mode == ExecutionMode.LOCAL_ONLY) SubAgentContext.PRIVACY_MAXIMUM
        else SubAgentContext.PRIVACY_STANDARD
    }.getOrDefault(SubAgentContext.PRIVACY_STANDARD)
}
