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
 *   SubAgentRegistry.route()        ← keyword scoring + canHandle() gate
 *     ↓ (matched)
 *   ProductionAgentOrchestrator.executeSingle()   ← real tool calls
 *     ↓
 *   LiveVoiceService speaks the handled response through its session-owned TTS.
 *     ↓
 *   VoiceRouteResult.Handled(text, agentId)
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
    private val orchestrator: ProductionAgentOrchestrator
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

        // ── Execute via orchestrator ───────────────────────────────────────────
        return try {
            val result = withContext(Dispatchers.IO) {
                orchestrator.executeSingle(trimmed, ctx) { event ->
                    Log.v(TAG, "AIRI VOICE_AGENT_EVENT type=${event::class.simpleName}")
                }
            }

            when (result) {
                is ProductionAgentOrchestrator.ExecutionResult.Success -> {
                    val text = result.finalResult.trim()
                    if (text.isNotBlank()) {
                        Log.i(TAG, "AIRI VOICE_ROUTE_HANDLED " +
                                "agent=${agent.capability.agentId} " +
                                "chars=${text.length} " +
                                "durationMs=${result.durationMs}")
                        VoiceRouteResult.Handled(text, agent.capability.agentId)
                    } else {
                        // Agent returned empty result — fall through to LLM
                        Log.d(TAG, "AIRI VOICE_ROUTE_EMPTY_RESULT " +
                                "agent=${agent.capability.agentId} — fallback")
                        VoiceRouteResult.Fallback
                    }
                }

                is ProductionAgentOrchestrator.ExecutionResult.PartialFailure -> {
                    Log.w(TAG, "AIRI VOICE_ROUTE_PARTIAL_FAILURE " +
                            "errors=${result.taskErrors.size}")
                    VoiceRouteResult.Fallback
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "AIRI VOICE_ROUTE_EXCEPTION ${e.javaClass.simpleName}")
            VoiceRouteResult.Fallback
        }
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
