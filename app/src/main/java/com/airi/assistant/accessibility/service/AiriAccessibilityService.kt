package com.airi.assistant.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.airi.assistant.accessibility.execution.AccessibilityExecutionEngine
import com.airi.assistant.accessibility.security.AccessibilityScopePolicy
import com.airi.assistant.agent.subagent.SubAgentRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AiriAccessibilityService — Android accessibility service powering the AIRI agent layer.
 *
 * ── Phase-3 P0 hardening ──────────────────────────────────────────────────────
 * Two surgical changes vs. the prior version:
 *
 *   1. Event types narrowed at runtime from `typeAllMask` (declared in
 *      `accessibility_service_config.xml`) to the specific events the
 *      agent layer actually consumes. This drastically cuts log noise,
 *      reduces battery drain, and limits the surface of accidentally
 *      collected sensitive data (e.g. typing in password fields).
 *
 *   2. Screen-context publication is gated by [AccessibilityScopePolicy].
 *      When the user is in a denylisted package (banking, 2FA, password
 *      managers, system UI…) we publish a *redacted* ScreenState that
 *      keeps the package name visible but reports `nodeCount=0`, so
 *      downstream features (chat "what's on my screen?", agent screen
 *      summaries) cannot leak content from those packages even if the
 *      user has accessibility enabled.
 *
 * Autonomous-action gating is *not* applied here because the execution
 * engine ([AccessibilityExecutionEngine]) has not yet been wired up to
 * consult the policy. That is the next surgical batch — see
 * `AccessibilityScopePolicy.kt` integration notes. Until then, autonomous
 * actions retain their previous behavior. The read-side gate, however,
 * is fully effective from this patch alone.
 */
class AiriAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AiriAccessibilityService"
        private const val CAPABILITY_ACCESSIBILITY = "airi_accessibility_enabled"

        /** Singleton — set when service is connected, null when disconnected. */
        @Volatile
        var instance: AiriAccessibilityService? = null
            private set

        /** True iff the accessibility service is currently connected. */
        val isConnected: Boolean
            get() = instance != null

        private val _screenContext = MutableStateFlow(ScreenState())
        /** Live screen context StateFlow — observe for reactive UI understanding. */
        val screenContext: StateFlow<ScreenState> = _screenContext.asStateFlow()
    }

    /** The execution engine — use this for all autonomous task execution. */
    val executionEngine = AccessibilityExecutionEngine()

    /** Policy for per-package gating. Lazily-initialized via the application context. */
    private val policy by lazy { AccessibilityScopePolicy.get(applicationContext) }

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ScreenContextHolder.serviceInstance = this

        // Configure event types we care about.
        //
        // We deliberately do NOT subscribe to TYPE_VIEW_TEXT_SELECTION_CHANGED,
        // TYPE_NOTIFICATION_STATE_CHANGED, or TYPE_VIEW_SCROLLED — these fire
        // at very high rates and would either drown the agent layer in noise
        // or leak text from password / OTP fields into the logs.
        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            )
            info.feedbackType  = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags         = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                                  AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.notificationTimeout = 100L
        }

        // Unlock AndroidAgent routing in SubAgentRegistry
        try {
            SubAgentRegistry.grantCapability(CAPABILITY_ACCESSIBILITY)
        } catch (_: Exception) {
            // Registry may not be initialized yet — AndroidAgent.canHandle() checks
            // AiriAccessibilityService.isConnected directly as fallback.
        }

        Log.i(TAG, "AIRI_PROOF_ACCESSIBILITY SERVICE_CONNECTED policyMode=${policy.state.value.mode}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                updateScreenState(event)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // Only log when reads are policy-allowed for the focused package
                // and the field is not a password/secure input.
                val pkg = event.packageName?.toString().orEmpty()
                if (!policy.readsAllowedFor(pkg)) return
                if (event.source?.isPassword == true) return
                val text = event.source?.text?.toString() ?: ""
                if (text.isNotBlank()) {
                    Log.v(TAG, "Focus: ${text.take(40)} in $pkg")
                }
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AIRI_PROOF_ACCESSIBILITY SERVICE_INTERRUPTED")
        executionEngine.killSwitch("AccessibilityService interrupted")
    }

    override fun onDestroy() {
        executionEngine.killSwitch("AccessibilityService destroyed")
        instance = null
        ScreenContextHolder.serviceInstance = null

        // Revoke accessibility capability
        try {
            SubAgentRegistry.revokeCapability(CAPABILITY_ACCESSIBILITY)
        } catch (_: Exception) { /* ignore if not initialized */ }

        _screenContext.value = ScreenState()
        Log.i(TAG, "AIRI_PROOF_ACCESSIBILITY SERVICE_DESTROYED")
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screen state capture
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateScreenState(event: AccessibilityEvent) {
        val pkg       = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // Policy-gated read. For denylisted packages we still publish the
        // package name (so the UI can reflect "AIRI is paused for this app")
        // but we do NOT walk the node tree.
        if (!policy.readsAllowedFor(pkg)) {
            _screenContext.value = ScreenState(
                packageName = pkg,
                className   = className,
                nodeCount   = 0,
                eventType   = "REDACTED_BY_POLICY",
                timestampMs = System.currentTimeMillis()
            )
            return
        }

        val root      = rootInActiveWindow
        val nodeCount = if (root != null) countNodes(root) else 0

        _screenContext.value = ScreenState(
            packageName = pkg,
            className   = className,
            nodeCount   = nodeCount,
            eventType   = eventTypeName(event.eventType),
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun countNodes(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int = 0): Int {
        if (depth > 50) return 1
        var count = 1
        for (i in 0 until node.childCount) {
            count += countNodes(node.getChild(i) ?: continue, depth + 1)
        }
        return count
    }

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   -> "WINDOW_STATE"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT"
        AccessibilityEvent.TYPE_VIEW_FOCUSED            -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_CLICKED            -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED       -> "TEXT_CHANGED"
        else -> "EVENT_$type"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data types
    // ─────────────────────────────────────────────────────────────────────────

    data class ScreenState(
        val packageName: String = "",
        val className:   String = "",
        val nodeCount:   Int    = 0,
        val eventType:   String = "",
        val timestampMs: Long   = 0L
    )
}
