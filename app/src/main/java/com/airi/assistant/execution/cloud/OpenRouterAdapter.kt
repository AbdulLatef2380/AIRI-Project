package com.airi.assistant.execution.cloud

import com.airi.assistant.ai.QueryType
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.security.SecureApiKeyStore
import java.net.HttpURLConnection

/**
 * OpenRouter streaming adapter with intelligent task-based model selection.
 *
 * Extends [OpenAIAdapter] — OpenRouter is fully OpenAI Chat Completions
 * compatible (same base URL + two extra headers).
 *
 * ## Intelligent routing
 * [selectModel] maps the incoming request's [QueryType], capability flags,
 * and context length to the best free/cheap OpenRouter model. This is
 * AIRI's primary cloud intelligence layer — the user never picks models
 * manually; AIRI chooses based on the task.
 *
 * Model priority table (all available on OpenRouter free tier or low-cost):
 *
 *   CODING         → deepseek/deepseek-coder   (best code quality)
 *   REASONING      → deepseek/deepseek-r1:free  (chain-of-thought)
 *   LONG CONTEXT   → google/gemini-2.0-flash-exp:free (1M ctx window)
 *   VISION         → google/gemini-2.0-flash-exp:free (multimodal)
 *   ARABIC/GENERAL → qwen/qwen-2.5-72b-instruct  (strong multilingual)
 *   FAST/SIMPLE    → meta-llama/llama-3.3-8b-instruct:free (fastest free)
 *   DEFAULT        → google/gemini-2.0-flash-exp:free
 *
 * ## API key
 * Uses the OPENROUTER slot in [SecureApiKeyStore].
 */
class OpenRouterAdapter(
    keyStore: SecureApiKeyStore,
    override val model: String = DEFAULT_MODEL
) : OpenAIAdapter(
    keyStore  = keyStore,
    provider  = CloudProvider.OPENROUTER,
    baseUrl   = BASE_URL,
    model     = model
) {

    override val providerId: String = "openrouter"

    override fun applyExtraHeaders(conn: HttpURLConnection) {
        conn.setRequestProperty("HTTP-Referer", APP_REFERER)
        conn.setRequestProperty("X-Title",      APP_TITLE)
    }

    companion object {
        private const val BASE_URL    = "https://openrouter.ai/api/v1"
        private const val APP_REFERER = "https://airi.app"
        private const val APP_TITLE   = "AIRI"
        const val DEFAULT_MODEL       = "google/gemini-2.0-flash-exp:free"

        // ── Free / low-cost model catalog on OpenRouter ──────────────────────
        // NOTE: model IDs must include :free for zero-cost OpenRouter routing.
        // Verify IDs at https://openrouter.ai/models before updating.
        const val MODEL_CODING        = "deepseek/deepseek-coder-v2:free"
        const val MODEL_REASONING     = "deepseek/deepseek-r1:free"
        const val MODEL_LONG_CONTEXT  = "google/gemini-2.0-flash-exp:free"
        const val MODEL_VISION        = "google/gemini-2.0-flash-exp:free"
        const val MODEL_MULTILINGUAL  = "qwen/qwen-2.5-72b-instruct:free"
        // llama-3.3-8b-instruct:free was removed from OpenRouter (HTTP 404).
        // Using llama-3.1-8b-instruct:free which is a confirmed current free endpoint.
        const val MODEL_FAST          = "meta-llama/llama-3.1-8b-instruct:free"
        const val MODEL_QUALITY       = "google/gemini-2.0-flash-exp:free"

        /**
         * Select the best OpenRouter model for an [ExecutionRequest].
         *
         * This is AIRI's task-to-model intelligence. Called by [CloudAdapterFactory]
         * when constructing an OpenRouterAdapter for a specific request, rather than
         * using the default model for every request.
         *
         * Rules (evaluated in priority order):
         *  1. Vision capability required        → gemini-flash (only multimodal free model)
         *  2. Long context (>8k tokens)         → gemini-flash (1M ctx window)
         *  3. Coding task                       → deepseek-coder
         *  4. Deep reasoning/analytical         → deepseek-r1 (chain-of-thought)
         *  5. Simple/fast response              → llama-3.3-8b (fastest free)
         *  6. Multilingual / Arabic detected    → qwen-2.5-72b
         *  7. Default                           → gemini-flash
         */
        fun selectModel(request: ExecutionRequest): String {
            // Rule 1: vision
            if (request.requiresVision) return MODEL_VISION

            // Rule 2: long context.
            // Phase A1: rely solely on request.requiresLongContext, which is set by
            // AgentLoop using contextBudget.longContextThreshold (= nCtx / 2).
            // The former hardcoded "> 4000" threshold is removed — it was a fixed
            // constant that ignored the loaded model's actual nCtx and could route to
            // the cloud unnecessarily on 8K+ models where 4000 tokens is a small prompt.
            if (request.requiresLongContext) {
                return MODEL_LONG_CONTEXT
            }

            // Rule 3: coding
            val prompt = request.prompt.lowercase()
            val isCodingPrompt = prompt.contains("```") ||
                prompt.contains("function ") ||
                prompt.contains("class ") ||
                prompt.contains("def ") ||
                prompt.contains("import ") ||
                prompt.contains("code") ||
                prompt.contains("debug") ||
                prompt.contains("error:") ||
                prompt.contains("kotlin") ||
                prompt.contains("python") ||
                prompt.contains("javascript") ||
                request.queryType == QueryType.ACTION && prompt.contains("script")

            if (isCodingPrompt) return MODEL_CODING

            // Rule 4: deep reasoning
            if (request.queryType == QueryType.ANALYTICAL) return MODEL_REASONING

            // Rule 5: simple/short — use fastest free model
            if (request.queryType == QueryType.SIMPLE &&
                request.estimatedPromptTokens < 200) return MODEL_FAST

            // Rule 6: Arabic / multilingual detected
            val hasArabic = request.prompt.any { c ->
                c.code in 0x0600..0x06FF || c.code in 0x0750..0x077F
            }
            if (hasArabic) return MODEL_MULTILINGUAL

            // Rule 7: default
            return DEFAULT_MODEL
        }

        /**
         * Human-readable label for a model ID — used in the Library screen
         * and execution diagnostics.
         */
        fun modelLabel(modelId: String): String = when (modelId) {
            MODEL_CODING       -> "DeepSeek Coder V2"
            MODEL_REASONING    -> "DeepSeek R1"
            MODEL_FAST         -> "Llama 3.1 8B"
            MODEL_MULTILINGUAL -> "Qwen 2.5 72B"
            DEFAULT_MODEL      -> "Gemini 2.0 Flash"
            else               -> modelId.substringAfterLast("/")
        }
    }
}
