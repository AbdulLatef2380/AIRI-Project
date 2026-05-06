package com.airi.assistant.connector.accessibility

import android.util.Log
import com.airi.assistant.accessibility.execution.AccessibilityExecutionEngine
import com.airi.assistant.accessibility.AccessibilityExecutionEngine.ExecutionEvent
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AccessibilityAutomationConnector — Android UI automation via the
 * [AccessibilityExecutionEngine] surfaced as a first-class [Connector].
 *
 * This connector bridges the accessibility service layer into the connector
 * architecture so that agent plans can issue UI automation actions through
 * [com.airi.assistant.connector.ConnectorActionBridge] using the same routing
 * as all other connectors.
 *
 * ── ACTIONS ─────────────────────────────────────────────────────────────
 *
 * | action         | required params              | notes                         |
 * |----------------|------------------------------|-------------------------------|
 * | `execute_task` | task (natural-language)      | Full autonomous UI task       |
 * | `tap`          | target (element name/text)   | Tap a named UI element        |
 * | `type`         | text                         | Type text into focused field  |
 * | `scroll_down`  | —                            | Scroll the active view down   |
 * | `scroll_up`    | —                            | Scroll the active view up     |
 * | `back`         | —                            | Press back button             |
 * | `home`         | —                            | Press home button             |
 * | `open_app`     | app (app name)               | Launch an app by name         |
 * | `search_in_app`| app, query                   | Open app and search           |
 *
 * ── SECURITY ─────────────────────────────────────────────────────────────
 *
 *   All actions require that the AIRI AccessibilityService is enabled.
 *   If the service is not connected, actions return a [ConnectorOutput.Failure]
 *   with code "a11y_service_unavailable".
 */
class AccessibilityAutomationConnector(
    private val engine: AccessibilityExecutionEngine
) : Connector {

    override val id          = "accessibility_automation"
    override val name        = "Accessibility Automation"
    override val description = "Automate on-screen UI actions via Android accessibility service."
    override val type        = ConnectorType.SYSTEM

    private val _state = MutableStateFlow(
        ConnectorState(connected = false, healthy = false, statusLine = "Accessibility service not checked")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("accessibility", "automation", "ui", "tap", "type", "scroll", "a11y", "open_app")
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        val available = isServiceAvailable()
        _state.value = ConnectorState(
            connected     = available,
            healthy       = available,
            statusLine    = if (available) "Accessibility service connected"
                           else           "Accessibility service unavailable — enable in Settings → Accessibility",
            lastUpdatedMs = System.currentTimeMillis()
        )
        return _state.value
    }

    override suspend fun disconnect() {
        _state.value = _state.value.copy(connected = false, statusLine = "Disconnected")
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        if (!isServiceAvailable()) {
            return@withContext ConnectorOutput.Failure(
                code    = "a11y_service_unavailable",
                message = "AIRI Accessibility Service is not enabled. Go to Settings → Accessibility → AIRI and enable it."
            )
        }

        val taskDescription = buildTaskDescription(input)
            ?: return@withContext ConnectorOutput.Failure(
                "unknown_action",
                "AccessibilityAutomationConnector: unknown action '${input.action}'"
            )

        val start = System.currentTimeMillis()
        val result = executeTask(taskDescription)
        val elapsed = System.currentTimeMillis() - start

        Log.i("AIRI_PROOF", "A11Y_ACTION action=${input.action} task='${taskDescription.take(60)}' elapsed=${elapsed}ms success=${result is ConnectorOutput.Success}")
        result
    }

    // ── Task description builder ──────────────────────────────────────────────

    private fun buildTaskDescription(input: ConnectorInput): String? {
        return when (input.action.lowercase()) {
            "execute_task" -> {
                input.params["task"] ?: input.text.takeIf { it.isNotBlank() }
                    ?: return null
            }
            "tap" -> {
                val target = input.params["target"] ?: input.text.takeIf { it.isNotBlank() }
                    ?: return null
                "Tap on '$target'"
            }
            "type" -> {
                val text = input.params["text"] ?: input.text.takeIf { it.isNotBlank() }
                    ?: return null
                "Type '$text'"
            }
            "scroll_down" -> "Scroll down"
            "scroll_up"   -> "Scroll up"
            "back"        -> "Go back"
            "home"        -> "Go home"
            "open_app" -> {
                val app = input.params["app"] ?: input.text.takeIf { it.isNotBlank() }
                    ?: return null
                "Open $app"
            }
            "search_in_app" -> {
                val app   = input.params["app"] ?: return null
                val query = input.params["query"] ?: input.text.takeIf { it.isNotBlank() }
                    ?: return null
                "Open $app and search for '$query'"
            }
            else -> null
        }
    }

    // ── Engine execution ──────────────────────────────────────────────────────

    private suspend fun executeTask(taskDescription: String): ConnectorOutput =
        withContext(Dispatchers.Main) {
            engine.reset()

            var summary  = ""
            var succeeded = false
            var actionCount = 0

            withTimeoutOrNull(TASK_TIMEOUT_MS) {
                engine.executeTask(taskDescription).collect { event ->
                    when (event) {
                        is ExecutionEvent.ActionExecuted -> {
                            actionCount++
                            if (!event.success) {
                                Log.d("A11Y_CONNECTOR", "Action failed: ${event.action} — ${event.result}")
                            }
                        }
                        is ExecutionEvent.Complete -> {
                            succeeded = event.success
                            summary   = event.summary
                        }
                        is ExecutionEvent.Cancelled -> {
                            summary = "Cancelled: ${event.reason}"
                        }
                        else -> Unit
                    }
                }
            } ?: run {
                engine.killSwitch("ConnectorTimeout")
                return@withContext ConnectorOutput.Failure(
                    code = "timeout",
                    message = "Accessibility task timed out after ${TASK_TIMEOUT_MS / 1000}s: $taskDescription"
                )
            }

            if (succeeded) {
                ConnectorOutput.Success(
                    text = summary,
                    data = mapOf(
                        "task"         to taskDescription,
                        "action_count" to actionCount.toString()
                    )
                )
            } else {
                ConnectorOutput.Failure(
                    code    = "task_failed",
                    message = summary.ifBlank { "Accessibility task did not complete: $taskDescription" }
                )
            }
        }

    private fun isServiceAvailable(): Boolean =
        com.airi.assistant.accessibility.service.AiriAccessibilityService.instance != null

    companion object {
        private const val TASK_TIMEOUT_MS = 60_000L
    }
}
