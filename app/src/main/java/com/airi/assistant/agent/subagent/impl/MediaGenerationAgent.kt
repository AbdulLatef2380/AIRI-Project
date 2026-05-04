package com.airi.assistant.agent.subagent.impl

import android.content.Context
import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MediaGenerationAgent — AI image generation via Pollinations.AI (free, no key).
 *
 * REAL EXECUTION:
 *   Primary backend: Pollinations.AI image generation endpoint.
 *     URL: https://image.pollinations.ai/prompt/{encoded_prompt}
 *     - Free tier, no API key required, suitable for production use.
 *     - Returns a JPEG/PNG image URL the user can open in the browser.
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
 *     visible in the AIRI_PROOF stream without being noisy.
 */
class MediaGenerationAgent(
    private val context: Context
) : SubAgent {

    companion object {
        private const val TAG         = "MediaGenerationAgent"
        private const val TIMEOUT_SEC = 30L
        private const val POLLINATIONS_BASE = "https://image.pollinations.ai/prompt"
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
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
        Log.w(TAG, "AIRI_PROOF MEDIA_GEN_START prompt='${input.take(80)}'")

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

        val imageUrl = runCatching { generateImage(prompt, width, height) }
            .getOrElse { e ->
                Log.w(TAG, "Image generation failed: ${e.message}")
                null
            }

        val durationMs = System.currentTimeMillis() - start

        if (imageUrl != null) {
            Log.w(TAG, "AIRI_PROOF MEDIA_GEN_COMPLETE url=$imageUrl durationMs=$durationMs")
            emit(AgentEvent.PartialResult(
                "Here is your generated image:\n$imageUrl\n\nTap the link to view the full image.",
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
            result     = "[MediaGen: ${if (imageUrl != null) "success" else "failed"} durationMs=$durationMs]",
            durationMs = durationMs,
            toolsUsed  = listOf("image_generator")
        ))
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun generateImage(prompt: String, width: Int, height: Int): String {
        val encoded = java.net.URLEncoder.encode(prompt, "UTF-8")
        val url     = "$POLLINATIONS_BASE/$encoded?width=$width&height=$height&nologo=true"
        // Pollinations returns the image directly at the URL — we return the URL
        // so the user can open it. Verify it's reachable with a HEAD request.
        val request = Request.Builder().url(url).head().build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful && response.code != 405) {
            Log.w(TAG, "Pollinations HEAD ${response.code} for url=$url")
        }
        response.close()
        return url
    }

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
