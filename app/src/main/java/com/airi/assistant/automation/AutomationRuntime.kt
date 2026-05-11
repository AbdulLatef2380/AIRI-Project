package com.airi.assistant.automation

import android.util.Log
import com.airi.assistant.accessibility.execution.AccessibilityExecutionEngine
import com.airi.assistant.accessibility.service.AiriAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AutomationRuntime — unified computer-use orchestrator.
 *
 * Composes [AccessibilityExecutionEngine] (OBSERVE→PLAN→EXECUTE→VERIFY→RECOVER)
 * with [UIInteractionEngine] (semantic element detection) into a single
 * automation API that the agent pipeline calls.
 *
 * ── COMMAND MODEL ────────────────────────────────────────────────────────────
 *
 *  | Command                  | Description                                |
 *  |--------------------------|--------------------------------------------|
 *  | tap(target)              | Tap a semantic UI element                  |
 *  | type(field, text)        | Type text into a field                     |
 *  | scroll(direction)        | Scroll in the primary scroll container     |
 *  | back()                   | Press the system back button               |
 *  | home()                   | Press the system home button               |
 *  | executeTask(desc)        | Full OBSERVE→PLAN→EXECUTE loop via engine  |
 *
 * ── SAFETY ───────────────────────────────────────────────────────────────────
 *
 *   - Kill switch via [abort] — callable from any thread.
 *   - Accessibility permission check before any dispatch.
 *   - Per-primitive timeout of [COMMAND_TIMEOUT_MS].
 */
class AutomationRuntime(
    private val accessibilityEngine: AccessibilityExecutionEngine,
    private val uiEngine:            UIInteractionEngine,
) {

    private val TAG     = "AutomationRuntime"
    private val aborted = AtomicBoolean(false)

    // ── State ─────────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(AutomationState())
    val state: StateFlow<AutomationState> = _state.asStateFlow()

    data class AutomationState(
        val isRunning:       Boolean = false,
        val currentTask:     String  = "",
        val actionsExecuted: Int     = 0,
        val lastError:       String? = null,
        val isPermissionOk:  Boolean = false,
    )

    sealed class AutomationEvent {
        data class Started(val task: String)                         : AutomationEvent()
        data class ActionExecuted(val action: String, val ok: Boolean) : AutomationEvent()
        data class Progress(val message: String, val pct: Int)       : AutomationEvent()
        data class Failed(val reason: String)                        : AutomationEvent()
        data class Completed(val summary: String)                    : AutomationEvent()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun isAccessibilityReady(): Boolean = AiriAccessibilityService.instance != null

    fun abort() {
        aborted.set(true)
        accessibilityEngine.killSwitch("AutomationRuntime abort()")
        _state.value = _state.value.copy(isRunning = false)
        Log.w(TAG, "AUTOMATION_ABORTED")
    }

    fun resetAbort() = aborted.set(false)

    /**
     * Execute a full natural-language automation task via the OBSERVE→PLAN→EXECUTE loop.
     */
    fun executeTask(taskDescription: String): Flow<AutomationEvent> = flow {
        if (!isAccessibilityReady()) {
            emit(AutomationEvent.Failed("Accessibility Service not active. Enable AIRI Accessibility in Settings."))
            return@flow
        }

        aborted.set(false)
        _state.value = _state.value.copy(
            isRunning      = true,
            currentTask    = taskDescription,
            lastError      = null,
            isPermissionOk = true,
        )
        emit(AutomationEvent.Started(taskDescription))
        Log.i(TAG, "AUTOMATION_START task='${taskDescription.take(80)}'")

        try {
            var actionCount = 0
            accessibilityEngine.executeTask(taskDescription).collect { event ->
                if (aborted.get()) return@collect
                when (event) {
                    is AccessibilityExecutionEngine.ExecutionEvent.ActionExecuted -> {
                        actionCount++
                        _state.value = _state.value.copy(actionsExecuted = actionCount)
                        emit(AutomationEvent.ActionExecuted(event.action, event.success))
                    }
                    is AccessibilityExecutionEngine.ExecutionEvent.PhaseChanged -> {
                        emit(AutomationEvent.Progress(event.details, 50))
                    }
                    is AccessibilityExecutionEngine.ExecutionEvent.Complete -> {
                        _state.value = _state.value.copy(isRunning = false)
                        if (event.success) emit(AutomationEvent.Completed(event.summary))
                        else emit(AutomationEvent.Failed(event.summary))
                    }
                    is AccessibilityExecutionEngine.ExecutionEvent.Cancelled -> {
                        _state.value = _state.value.copy(isRunning = false, lastError = event.reason)
                        emit(AutomationEvent.Failed(event.reason))
                    }
                    else -> {}
                }
            }
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(isRunning = false)
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(isRunning = false, lastError = e.message)
            emit(AutomationEvent.Failed("Automation error: ${e.message}"))
            Log.e(TAG, "AUTOMATION_ERROR: ${e.message}", e)
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Semantic tap: find a UI element matching [target] and click it.
     */
    suspend fun tap(target: String): Boolean {
        if (!isAccessibilityReady() || aborted.get()) return false
        return runCatching {
            withTimeout(COMMAND_TIMEOUT_MS) {
                val element = uiEngine.findClickTarget(target)
                if (element != null && element.isClickable) {
                    element.node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "TAP_OK target='$target' label='${element.semanticLabel}'")
                    true
                } else {
                    Log.w(TAG, "TAP_MISS target='$target'")
                    false
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Type [text] into a semantic input field.
     */
    suspend fun type(fieldHint: String, text: String): Boolean {
        if (!isAccessibilityReady() || aborted.get()) return false
        return runCatching {
            withTimeout(COMMAND_TIMEOUT_MS) {
                val field = uiEngine.findInputField(fieldHint)
                if (field != null && field.isEditable) {
                    val bundle = android.os.Bundle()
                    bundle.putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    val ok = field.node.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    Log.d(TAG, "TYPE_${if (ok) "OK" else "FAIL"} field='$fieldHint'")
                    ok
                } else {
                    Log.w(TAG, "TYPE_MISS field='$fieldHint'")
                    false
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Scroll the primary scroll region.
     * @param direction "up" or "down"
     */
    suspend fun scroll(direction: String = "down"): Boolean {
        if (!isAccessibilityReady() || aborted.get()) return false
        return runCatching {
            val scrollable = uiEngine.findScrollRegion()
            val action = if (direction.equals("up", ignoreCase = true))
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val ok = scrollable?.node?.performAction(action) ?: false
            Log.d(TAG, "SCROLL_${if (ok) "OK" else "MISS"} dir=$direction")
            ok
        }.getOrDefault(false)
    }

    fun back(): Boolean {
        val service = AiriAccessibilityService.instance ?: return false
        return runCatching {
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }.getOrDefault(false)
    }

    fun home(): Boolean {
        val service = AiriAccessibilityService.instance ?: return false
        return runCatching {
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        }.getOrDefault(false)
    }

    companion object {
        private const val COMMAND_TIMEOUT_MS = 5_000L
    }
}
