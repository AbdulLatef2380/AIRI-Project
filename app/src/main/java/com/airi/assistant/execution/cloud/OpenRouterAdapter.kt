package com.airi.assistant.execution.cloud

import com.airi.assistant.ai.QueryType
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.security.SecureApiKeyStore
import java.net.HttpURLConnection

/**
 * OpenRouter streaming adapter with task-based model selection.
 *
 * OpenRouter is OpenAI Chat Completions compatible. Model IDs are kept in one
 * catalog below and are selected from the request capabilities.
 *
 * The previous catalog used google/gemini-2.0-flash-exp:free. Google lists
 * Gemini 2.0 Flash as shut down on 2026-06-01, so that ID now produces a
 * provider 404/invalid-request error. The free route below was verified
 * against OpenRouter's live /api/v1/models catalog on 2026-09-20.
 */
class OpenRouterAdapter(
    keyStore: SecureApiKeyStore,
    override val model: String = DEFAULT_MODEL
) : OpenAIAdapter(
    keyStore = keyStore,
    provider = CloudProvider.OPENROUTER,
    baseUrl = BASE_URL,
    model = model
) {

    override val providerId: String = "openrouter"

    override fun applyExtraHeaders(conn: HttpURLConnection) {
        conn.setRequestProperty("HTTP-Referer", APP_REFERER)
        conn.setRequestProperty("X-Title", APP_TITLE)
    }

    companion object {
        private const val BASE_URL = "https://openrouter.ai/api/v1"
        private const val APP_REFERER = "https://airi.app"
        private const val APP_TITLE = "AIRI"

        // Current free models verified in OpenRouter's live model catalog.
        const val DEFAULT_MODEL = "qwen/qwen3.8-27b:free"
        const val MODEL_CODING = "cohere/north-mini-code:free"
        const val MODEL_REASONING = "z-ai/glm-5.2:free"
        const val MODEL_LONG_CONTEXT = DEFAULT_MODEL
        const val MODEL_VISION = DEFAULT_MODEL
        const val MODEL_MULTILINGUAL = DEFAULT_MODEL
        const val MODEL_FAST = DEFAULT_MODEL
        const val MODEL_QUALITY = DEFAULT_MODEL

        /** Select a current model for the request without returning retired IDs. */
        fun selectModel(request: ExecutionRequest): String {
            val candidates = when {
                request.requiresVision -> listOf(MODEL_VISION)
                request.requiresLongContext -> listOf(MODEL_LONG_CONTEXT)
                else -> {
                    val prompt = request.prompt.lowercase()
                    val isCodingPrompt = prompt.contains("```") ||
                        prompt.contains("function ") || prompt.contains("class ") ||
                        prompt.contains("def ") || prompt.contains("import ") ||
                        prompt.contains("code") || prompt.contains("debug") ||
                        prompt.contains("error:") || prompt.contains("kotlin") ||
                        prompt.contains("python") || prompt.contains("javascript") ||
                        request.queryType == QueryType.ACTION && prompt.contains("script")
                    when {
                        isCodingPrompt -> listOf(MODEL_CODING, DEFAULT_MODEL)
                        request.queryType == QueryType.ANALYTICAL -> listOf(MODEL_REASONING, DEFAULT_MODEL)
                        request.queryType == QueryType.SIMPLE && request.estimatedPromptTokens < 200 -> listOf(MODEL_FAST, DEFAULT_MODEL)
                        else -> listOf(MODEL_MULTILINGUAL)
                    }
                }
            }
            val live = OpenRouterModelRegistry.snapshot()
            if (live.isNotEmpty()) {
                candidates.firstOrNull { it in live }?.let { return it }
                live.firstOrNull { it.endsWith(":free") }?.let { return it }
            }
            return candidates.first()
        }

        fun modelLabel(modelId: String): String = when (modelId) {
            MODEL_CODING -> "North Mini Code"
            MODEL_REASONING -> "GLM 5.2"
            DEFAULT_MODEL -> "Qwen 3.8 27B"
            else -> modelId.substringAfterLast('/')
        }
    }
}
