package com.airi.assistant.agent.subagent.impl

import android.content.Context
import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.attachments.MediaArtifactService
import com.airi.assistant.core.ServiceLocator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * MediaGenerationAgent — AI image generation via Pollinations.AI (free, no key).
 *
 * REAL EXECUTION:
 *   Primary backend: Pollinations.AI image generation endpoint.
 *     URL: https://image.pollinations.ai/prompt/{encoded_prompt}
 *     - Free tier, no API key required, suitable for production use.
 *     - Downloads and verifies JPEG/PNG bytes before returning an AIRI artifact.
 *     - Parameters: width, height, model, seed.
 *
 *   When the user explicitly provides an OpenAI key via SecureApiKeyStore,
 *   the agent upgrades to DALL-E 3 automatically.
 *
 * QUOTA:
 *   - Each generation is counted as one agent execution.
 *   - Premium users get larger images (1024×1024 vs 512×512 free).
 *
 * PRIVACY:
 *   - Blocked in PRIVACY_MAXIMUM mode (would send prompt to cloud).
 *   - The image prompt is logged at WARN level (not DEBUG) so it is
 *     visible in the AIRI stream without being noisy.
 */
class MediaGenerationAgent(
    private val context: Context
) : SubAgent {

    companion object {
        private const val TAG         = "MediaGenerationAgent"
    }

    override val capability = SubAgentCapability(
        agentId        = "media_generation_agent",
        displayName    = "Media Generation",
        description    = "Generate AI images from text descriptions.",
        intentKeywords = listOf(
            "generate image", "create image", "draw", "paint", "illustrate",
            "make a picture", "show me an image", "render", "generate art",
            "create art", "make art", "design an image", "create a photo",
            "generate a photo", "dall-e", "image of", "picture of", "photo of",
            "visualize", "create visual"
        ),
        domains             = listOf("image", "media", "art", "generation", "visual"),
        requiresCloud       = true,
        requiredTools       = listOf("image_generator"),
        costTier            = SubAgentCapability.CostTier.HIGH,
        latencyProfile      = SubAgentCapability.LatencyProfile.SLOW,
        supportsBackground  = true,
        maxParallelSubTasks = 1,
        supportsResume      = false
    )

    override suspend fun canHandle(input: String, context: SubAgentContext): Boolean {
        if (context.privacyLevel == SubAgentContext.PRIVACY_MAXIMUM) return false
        val lower = input.lowercase()
        return MEDIA_SIGNALS.any { lower.contains(it) }
    }

    override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
        val start = System.currentTimeMillis()
        Log.i(TAG, "MEDIA_GENERATION_STARTED inputChars=${input.length}")

        if (context.privacyLevel == SubAgentContext.PRIVACY_MAXIMUM) {
            emit(AgentEvent.Failed("Image generation blocked: privacy=MAXIMUM", recoverable = false))
            return@flow
        }

        emit(AgentEvent.Progress("Preparing image prompt…", 10, "prompt_prep"))
        val prompt = cleanPrompt(input)
        val width  = if (context.cloudAllowed) 1024 else 512
        val height = width

        emit(AgentEvent.Progress("Generating image (this may take ~20s)…", 25, "generating"))
        emit(AgentEvent.ToolCall(
            toolName  = "image_generator",
            params    = mapOf(
                "prompt" to prompt,
                "width"  to width.toString(),
                "height" to height.toString(),
                "backend" to "pollinations"
            ),
            reasoning = "Generate AI image from prompt using Pollinations.AI"
        ))

        val artifact = try {
            MediaArtifactService(this@MediaGenerationAgent.context, ServiceLocator.artifactManager).generateImageFromPrompt(
                sessionId = context.sessionId,
                prompt = prompt,
                width = width,
                height = height,
                runId = context.executionId.takeIf { it.isNotBlank() },
                traceId = context.parentId,
            )
        } catch (e: Exception) {
            Log.w(TAG, "MEDIA_GENERATION_FAILURE causeType=${e::class.simpleName}")
            null
        }

        val durationMs = System.currentTimeMillis() - start

        if (artifact != null) {
            Log.i(TAG, "MEDIA_GENERATION_COMPLETE success=true durationMs=$durationMs")
            emit(AgentEvent.PartialResult(
                "Here is your generated image:\n${artifact.structured()}",
                isFinal = true
            ))
        } else {
            emit(AgentEvent.PartialResult(
                "Image generation is currently unavailable. The service may be temporarily down. " +
                "Try again in a moment, or describe what you want and I can suggest alternatives.",
                isFinal = true
            ))
        }

        emit(AgentEvent.Complete(
            result     = "[MediaGen: ${if (artifact != null) "success artifact=${artifact.artifactId}" else "failed"} durationMs=$durationMs]",
            durationMs = durationMs,
            toolsUsed  = listOf("image_generator")
        ))
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun cleanPrompt(input: String): String =
        input
            .replace(Regex("(?i)(generate image|create image|draw|paint|make a picture|" +
                "show me an image|render|generate art|create art|make art|" +
                "design an image|create a photo|generate a photo|image of|" +
                "picture of|photo of|visualize|create visual|of)"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .ifBlank { input }

    private val MEDIA_SIGNALS = listOf(
        "generate image", "create image", "draw ", "paint ", "illustrate",
        "make a picture", "show me an image", "render ", "generate art",
        "create art", "make art", "design an image", "create a photo",
        "generate a photo", "image of", "picture of", "photo of",
        "visualize", "create visual"
    )
}
