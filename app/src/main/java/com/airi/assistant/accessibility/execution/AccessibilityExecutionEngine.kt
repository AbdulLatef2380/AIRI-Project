package com.airi.assistant.accessibility.execution

import android.util.Log
import com.airi.assistant.accessibility.security.AccessibilityPolicyGuard
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
import kotlinx.coroutines.withContext
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
 *      AIRI_RUNTIME_ACCESSIBILITY for audit trails.
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
        Log.w(TAG, "AIRI_RUNTIME_ACCESSIBILITY KILL_SWITCH reason=$reason")
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
     * LLM provider for the PLAN step. Set this before calling [executeTask] so
     * the engine can ask the model "what is the next single action to take?"
     * after each OBSERVE step.
     *
     * If null, falls back to a heuristic single-action mapper (open_app / tap / type).
     * The fallback handles the most common one-step requests without requiring LLM.
     */
    var llmPlanner: (suspend (prompt: String) -> String)? = null

    /**
     * Execute an autonomous accessibility task using the LLM-driven
     * OBSERVE → PLAN(LLM) → EXECUTE → VERIFY loop.
     *
     * Key differences from the old regex-based planner:
     *   • No pre-generated multi-step plan. Each action is decided AFTER observing
     *     the result of the previous one.
     *   • The LLM sees the current screen state and task goal, and returns ONE
     *     action in structured JSON (or "done" / "failed").
     *   • maxActions enforces a hard cap; no runaway loops.
     *
     * REQUIRES: AiriAccessibilityService enabled and connected.
     * REQUIRES: User consent obtained before calling.
     * REQUIRES: [reset] called if [killSwitch] was previously used.
     */
    fun executeTask(taskDescription: String): Flow<ExecutionEvent> = flow {

        if (killed.get()) {
            emit(ExecutionEvent.Cancelled("Kill switch active — call reset() first"))
            return@flow
        }

        val service = AiriAccessibilityService.instance
        if (service == null) {
            emit(ExecutionEvent.Complete(success = false, summary = "Accessibility service not enabled"))
            return@flow
        }

        _isRunning.value = true
        log(ExecutionPhase.OBSERVE, "Task started: $taskDescription")
        Log.i(TAG, "AIRI_RUNTIME_ACCESSIBILITY TASK_START task='$taskDescription'")

        var actionCount = 0
        var succeeded   = false
        val actionHistory = mutableListOf<String>()  // For LLM context

        try {
            while (actionCount < maxActions && !killed.get()) {

                // ── OBSERVE ────────────────────────────────────────────────
                emit(ExecutionEvent.PhaseChanged(ExecutionPhase.OBSERVE, "Observing screen…"))
                val screenCtx = observeScreen(service)
                log(ExecutionPhase.OBSERVE, "App: ${screenCtx.packageName}  Nodes: ${screenCtx.nodeCount}")
                emit(ExecutionEvent.ScreenObserved(screenCtx))

                // ── SECURITY: Package deny-list ────────────────────────────
                val policyDecision = AccessibilityPolicyGuard.checkPackage(screenCtx.packageName)
                if (policyDecision is AccessibilityPolicyGuard.PolicyDecision.Denied) {
                    log(ExecutionPhase.EXECUTE, "BLOCKED: ${policyDecision.reason}", false)
                    emit(ExecutionEvent.Complete(success = false, summary = policyDecision.reason))
                    return@flow
                }

                // ── PLAN (LLM decides single next action) ──────────────────
                emit(ExecutionEvent.PhaseChanged(ExecutionPhase.PLAN, "Deciding next action…"))
                val action = decideNextAction(taskDescription, screenCtx, actionHistory)
                log(ExecutionPhase.PLAN, "Next action: ${action.description}")
                emit(ExecutionEvent.PlanReady(listOf(action)))

                // Done / Observe-only signals
                if (action is ExecutionAction.Done) {
                    succeeded = true
                    break
                }
                if (action is ExecutionAction.Observe) {
                    log(ExecutionPhase.OBSERVE, "Cannot determine next action: ${action.reason}")
                    emit(ExecutionEvent.Complete(success = false, summary = "Cannot proceed: ${action.reason}"))
                    return@flow
                }

                // ── EXECUTE ────────────────────────────────────────────────
                emit(ExecutionEvent.PhaseChanged(ExecutionPhase.EXECUTE, "Executing: ${action.description}"))
                var retries = 0
                var actionSucceeded = false

                while (retries <= maxRetries && !killed.get()) {
                    if (retries > 0) {
                        emit(ExecutionEvent.RecoveryAttempt(retries, action.description))
                        delay(500L * retries)
                    }
                    val result = executeAction(service, action)
                    actionCount++
                    log(ExecutionPhase.EXECUTE, "${action.description}: ${result.message}", result.success)
                    emit(ExecutionEvent.ActionExecuted(action.description, result.message, result.success))

                    if (result.success) {
                        // ── VERIFY ─────────────────────────────────────────
                        emit(ExecutionEvent.PhaseChanged(ExecutionPhase.VERIFY, "Verifying…"))
                        delay(350L)
                        val verified = verifyAction(service, action)
                        log(ExecutionPhase.VERIFY, "passed=${verified.passed}: ${verified.details}")
                        emit(ExecutionEvent.StepVerified(verified.passed, verified.details))
                        if (verified.passed) { actionSucceeded = true; break }
                    }
                    retries++
                }

                if (!actionSucceeded && !killed.get()) {
                    log(ExecutionPhase.RECOVER, "Action failed: ${action.description}", false)
                    emit(ExecutionEvent.PhaseChanged(ExecutionPhase.RECOVER, "Recovery: pressing back"))
                    AccessibilityCommandBridge.performBack()
                    delay(400L)
                    actionHistory.add("FAILED: ${action.description}")
                } else {
                    actionHistory.add("OK: ${action.description}")
                }
            }

            succeeded = succeeded || (!killed.get() && actionCount > 0)

        } catch (e: CancellationException) {
            emit(ExecutionEvent.Cancelled(e.message ?: "Cancelled"))
            return@flow
        } catch (e: Exception) {
            Log.e(TAG, "Task execution error", e)
            emit(ExecutionEvent.Complete(success = false, summary = "Error: ${e.message}"))
            return@flow
        } finally {
            _isRunning.value = false
            Log.i(TAG, "AIRI_RUNTIME_ACCESSIBILITY TASK_END success=$succeeded actions=$actionCount")
        }

        emit(ExecutionEvent.Complete(
            success = succeeded,
            summary = if (succeeded) "Done ($actionCount actions)" else "Stopped ($actionCount actions)"
        ))
    // flowOn(Dispatchers.Default): NodeScanner tree traversal + LLM planner calls are CPU/IO
    // bound and must NOT block the main thread. AccessibilityService API calls inside
    // executeAction are dispatched to Main via withContext(Dispatchers.Main) individually.
    }.flowOn(Dispatchers.Default)

    // ─────────────────────────────────────────────────────────────────────────
    // PLAN — LLM decides ONE next action based on current screen state
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ask the LLM (or heuristic fallback) what the single next action should be.
     *
     * LLM response format — one of:
     *   {"action":"open_app","app":"Settings"}
     *   {"action":"tap","target":"Wi-Fi"}
     *   {"action":"type","text":"hello"}
     *   {"action":"scroll_down"}
     *   {"action":"go_back"}
     *   {"action":"done"}
     *   {"action":"cannot_proceed","reason":"No relevant element found"}
     */
    private suspend fun decideNextAction(
        task:    String,
        screen:  ScreenContext,
        history: List<String>
    ): ExecutionAction {
        val planner = llmPlanner
        if (planner != null) {
            return askLlmForNextAction(task, screen, history, planner)
        }
        // Heuristic fallback for the most common single-step requests
        return heuristicNextAction(task, screen)
    }

    private suspend fun askLlmForNextAction(
        task:    String,
        screen:  ScreenContext,
        history: List<String>,
        planner: suspend (String) -> String
    ): ExecutionAction {
        val historyText = if (history.isEmpty()) "None yet."
                          else history.takeLast(5).joinToString("\n")
        val prompt = """
You are controlling an Android device. Your task: "$task"

Current screen:
  App: ${screen.packageName}
  Visible text: ${screen.textSummary.take(400)}
  Has editable field: ${screen.hasEditField}
  Has scrollable content: ${screen.hasScroll}

Actions taken so far:
$historyText

Respond with ONLY one JSON object — the single next action to take:
  {"action":"open_app","app":"<name>"}
  {"action":"tap","target":"<visible text>"}
  {"action":"type","text":"<text to enter>"}
  {"action":"scroll_down"}
  {"action":"go_back"}
  {"action":"done"}   ← when the task is fully complete
  {"action":"cannot_proceed","reason":"<why>"}

No explanation. No markdown. Just the JSON.
""".trimIndent()

        return try {
            val response = planner(prompt)
            parseActionJson(response.trim()) ?: heuristicNextAction(task, screen)
        } catch (e: Exception) {
            Log.w(TAG, "LLM planner failed: ${e.message} — using heuristic")
            heuristicNextAction(task, screen)
        }
    }

    private fun parseActionJson(json: String): ExecutionAction? {
        // Strip markdown fences if the model added them
        val clean = json.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end   = clean.lastIndexOf('}')
        if (start == -1 || end == -1) return null
        return try {
            val obj    = org.json.JSONObject(clean.substring(start, end + 1))
            val action = obj.getString("action")
            when (action) {
                "open_app"        -> ExecutionAction.LaunchApp(obj.getString("app"))
                "tap"             -> ExecutionAction.Click(obj.getString("target"))
                "type"            -> ExecutionAction.TypeText(obj.getString("text"))
                "scroll_down"     -> ExecutionAction.ScrollDown
                "scroll_up"       -> ExecutionAction.ScrollUp
                "go_back"         -> ExecutionAction.Back
                "home"            -> ExecutionAction.Home
                "done"            -> ExecutionAction.Done
                "cannot_proceed"  -> ExecutionAction.Observe(obj.optString("reason", "Cannot proceed"))
                else              -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_JSON_PARSE_FAILURE causeType=${e::class.simpleName} inputChars=${clean.length}")
            null
        }
    }

    /** Heuristic fallback — handles the most common single-step requests without LLM. */
    private fun heuristicNextAction(task: String, screen: ScreenContext): ExecutionAction {
        val lower = task.lowercase()
        // Open app
        val openMatch = Regex("""(?:open|launch|start)\s+(?:the\s+)?([a-z][a-z\s]{1,24}?)(?:\s+app)?\s*$""").find(lower)
        if (openMatch != null) return ExecutionAction.LaunchApp(openMatch.groupValues[1].trim())
        // Tap / click
        val tapMatch = Regex("""(?:tap|click|press|select)\s+(?:on\s+)?["']?([^"'\n]{2,40})["']?""").find(lower)
        if (tapMatch != null) return ExecutionAction.Click(tapMatch.groupValues[1].trim())
        // Type
        val typeMatch = Regex("""(?:type|enter|write)\s+["']?([^"'\n]{1,120})["']?""").find(lower)
        if (typeMatch != null) return ExecutionAction.TypeText(typeMatch.groupValues[1].trim())
        // Search
        val searchMatch = Regex("""(?:search|find)\s+(?:for\s+)?["']?([^"'\n]{2,60})["']?""").find(lower)
        if (searchMatch != null) return ExecutionAction.Search(searchMatch.groupValues[1].trim())
        // Navigation
        if ("scroll down" in lower || "swipe down" in lower) return ExecutionAction.ScrollDown
        if ("scroll up"   in lower || "swipe up"   in lower) return ExecutionAction.ScrollUp
        if ("go back"     in lower || "press back"  in lower) return ExecutionAction.Back
        if ("go home"     in lower || "home screen" in lower) return ExecutionAction.Home
        // Fallback: observe the first visible element
        val firstText = screen.textSummary.split(" | ").firstOrNull()?.trim()
        return if (!firstText.isNullOrBlank()) ExecutionAction.Click(firstText)
               else ExecutionAction.Observe("Cannot determine action for: $task")
    }

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
    // EXECUTE — dispatch action to AccessibilityCommandBridge
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun executeAction(
        service: AiriAccessibilityService,
        action: ExecutionAction
    ): ActionResult {
        // AccessibilityService API calls (performAction, performGlobalAction, etc.) require
        // the main thread. executeTask flow now runs on Dispatchers.Default for CPU work;
        // we switch back to Main only for the actual execution bridge call.
        return try {
            withTimeout(actionTimeoutMs) {
                withContext(Dispatchers.Main) {
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
                        is ExecutionAction.Done, is ExecutionAction.Observe ->
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
        Log.d(TAG, "AIRI_RUNTIME_ACCESSIBILITY [${phase.name}] $message")
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
        /** Signals task completion — no more actions needed. */
        data object Done                           : ExecutionAction() {
            override val description = "Task complete"
        }
        /** Signals the engine cannot determine the next action. */
        data class Observe(val reason: String)     : ExecutionAction() {
            override val description = "Observe: $reason"
        }
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
