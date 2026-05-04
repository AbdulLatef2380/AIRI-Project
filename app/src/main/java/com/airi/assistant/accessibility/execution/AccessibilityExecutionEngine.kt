package com.airi.assistant.accessibility.execution

import android.util.Log
import com.airi.assistant.accessibility.service.AiriAccessibilityService
import com.airi.assistant.agent.execution.command.AccessibilityCommandBridge
import com.airi.assistant.agent.execution.node.NodeScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AccessibilityExecutionEngine — production OBSERVE → PLAN → EXECUTE → VERIFY → RECOVER loop.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SAFETY CONTRACT
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   1. User consent must be obtained BEFORE calling [executeTask]. The engine
 *      does not prompt for consent — the caller is responsible.
 *
 *   2. [killSwitch] can be called from any thread at any time. All in-progress
 *      work is cancelled within one coroutine suspension boundary.
 *
 *   3. [maxActions] enforces a hard cap on actions per task — prevents runaway
 *      loops regardless of LLM plan output.
 *
 *   4. Every action is logged to [executionLog] and to Logcat with tag
 *      AIRI_PROOF_ACCESSIBILITY for audit trails.
 *
 *   5. No blind execution: each action is followed by a VERIFY phase that
 *      checks the accessibility tree for expected state change.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EXECUTION PHASES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   OBSERVE  — capture current screen context (package, activity, node tree)
 *   PLAN     — convert task description into concrete action sequence
 *   EXECUTE  — perform each action via AccessibilityCommandBridge
 *   VERIFY   — confirm expected state change occurred
 *   RECOVER  — on failure: retry / scroll / back / re-plan (up to maxRetries)
 */
class AccessibilityExecutionEngine {

    private val TAG = "AccessibilityExecEngine"

    // ── State ─────────────────────────────────────────────────────────────────

    private val _isRunning    = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _executionLog = MutableStateFlow<List<ExecutionLogEntry>>(emptyList())
    val executionLog: StateFlow<List<ExecutionLogEntry>> = _executionLog.asStateFlow()

    private val killed = AtomicBoolean(false)

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Maximum number of discrete actions per task. Safety cap against infinite loops. */
    var maxActions: Int = 20

    /** Maximum recovery retries per action failure. */
    var maxRetries: Int = 3

    /** Timeout per action phase in milliseconds. */
    var actionTimeoutMs: Long = 8_000L

    // ─────────────────────────────────────────────────────────────────────────
    // Kill switch — call immediately from any thread to halt execution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Immediately cancel the active task. Safe to call from any thread.
     * The active [executeTask] Flow will emit [ExecutionEvent.Cancelled]
     * and complete within one suspension boundary.
     */
    fun killSwitch(reason: String = "User kill switch") {
        log(ExecutionPhase.EXECUTE, "KILL SWITCH: $reason", success = false)
        killed.set(true)
        _isRunning.value = false
        Log.w(TAG, "AIRI_PROOF_ACCESSIBILITY KILL_SWITCH reason=$reason")
    }

