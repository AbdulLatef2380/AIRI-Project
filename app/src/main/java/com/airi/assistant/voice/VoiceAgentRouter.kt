package com.airi.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.agent.subagent.SubAgentRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * VoiceAgentRouter — the bridge that transforms STT transcripts into agent actions.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * DATA FLOW
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   LiveVoiceService.onSpeechResult(text)
 *     ↓
 *   VoiceAgentRouter.route(text, voiceSessionId)
 *     ↓
 *   SubAgentRegistry.route()        ← keyword scoring + canHandle() hint
 *     ↓
 *   VoiceRouteResult.Fallback       ← every intent enters the canonical path
 *     ↓
 *   ChatViewModel → AgentLoop       ← admission, tools, sandbox, approval and trace
 *
 *   This router never executes an agent. The registry result is only a routing
 *   hint; execution authority belongs to the request-scoped AgentLoop.
 *
 *   ── OR ──
 *
 *   VoiceRouteResult.Fallback       ← LiveVoiceService emits to voiceTranscriptBus
 *     ↓
 *   ChatViewModel.sendMessage()     ← full LLM / AgentService pipeline
 *
 * ─────────────────────────────────────────────────────────────────────────
 * THREAD SAFETY
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   [route] is a suspend function. Call it from [LiveVoiceService.serviceScope]
 *   (Main dispatcher). I/O-bound registry lookup and orchestrator execution
 *   are dispatched to [Dispatchers.IO] internally.
 *   The caller owns audio output, so routing never opens or releases microphone resources.
 */
class VoiceAgentRouter(
    private val appContext: Context,
    private val orchestrator: ProductionAgentOrchestrator,
    /**
     * V2: supplies the UI confirmation gate (title, description) → approved. Resolved on every
     * request so a gate registered after construction is honoured. Null = no UI available.
     */
    private val confirmationGateProvider: () -> (suspend (String, String) -> Boolean)? = { null }
) {

    // ─────────────────────────────────────────────────────────────────────────
    // Result type
    // ─────────────────────────────────────────────────────────────────────────

    sealed class VoiceRouteResult {
        /**
         * An agent handled the request. The calling voice session owns TTS output.
         */
        data class Handled(val spokenText: String, val agentId: String) : VoiceRouteResult()

        /**
         * No agent matched. LiveVoiceService should emit [text] to
         * [com.airi.assistant.core.ServiceLocator.voiceTranscriptBus] for LLM fallback.
         */
        object Fallback : VoiceRouteResult()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Route [transcript] through the sub-agent system.
     *
     * Suspend function — must be called from a coroutine.
     * [voiceSessionId] is used as the SubAgentContext session identifier so
     * MemoryAgent can correlate voice turns with the correct memory partition.
     */
    suspend fun route(transcript: String, voiceSessionId: Long): VoiceRouteResult {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) {
            Log.d(TAG, "AIRI VOICE_ROUTE_SKIP reason=blank")
            return VoiceRouteResult.Fallback
        }

        val ctx = buildContext(voiceSessionId)

        // ── Agent lookup ──────────────────────────────────────────────────────
        val agent = withContext(Dispatchers.IO) {
            SubAgentRegistry.route(trimmed, ctx)
        }

        if (agent == null) {
            Log.d(TAG, "AIRI VOICE_ROUTE_FALLBACK inputChars=${trimmed.length}")
            return VoiceRouteResult.Fallback
        }

        Log.i(TAG, "AIRI VOICE_ROUTE_MATCH agent=${agent.capability.agentId}")

        // Every voice transcript, including read-only agent intents, must enter
        // the canonical ChatViewModel → AgentLoop path. The old direct
        // orchestrator call bypassed request-scoped capability admission,
        // ToolDispatcher, trace ownership and the common cancellation/approval
        // contract. Agent lookup remains a routing hint; it is never authority
        // to execute a tool.
        Log.i(TAG, "AIRI VOICE_ROUTE_CANONICAL_REQUIRED agent=${agent.capability.agentId}")
        return VoiceRouteResult.Fallback
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Context construction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build [SubAgentContext] from live Android permission state +
     * runtime capability tokens (e.g. accessibility service connected).
     *
     * Voice sessions use STANDARD privacy level — the user initiated the
     * interaction explicitly via microphone. Agents that require cloud access
     * check [SubAgentContext.cloudAllowed] independently.
     */
    private fun buildContext(voiceSessionId: Long): SubAgentContext {
        val manifestPerms = listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        val granted = manifestPerms.filter { perm ->
            ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED
        }.toMutableList()

        // Runtime tokens: e.g. "airi_accessibility_enabled" from AccessibilityService
        granted.addAll(SubAgentRegistry.activeCapabilities())

        return SubAgentContext(
            sessionId                 = "voice-$voiceSessionId",
            userId                    = "voice_user",
            recentTurns               = emptyList(),
            worldState                = mapOf("input_modality" to "voice"),
            grantedPermissions        = granted,
            allowedTools              = listOf(
                "calendar_tool",
                "alarm_tool",
                "web_search",
                "notes_tool",
                "memory_store",
                "memory_recall",
                "accessibility_bridge"
            ),
            privacyLevel              = SubAgentContext.PRIVACY_STANDARD,
            remainingCloudTokenBudget = 20_000,
            timeoutMs                 = 20_000L,
            nestingDepth              = 0
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VoiceAgentRouter"
    }
}
