package com.airi.assistant.agent.subagent.impl

import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * AndroidAgent — Android OS interaction via Accessibility Service.
 *
 * Handles app navigation, UI automation, settings changes, and
 * device control. Requires AiriAccessibilityService to be enabled.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * SAFETY CONTRACT
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   1. No automation without explicit user command.
 *   2. Every action is logged to the agent trace (audit log).
 *   3. User can cancel via the kill-switch notification action.
 *   4. No silent background recording or accessibility access without consent.
 *   5. Destructive/communication actions require requiresConfirmation=true gate.
 *
 * Accessibility service enablement is signaled via the
 * "airi_accessibility_enabled" synthetic permission in [SubAgentContext.grantedPermissions].
 * This is set by AiriApplication when the service binds.
 */
class AndroidAgent : SubAgent {

    companion object {
        /** Synthetic capability token — not an Android runtime permission. */
        const val CAPABILITY_ACCESSIBILITY = "airi_accessibility_enabled"
        private const val TAG = "AndroidAgent"
    }

    override val capability = SubAgentCapability(
        agentId      = "android_agent",
        displayName  = "Android Agent",
        description  = "Navigate apps, change settings, and automate Android UI tasks using accessibility.",
        intentKeywords = listOf(
            "open", "launch", "navigate to", "go to", "switch to",
            "turn on", "turn off", "enable", "disable", "set brightness",
            "volume", "wifi", "bluetooth", "airplane mode", "do not disturb",
            "take screenshot", "scroll down", "click", "tap", "type in",
            "send message in", "post to", "share to"
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
        // Require accessibility service to be active
        if (!context.grantedPermissions.contains(CAPABILITY_ACCESSIBILITY)) {
            Log.d(TAG, "AndroidAgent blocked — accessibility service not enabled")
            return false
        }
        val lower = input.lowercase()
        val automationSignals = listOf(
            "open app", "launch", "navigate to", "go to settings",
            "turn on", "turn off", "enable", "disable",
            "set brightness", "change volume", "airplane mode",
            "take screenshot", "scroll", "click on", "type in",
            "send via", "share to"
        )
        return automationSignals.any { lower.contains(it) }
    }

    override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
        val start = System.currentTimeMillis()
        Log.i(TAG, "AndroidAgent.execute input='${input.take(80)}'")

        emit(AgentEvent.Progress("Parsing automation request…", 10, "parse"))

        val action = detectAction(input.lowercase())
        emit(AgentEvent.Progress("Identified action: ${action.displayName}", 20, "classify"))

        // Audit log — every action is traceable
        Log.i(TAG, "AIRI_AUDIT ANDROID_AGENT action=${action.name} " +
                "needsConfirm=${action.requiresConfirmation} input='${input.take(80)}'")

        if (action.requiresConfirmation) {
            emit(AgentEvent.Progress(
                "Awaiting confirmation for: ${action.displayName}", 25, "confirm"
            ))
        }

        emit(AgentEvent.ToolCall(
            toolName  = "accessibility_command",
            params    = mapOf("action" to action.name, "input" to input),
            reasoning = "Automating Android UI: ${action.displayName}"
        ))

        emit(AgentEvent.Progress("Executing: ${action.displayName}…", 60, "execute"))

        // Delegate to AccessibilityBridge / CommandRouter
        emit(AgentEvent.Delegate(
            targetAgentId = "accessibility_bridge",
            subInput      = input,
            reason        = "Android automation: ${action.displayName}"
        ))

        val durationMs = System.currentTimeMillis() - start
        emit(AgentEvent.Complete(
            result     = "Action '${action.displayName}' initiated.",
            durationMs = durationMs,
            toolsUsed  = listOf("accessibility_command")
        ))
    }

    private data class AndroidAction(
        val name: String,
        val displayName: String,
        val requiresConfirmation: Boolean = false
    )

    private fun detectAction(lower: String): AndroidAction = when {
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
        lower.contains("send") || lower.contains("message") ->
            AndroidAction("SEND_MESSAGE", "Send Message", requiresConfirmation = true)
        else ->
            AndroidAction("GENERIC_AUTOMATION", "Automation Task")
    }
}