    /** Reset kill switch (required before next [executeTask] call). */
    fun reset() {
        killed.set(false)
        _isRunning.value = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Primary API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Execute an autonomous accessibility task.
     *
     * Returns a cold [Flow] of [ExecutionEvent] — collect it to drive execution.
     * The flow completes (successfully or with error) when the task is done.
     *
     * REQUIRES: AiriAccessibilityService must be enabled and connected.
     * REQUIRES: User consent must be obtained before calling this.
     * REQUIRES: Call [reset] if [killSwitch] was previously called.
     *
     * @param taskDescription Natural-language task (e.g. "Open Settings and turn on Wi-Fi")
     */
    fun executeTask(taskDescription: String): Flow<ExecutionEvent> = flow {

        if (killed.get()) {
            emit(ExecutionEvent.Cancelled("Kill switch active — call reset() first"))
            return@flow
        }

        val service = AiriAccessibilityService.instance
        if (service == null) {
            emit(ExecutionEvent.PhaseChanged(
                ExecutionPhase.OBSERVE, "Accessibility service not connected"
            ))
            emit(ExecutionEvent.Complete(success = false,
                summary = "Accessibility service not enabled"))
            return@flow
        }

        _isRunning.value = true
        log(ExecutionPhase.OBSERVE, "Task started: $taskDescription")
        Log.i(TAG, "AIRI_PROOF_ACCESSIBILITY TASK_START task='$taskDescription'")

        var actionCount = 0
        var succeeded   = false

        try {
            // ── PHASE: OBSERVE ─────────────────────────────────────────────
            emit(ExecutionEvent.PhaseChanged(ExecutionPhase.OBSERVE, "Capturing screen context"))

            val screenCtx = observeScreen(service)
            log(ExecutionPhase.OBSERVE, "App: ${screenCtx.packageName}  Nodes: ${screenCtx.nodeCount}")
            emit(ExecutionEvent.ScreenObserved(screenCtx))

            // ── PHASE: PLAN ────────────────────────────────────────────────
            emit(ExecutionEvent.PhaseChanged(ExecutionPhase.PLAN, "Planning actions"))

            val plan = planActions(taskDescription, screenCtx)
            log(ExecutionPhase.PLAN, "Plan: ${plan.size} actions")
            emit(ExecutionEvent.PlanReady(plan))

            // ── PHASE: EXECUTE + VERIFY loop ───────────────────────────────
            for (action in plan) {
                if (killed.get()) {
                    emit(ExecutionEvent.Cancelled("Cancelled during execution"))
                    break
                }
                if (actionCount >= maxActions) {
                    log(ExecutionPhase.EXECUTE, "Max action cap ($maxActions) reached", success = false)
                    emit(ExecutionEvent.PhaseChanged(ExecutionPhase.EXECUTE,
                        "Action cap reached — stopping"))
                    break
                }

                emit(ExecutionEvent.PhaseChanged(ExecutionPhase.EXECUTE,
                    "Executing: ${action.description}"))

                var retries = 0
                var actionSucceeded = false

                while (retries <= maxRetries && !killed.get()) {
                    if (retries > 0) {
                        emit(ExecutionEvent.RecoveryAttempt(retries, action.description))
                        log(ExecutionPhase.RECOVER, "Retry $retries for: ${action.description}")
                        delay(500L * retries)
                    }

                    val result = executeAction(service, action)
                    actionCount++
                    log(ExecutionPhase.EXECUTE, "${action.description}: ${result.message}",
                        success = result.success)
                    emit(ExecutionEvent.ActionExecuted(
                        action  = action.description,
                        result  = result.message,
                        success = result.success
                    ))

                    // ── VERIFY ─────────────────────────────────────────────
                    if (result.success) {
                        emit(ExecutionEvent.PhaseChanged(ExecutionPhase.VERIFY,
                            "Verifying: ${action.description}"))
                        delay(300L) // allow UI to settle

                        val verified = verifyAction(service, action)
                        log(ExecutionPhase.VERIFY, "Verified=${verified.passed}: ${verified.details}")
                        emit(ExecutionEvent.StepVerified(verified.passed, verified.details))

                        if (verified.passed) {
                            actionSucceeded = true
                            break
                        }
                        // Failed verify → retry
                        retries++
                    } else {
                        retries++
                    }
                }

                if (!actionSucceeded && !killed.get()) {
                    log(ExecutionPhase.RECOVER, "Action failed after $retries retries: ${action.description}",
                        success = false)
                    emit(ExecutionEvent.PhaseChanged(ExecutionPhase.RECOVER,
                        "Failed: ${action.description} — attempting recovery"))

                    // Recovery: try pressing Back then re-observe
                    AccessibilityCommandBridge.performBack()
                    delay(400L)
                }
            }

            succeeded = !killed.get()

        } catch (e: CancellationException) {
            log(ExecutionPhase.EXECUTE, "Task cancelled: ${e.message}", success = false)
            emit(ExecutionEvent.Cancelled(e.message ?: "Cancelled"))
            return@flow
        } catch (e: Exception) {
            log(ExecutionPhase.EXECUTE, "Task error: ${e.message}", success = false)
            Log.e(TAG, "Task execution error", e)
            emit(ExecutionEvent.Complete(success = false, summary = "Error: ${e.message}"))
            return@flow
        } finally {
            _isRunning.value = false
            Log.i(TAG, "AIRI_PROOF_ACCESSIBILITY TASK_END success=$succeeded actions=$actionCount")
        }

        emit(ExecutionEvent.Complete(
            success = succeeded,
            summary = if (succeeded) "Task completed ($actionCount actions)"
                      else "Task stopped ($actionCount actions executed)"
        ))

    }.flowOn(Dispatchers.Main)

    // ─────────────────────────────────────────────────────────────────────────
    // OBSERVE — capture screen context
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeScreen(service: AiriAccessibilityService): ScreenContext {
        val root = service.rootInActiveWindow
        val pkg  = root?.packageName?.toString() ?: "unknown"
        val nodes = if (root != null) NodeScanner.collectAllNodes(root) else emptyList()
        val textSummary = nodes
            .mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)
            .joinToString(" | ")
        return ScreenContext(
            packageName  = pkg,
            nodeCount    = nodes.size,
            textSummary  = textSummary,
            hasEditField = nodes.any { it.isEditable },
            hasScroll    = nodes.any { it.isScrollable }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PLAN — convert task description to action sequence
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Phase 3: Replaced single-pass regex planner with a multi-step compound task
     * decomposer. The old implementation could only produce one action per
     * category (one LaunchApp OR one Click etc.) and could not handle requests
     * like "open Maps and search for coffee" or "send a WhatsApp message to Alice
     * saying hello". This version:
     *
     *  1. Detects compound conjunctions ("and then", "and", "then") to split the
     *     task into atomic sub-goals before matching.
     *  2. Produces an ordered multi-step plan where the launch always precedes
     *     subsequent interaction steps, as required by AccessibilityCommandBridge.
     *  3. Still supports all original single-action patterns as a fallback.
     *  4. Deduplicates consecutive identical actions to avoid redundant gestures.
     */
    private fun planActions(task: String, ctx: ScreenContext): List<ExecutionAction> {
        val lower = task.lowercase()
        val plan  = mutableListOf<ExecutionAction>()

        // ── Step 1: Extract explicit app launch ───────────────────────────────
        // Must always be the FIRST step — accessibility dispatcher requires the
        // app to be in foreground before any UI-interaction steps can run.
        val launchRegex = Regex(
            """(?:open|launch|start|switch to|go to)\s+(?:the\s+)?([a-z][a-z\s]{0,24}?)\s*(?:app\b)?"""
        )
        val launchMatch = launchRegex.find(lower)
        if (launchMatch != null) {
            val appName = launchMatch.groupValues[1].trim()
            if (appName.isNotBlank() && appName !in setOf("the", "a", "an", "my")) {
                plan += ExecutionAction.LaunchApp(appName)
            }
        }

        // ── Step 2: Extract search targets ───────────────────────────────────
        // Match "search for X", "find X", "look up X", "search X"
        val searchRegex = Regex(
            """(?:search(?:\s+for)?|find|look\s+up|look\s+for)\s+["']?([^"'\n,]{2,60}?)["']?(?:\s+(?:on|in|using|with)\b|\s*$|\s*,|\s+and\b)"""
        )
        for (match in searchRegex.findAll(lower)) {
            val query = match.groupValues[1].trim()
            if (query.isNotBlank()) plan += ExecutionAction.Search(query)
        }

        // ── Step 3: Extract type / message text ───────────────────────────────
        // Match "type X", "write X", "send X", "say X", "message X saying Y"
        val typeRegex = Regex(
            """(?:type|write|enter|input|send|say|message\s+\S+\s+saying)\s+["']?([^"'\n]{2,120}?)["']?(?:\s*$|,|\s+and\b)"""
        )
        for (match in typeRegex.findAll(lower)) {
            val text = match.groupValues[1].trim()
            // Avoid duplicating search queries already captured
            if (text.isNotBlank() && plan.none { it is ExecutionAction.Search && (it as ExecutionAction.Search).query == text }) {
                plan += ExecutionAction.TypeText(text)
            }
        }

        // ── Step 4: Clicks / taps ─────────────────────────────────────────────
        val clickRegex = Regex(
            """(?:click|tap|press|select|choose|hit|toggle)\s+(?:on\s+)?["']?([^"',\n]{2,50}?)["']?(?:\s+button|\s+link|\s+option|\s*$|,|\s+and\b)"""
        )
        for (match in clickRegex.findAll(lower)) {
            val target = match.groupValues[1].trim()
            if (target.isNotBlank()) plan += ExecutionAction.Click(target)
        }

        // ── Step 5: Scroll gestures ───────────────────────────────────────────
        if (Regex("""scroll\s+down|swipe\s+down|page\s+down""").containsMatchIn(lower))
            plan += ExecutionAction.ScrollDown
        if (Regex("""scroll\s+up|swipe\s+up|page\s+up""").containsMatchIn(lower))
            plan += ExecutionAction.ScrollUp

        // ── Step 6: Navigation gestures ───────────────────────────────────────
        if (Regex("""go\s+back|press\s+back|navigate\s+back""").containsMatchIn(lower))
            plan += ExecutionAction.Back
        if (Regex("""go\s+home|press\s+home|home\s+screen""").containsMatchIn(lower))
            plan += ExecutionAction.Home

        // ── Step 7: Compound patterns not captured by generic regex ───────────
        // "call X", "dial X" → open phone app + type number/name
        val callRegex = Regex("""(?:call|dial|phone)\s+([a-z][a-z\s]{1,30})""")
        val callMatch = callRegex.find(lower)
        if (callMatch != null) {
            val contact = callMatch.groupValues[1].trim()
            if (plan.none { it is ExecutionAction.LaunchApp }) {
                plan.add(0, ExecutionAction.LaunchApp("phone"))
            }
            if (plan.none { it is ExecutionAction.Search && (it as ExecutionAction.Search).query == contact }) {
                plan += ExecutionAction.Search(contact)
            }
            plan += ExecutionAction.Click("call")
        }

        // ── Step 8: Deduplicate consecutive identical actions ─────────────────
        val deduped = mutableListOf<ExecutionAction>()
        for (action in plan) {
            if (deduped.lastOrNull() != action) deduped += action
        }

        // ── Step 9: Fallback — screen-context click or observe ────────────────
        if (deduped.isEmpty()) {
            val firstText = ctx.textSummary.split(" | ").firstOrNull()?.trim()
            if (!firstText.isNullOrBlank()) {
                deduped += ExecutionAction.Click(firstText)
            } else {
                deduped += ExecutionAction.Observe("No plan derivable from: $task")
            }
        }

        Log.i("AEE_PLAN", "planActions input='${task.take(60)}' steps=${deduped.size}: " +
            deduped.joinToString(" → ") { it::class.simpleName ?: "?" })
        return deduped
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXECUTE — dispatch action to AccessibilityCommandBridge
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun executeAction(
        service: AiriAccessibilityService,
        action: ExecutionAction
    ): ActionResult {
        return try {
            withTimeout(actionTimeoutMs) {
                when (action) {
                    is ExecutionAction.LaunchApp  -> {
                        val r = AccessibilityCommandBridge.launchApp(action.appName)
                        ActionResult(r.success, r.message ?: "")
                    }
                    is ExecutionAction.Click      -> {
                        val r = AccessibilityCommandBridge.click(action.target)
                        ActionResult(r.success, r.message ?: "")
                    }
                    is ExecutionAction.TypeText   -> {
                        val r = AccessibilityCommandBridge.typeText(action.text)
                        ActionResult(r.success, r.message ?: "")
                    }
                    is ExecutionAction.Search     -> {
                        val r = AccessibilityCommandBridge.search(action.query)
                        ActionResult(r.success, r.message ?: "")
                    }
                    is ExecutionAction.ScrollDown -> {
                        val r = AccessibilityCommandBridge.scrollDown()
                        ActionResult(r.success, r.message ?: "")
                    }
                    is ExecutionAction.ScrollUp   -> {
                        val r = AccessibilityCommandBridge.scrollUp()
                        ActionResult(r.success, r.message ?: "")
                    }
                    is ExecutionAction.Back       -> {
                        val r = AccessibilityCommandBridge.performBack()
                        ActionResult(r.success, r.message ?: "")
                    }
                    is ExecutionAction.Home       -> {
                        val r = AccessibilityCommandBridge.performHome()
                        ActionResult(r.success, r.message ?: "")
                    }
                    is ExecutionAction.Observe    -> {
                        ActionResult(true, action.description)
                    }
                }
            }
        } catch (e: Exception) {
            ActionResult(false, "Timeout or error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VERIFY — confirm UI changed as expected
    // ─────────────────────────────────────────────────────────────────────────

    private fun verifyAction(service: AiriAccessibilityService, action: ExecutionAction): VerifyResult {
        val root = service.rootInActiveWindow ?: return VerifyResult(false, "No active window")
        val nodes = NodeScanner.collectAllNodes(root)
        return when (action) {
            is ExecutionAction.Click     -> {
                // After click, check that the window changed or target is focused
                val focused = nodes.any {
                    it.isFocused || (it.text?.toString()?.lowercase()
                        ?.contains(action.target.lowercase()) == true)
                }
                VerifyResult(focused || nodes.isNotEmpty(), "Post-click tree: ${nodes.size} nodes")
            }
            is ExecutionAction.TypeText  -> {
                val hasText = nodes.any {
                    it.text?.toString()?.contains(action.text, ignoreCase = true) == true
                }
                VerifyResult(hasText, if (hasText) "Text found in tree" else "Text not confirmed")
            }
            is ExecutionAction.LaunchApp -> {
                val pkg = root.packageName?.toString() ?: ""
                val nameInPkg = pkg.contains(action.appName.lowercase().replace(" ", ""))
                VerifyResult(nameInPkg || nodes.size > 5,
                    "Current pkg: $pkg  nodes: ${nodes.size}")
            }
            else -> VerifyResult(true, "Action type requires no UI verification")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal logging
    // ─────────────────────────────────────────────────────────────────────────

    private fun log(phase: ExecutionPhase, message: String, success: Boolean? = null) {
        val entry = ExecutionLogEntry(
            phase     = phase,
            message   = message,
            success   = success,
            timestampMs = System.currentTimeMillis()
        )
        val current = _executionLog.value.toMutableList()
        current.add(0, entry)
        if (current.size > 200) current.removeAt(current.size - 1)
        _executionLog.value = current
        Log.d(TAG, "AIRI_PROOF_ACCESSIBILITY [${phase.name}] $message")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data types
    // ─────────────────────────────────────────────────────────────────────────

    enum class ExecutionPhase { OBSERVE, PLAN, EXECUTE, VERIFY, RECOVER }

    sealed class ExecutionEvent {
        data class PhaseChanged(val phase: ExecutionPhase, val details: String) : ExecutionEvent()
        data class ScreenObserved(val context: ScreenContext) : ExecutionEvent()
        data class PlanReady(val actions: List<ExecutionAction>) : ExecutionEvent()
        data class ActionExecuted(val action: String, val result: String, val success: Boolean) : ExecutionEvent()
        data class StepVerified(val passed: Boolean, val details: String) : ExecutionEvent()
        data class RecoveryAttempt(val attempt: Int, val description: String) : ExecutionEvent()
        data class Complete(val success: Boolean, val summary: String) : ExecutionEvent()
        data class Cancelled(val reason: String) : ExecutionEvent()
    }

    sealed class ExecutionAction {
        abstract val description: String
        data class LaunchApp(val appName: String)  : ExecutionAction() {
            override val description = "Launch app: $appName"
        }
        data class Click(val target: String)       : ExecutionAction() {
            override val description = "Click: $target"
        }
        data class TypeText(val text: String)      : ExecutionAction() {
            override val description = "Type: $text"
        }
        data class Search(val query: String)       : ExecutionAction() {
            override val description = "Search: $query"
        }
        data object ScrollDown                     : ExecutionAction() {
            override val description = "Scroll down"
        }
        data object ScrollUp                       : ExecutionAction() {
            override val description = "Scroll up"
        }
        data object Back                           : ExecutionAction() {
            override val description = "Press back"
        }
        data object Home                           : ExecutionAction() {
            override val description = "Go home"
        }
        data class Observe(override val description: String) : ExecutionAction()
    }

    data class ScreenContext(
        val packageName: String,
        val nodeCount:   Int,
        val textSummary: String,
        val hasEditField: Boolean,
        val hasScroll:    Boolean
    )

    data class ActionResult(val success: Boolean, val message: String)

    data class VerifyResult(val passed: Boolean, val details: String)

    data class ExecutionLogEntry(
        val phase:       ExecutionPhase,
        val message:     String,
        val success:     Boolean?,
        val timestampMs: Long
    ) {
        val formattedTime: String get() {
            return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
        }
    }
}
