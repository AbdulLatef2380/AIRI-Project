package com.airi.assistant.agent.subagent.impl

import android.util.Log
import com.airi.assistant.accessibility.execution.AccessibilityExecutionEngine
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * AndroidAgent — Android OS interaction via Accessibility Service.
 *
 * REAL EXECUTION: delegates directly to [AccessibilityExecutionEngine], which
 * runs the full OBSERVE → PLAN → EXECUTE → VERIFY → RECOVER loop.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * SAFETY CONTRACT
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   1. Routing is BLOCKED unless context.grantedPermissions contains
 *      [CAPABILITY_ACCESSIBILITY] — the token granted by AiriAccessibilityService
 *      when it binds. If the service is not enabled, canHandle() returns false.
 *
 *   2. Every action is logged to Logcat tag AIRI_PROOF_ACCESSIBILITY.
 *
 *   3. The engine enforces: maxActions=20, maxRetries=3, 8s timeout/action.
 *
 *   4. Kill switch is always available via AccessibilityExecutionEngine.killSwitch().
 *
 *   5. Destructive actions (send message, post to social) require
 *      [requiresConfirmation] — surfaced as a Progress event to the UI
 *      for a confirmation gate.
 */
class AndroidAgent(
    internal val engine: AccessibilityExecutionEngine
) : SubAgent {

    companion object {
        const val CAPABILITY_ACCESSIBILITY = "airi_accessibility_enabled"
        private const val TAG = "AndroidAgent"
    }

    /**
     * Phase 1: Real confirmation gate for destructive actions.
     *
     * Set by [ServiceLocator.initSubAgentSystem] after ChatViewModel creates the
     * ViewModel. When non-null, destructive actions (send, post, delete, share)
     * SUSPEND until the user responds. Returns true = approved, false = cancelled.
     *
     * When null (background/scheduled contexts without an active UI), destructive
     * actions are BLOCKED rather than auto-approved — fail safe.
     */
    @Volatile
    var confirmationGate: (suspend (actionName: String, description: String) -> Boolean)? = null

    override val capability = SubAgentCapability(
        agentId      = "android_agent",
        displayName  = "Android Agent",
        description  = "Navigate apps, change settings, and automate Android UI tasks using accessibility.",
        intentKeywords = listOf(
            "open", "launch", "navigate to", "go to", "switch to",
            "turn on", "turn off", "enable", "disable", "set brightness",
            "volume", "wifi", "bluetooth", "airplane mode", "do not disturb",
            "take screenshot", "scroll down", "click", "tap", "type in",
            "send message in", "post to", "share to", "open app"
        ),
        domains            = listOf("android", "automation", "accessibility", "device control"),
        requiredPermissions = listOf(CAPABILITY_ACCESSIBILITY),
        accessesPrivateData = true,
        requiresCloud       = false,
        costTier            = SubAgentCapability.CostTier.FREE,
        latencyProfile      = SubAgentCapability.LatencyProfile.FAST,
        supportsBackground  = true,
        maxParallelSubTasks = 1,
        supportsResume      = false
    )

    override suspend fun canHandle(input: String, context: SubAgentContext): Boolean {
        if (!context.grantedPermissions.contains(CAPABILITY_ACCESSIBILITY)) {
            Log.d(TAG, "AndroidAgent blocked — accessibility service not enabled")
            return false
        }
        val lower = input.lowercase()
        return AUTOMATION_SIGNALS.any { lower.contains(it) }
    }

    override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
        val start = System.currentTimeMillis()
        Log.i(TAG, "AndroidAgent.execute input='${input.take(80)}'")

        // Guard: re-check capability at execution time (token may have been revoked)
        if (!context.grantedPermissions.contains(CAPABILITY_ACCESSIBILITY)) {
            emit(AgentEvent.Failed(
                reason      = "Accessibility service is not enabled. Please enable AIRI in Accessibility Settings.",
                recoverable = false
            ))
            return@flow
        }

        val actionType = detectAction(input.lowercase())
        Log.i(TAG, "AIRI_AUDIT ANDROID_AGENT action=${actionType.name} " +
                "needsConfirm=${actionType.requiresConfirmation} input='${input.take(80)}'")

        // ── Phase 1: Real blocking confirmation gate ───────────────────────────
        // Previously: emitted Progress("⚠ Confirmation required… Proceeding…") and
        // immediately continued — no actual user gate.
        // Now: suspends until the UI resolves the confirmation dialog.
        // If no gate is wired (background context), BLOCK the action — fail-safe.
        if (actionType.requiresConfirmation) {
            val gate = confirmationGate
            val approved = if (gate != null) {
                gate(actionType.displayName, "This will ${actionType.displayName.lowercase()} on your device.")
            } else {
                Log.w(TAG, "AIRI_AUDIT BLOCKED destructive action '${actionType.name}' — " +
                    "no confirmation gate wired (background context). Blocking.")
                false
            }
            if (!approved) {
                emit(AgentEvent.Failed(
                    reason     = "Action '${actionType.displayName}' cancelled by user.",
                    recoverable = false
                ))
                return@flow
            }
            Log.i(TAG, "AIRI_AUDIT CONFIRMED action='${actionType.name}' by user gate")
        }

        emit(AgentEvent.ToolCall(
            toolName  = "accessibility_engine",
            params    = mapOf("action" to actionType.name, "input" to input),
            reasoning = "Executing Android automation: ${actionType.displayName}"
        ))
        emit(AgentEvent.Progress("Executing: ${actionType.displayName}…", 30, "execute"))

        // Reset engine kill switch if it was previously fired
        if (!engine.isRunning.value) engine.reset()

        var finalSuccess = false
        var finalSummary = ""
        var errorReason: String? = null

        // Collect the real execution flow from AccessibilityExecutionEngine
        engine.executeTask(input).collect { event ->
            when (event) {
                is AccessibilityExecutionEngine.ExecutionEvent.PhaseChanged -> {
                    val pct = when (event.phase) {
                        AccessibilityExecutionEngine.ExecutionPhase.OBSERVE  -> 35
                        AccessibilityExecutionEngine.ExecutionPhase.PLAN     -> 45
                        AccessibilityExecutionEngine.ExecutionPhase.EXECUTE  -> 60
                        AccessibilityExecutionEngine.ExecutionPhase.VERIFY   -> 75
                        AccessibilityExecutionEngine.ExecutionPhase.RECOVER  -> 65
                    }
                    emit(AgentEvent.Progress("[${event.phase.name}] ${event.details}", pct, event.phase.name.lowercase()))
                }
                is AccessibilityExecutionEngine.ExecutionEvent.ScreenObserved -> {
                    emit(AgentEvent.Progress(
                        "Screen: ${event.context.packageName} (${event.context.nodeCount} nodes)",
                        38, "screen_observed"
                    ))
                }
                is AccessibilityExecutionEngine.ExecutionEvent.PlanReady -> {
                    emit(AgentEvent.Progress(
                        "Plan ready: ${event.actions.size} action(s)",
                        48, "plan_ready"
                    ))
                }
                is AccessibilityExecutionEngine.ExecutionEvent.ActionExecuted -> {
                    emit(AgentEvent.Progress(
                        "${if (event.success) "✓" else "✗"} ${event.action}: ${event.result}",
                        65, "action"
                    ))
                }
                is AccessibilityExecutionEngine.ExecutionEvent.StepVerified -> {
                    emit(AgentEvent.Progress(
                        "Verify: ${if (event.passed) "passed" else "failed"} — ${event.details}",
                        78, "verify"
                    ))
                }
                is AccessibilityExecutionEngine.ExecutionEvent.RecoveryAttempt -> {
                    emit(AgentEvent.Progress(
                        "Recovery attempt ${event.attempt}: ${event.description}",
                        65, "recover"
                    ))
                }
                is AccessibilityExecutionEngine.ExecutionEvent.Complete -> {
                    finalSuccess = event.success
                    finalSummary = event.summary
                    if (!event.success) errorReason = event.summary
                }
                is AccessibilityExecutionEngine.ExecutionEvent.Cancelled -> {
                    errorReason = "Task cancelled: ${event.reason}"
                }
            }
        }

        val durationMs = System.currentTimeMillis() - start

        if (errorReason != null) {
            emit(AgentEvent.Failed(reason = errorReason!!, recoverable = true))
        } else {
            emit(AgentEvent.PartialResult(finalSummary, isFinal = true))
            emit(AgentEvent.Complete(
                result     = finalSummary,
                durationMs = durationMs,
                toolsUsed  = listOf("accessibility_engine")
            ))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Action classification (for audit log and confirmation gate)
    // ─────────────────────────────────────────────────────────────────────────

    private data class AndroidAction(
        val name:                 String,
        val displayName:          String,
        val requiresConfirmation: Boolean = false
    )

    private fun detectAction(lower: String): AndroidAction = when {
        lower.contains("send") || lower.contains("post") || lower.contains("share") ->
            AndroidAction("SEND_OR_POST", "Send/Post", requiresConfirmation = true)
        lower.contains("delete") || lower.contains("remove") ->
            AndroidAction("DELETE", "Delete", requiresConfirmation = true)
        lower.contains("open") || lower.contains("launch") ->
            AndroidAction("OPEN_APP", "Open App")
        lower.contains("navigate") || lower.contains("go to") ->
            AndroidAction("NAVIGATE", "Navigate")
        lower.contains("turn on") || lower.contains("enable") ->
            AndroidAction("ENABLE_SETTING", "Enable Setting")
        lower.contains("turn off") || lower.contains("disable") ->
            AndroidAction("DISABLE_SETTING", "Disable Setting")
        lower.contains("brightness") ->
            AndroidAction("SET_BRIGHTNESS", "Adjust Brightness")
        lower.contains("volume") ->
            AndroidAction("SET_VOLUME", "Adjust Volume")
        lower.contains("screenshot") ->
            AndroidAction("TAKE_SCREENSHOT", "Take Screenshot")
        lower.contains("type") || lower.contains("input") ->
            AndroidAction("TYPE_TEXT", "Type Text")
        lower.contains("scroll") ->
            AndroidAction("SCROLL", "Scroll")
        lower.contains("click") || lower.contains("tap") ->
            AndroidAction("CLICK", "Click")
        else ->
            AndroidAction("GENERIC_AUTOMATION", "Automation Task")
    }

    private val AUTOMATION_SIGNALS = listOf(
        "open app", "launch", "navigate to", "go to settings",
        "turn on", "turn off", "enable", "disable",
        "set brightness", "change volume", "airplane mode",
        "take screenshot", "scroll", "click on", "tap on", "type in",
        "send via", "share to", "post to", "open the"
    )
}
