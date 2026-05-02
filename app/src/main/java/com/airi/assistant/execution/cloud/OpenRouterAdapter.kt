package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.security.SecureApiKeyStore
import java.net.HttpURLConnection

/**
 * OpenRouter streaming adapter.
 *
 * Extends [OpenAIAdapter] because OpenRouter is fully OpenAI Chat Completions
 * compatible — only the base URL and two extra headers differ:
 *
 *   Base URL:  https://openrouter.ai/api/v1
 *   Extra headers:
 *     HTTP-Referer: https://airi.app     (app attribution — required by OpenRouter)
 *     X-Title: AIRI                      (display name in openrouter.ai dashboard)
 *
 * ## Model selection
 * The default model is `google/gemini-2.0-flash-001` which offers excellent
 * quality/cost ratio and is broadly available. The caller can override this
 * by constructing with a custom [model] parameter.
 *
 * ## API key
 * Uses the OPENROUTER slot in [SecureApiKeyStore]. Users enter their key in
 * Settings → API Keys → OpenRouter.
 *
 * ## Rate limits
 * OpenRouter enforces per-key and per-model rate limits. The [RetryPolicy]
 * in [CloudBackend] handles 429 responses with exponential back-off.
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
        const val DEFAULT_MODEL       = "google/gemini-2.0-flash-001"
    }
}
