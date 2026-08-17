package com.airi.assistant.execution.cloud

import android.content.Context
import android.util.Log
import com.airi.assistant.ai.remote.RemoteModelRegistry
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.security.SecureApiKeyStore
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Factory that constructs the correct [CloudProviderAdapter] for a given
 * [CloudProvider] preference.
 *
 * ## Key resolution order
 * 1. [SecureApiKeyStore] (encrypted EncryptedSharedPreferences) — preferred
 * 2. [RemoteModelRegistry.getActive().apiKey] — legacy fallback (plaintext)
 *    Used transparently to preserve backward compatibility with keys that
 *    were entered before the encrypted store was introduced.
 *
 * The factory never exposes key values in logs. It only reports presence.
 *
 * ## Custom endpoints
 * [CloudProvider.CUSTOM] uses [RemoteModelRegistry.getActive()] for both
 * the base URL and the API key, delegating to [OpenAIAdapter] (the most
 * common custom server format).
 *
 * ## Caching
 * Adapters are lightweight (no coroutine scope, no state) so a new instance
 * can be created per-request. [create] is idempotent and cheap.
 */
object CloudAdapterFactory {

    private const val TAG = "AIRI_CloudAdapterFactory"

    /**
     * Sentinel stored in SecureApiKeyStore.CUSTOM for LOCAL_SERVER providers
     * (Ollama, LM Studio) that require no API key. OpenAIAdapter's null-key
     * guard passes because this value is non-blank. The Authorization header
     * sent is "Bearer no-auth-local-server". Ollama, LM Studio, and standard
     * OpenAI-compatible local servers ignore the Authorization header when
     * running without auth enabled.
     */
    private const val NO_AUTH_SENTINEL = "no-auth-local-server"

    /**
     * Create the best adapter for [provider].
     *
     * For [CloudProvider.OPENROUTER], the optional [request] parameter
     * enables task-based model selection via [OpenRouterAdapter.selectModel].
     * When null, the default model is used (safe for providers that don't
     * support per-request model selection).
     */
    fun create(
        provider: CloudProvider,
        context:  Context,
        request:  com.airi.assistant.execution.ExecutionRequest? = null
    ): CloudProviderAdapter {
        val keyStore = SecureApiKeyStore(context)
        logPresence(provider, keyStore, context)

        return when (provider) {
            CloudProvider.GEMINI     -> {
                // BUG-FIX: RemoteModelRegistry is a shared cross-provider registry.
                // Using RemoteModelRegistry.getActive()?.name here caused cross-provider
                // contamination: if the user had Groq ("llama-3.3-70b-versatile") active
                // before switching to Gemini, GeminiAdapter received "llama-3.3-70b-versatile"
                // as its model — causing Gemini API 404 manifesting as spurious timeouts.
                //
                // Resolution: read model name from EmbeddedProviderConfig which is scoped
                // to the provider type, not from the shared RemoteModelRegistry.
                val geminiModel = EmbeddedProviderConfig.getActiveProvider(context)
                    ?.takeIf { it.provider == CloudProvider.GEMINI }
                    ?.defaultModel
                    ?: "gemini-2.0-flash"
                Log.d(TAG, "GEMINI: model=$geminiModel (from EmbeddedProviderConfig)")
                GeminiAdapter(keyStore, geminiModel)
            }
            CloudProvider.OPENAI     -> OpenAIAdapter(keyStore, CloudProvider.OPENAI)
            CloudProvider.ANTHROPIC  -> AnthropicAdapter(keyStore)
            CloudProvider.OPENROUTER -> {
                // Intelligent model selection: pick the best OpenRouter model
                // for this specific request's task type and capabilities.
                // Falls back to DEFAULT_MODEL when request is null.
                val selectedModel = request?.let { OpenRouterAdapter.selectModel(it) }
                    ?: OpenRouterAdapter.DEFAULT_MODEL
                Log.i(TAG, "OPENROUTER: selected model=$selectedModel " +
                    "queryType=${request?.queryType} vision=${request?.requiresVision}")
                OpenRouterAdapter(keyStore, selectedModel)
            }
            CloudProvider.KIMI       -> OpenAIAdapter(keyStore, CloudProvider.KIMI)
            CloudProvider.CUSTOM     -> buildCustomAdapter(keyStore, context)
            CloudProvider.BRAVE      -> throw IllegalArgumentException(
                "BRAVE is a search API key, not an LLM provider. Use SearchTool instead of CloudAdapterFactory."
            )
        }
    }

    /**
     * Create adapters for ALL providers that currently have a key configured.
     * Used by diagnostics to report key presence.
     */
    fun availableProviders(context: Context): List<CloudProvider> {
        val keyStore = SecureApiKeyStore(context)
        return CloudProvider.entries.filter { provider ->
            when (provider) {
                CloudProvider.CUSTOM -> RemoteModelRegistry.getActive() != null
                else                 -> keyStore.hasKey(provider)
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun buildCustomAdapter(keyStore: SecureApiKeyStore, context: Context): CloudProviderAdapter {
        val remote = RemoteModelRegistry.getActive()
        if (remote != null) {
            Log.i(TAG, "CUSTOM_REMOTE_MODEL_CONFIGURED")
            return object : OpenAIAdapter(keyStore, CloudProvider.CUSTOM, remote.serverUrl, remote.name) {
                override val isAvailable: Boolean get() = true

                // Patch 3B: runtime model discovery for LOCAL_SERVER providers.
                // effectiveModel starts as remote.name (= config.defaultModel after Fix C,
                // e.g. "llama3.2" for Ollama, "local-model" for LM Studio ≤0.2.x). On
                // every keyless request we query GET /v1/models to get the actual loaded
                // model ID. This overrides stale catalog placeholders automatically and
                // silently resolves the LM Studio "local-model" issue (RC-6).
                // The property is instance-scoped; adapters are recreated per-request
                // (factory KDoc), so discovery re-runs per request. The GET /v1/models
                // call to localhost typically completes in < 10 ms.
                private var effectiveModel: String = remote.name
                override val model: String get() = effectiveModel

                override suspend fun streamGenerate(
                    request: com.airi.assistant.execution.ExecutionRequest,
                    onToken: suspend (String) -> Unit,
                    onUsage: suspend (Int, Int) -> Unit
                ): CloudProviderAdapter.AdapterResult {
                    // ── Key handling (Fix B) ──────────────────────────────────────────
                    when {
                        remote.apiKey.isNotBlank() -> {
                            // Fix B / legacy migration: always write the active remote's key so
                            // it overwrites any previously-stored sentinel or stale key. Ensures
                            // that switching between providers (e.g. Groq → custom server) always
                            // uses the correct credential without requiring an app restart.
                            keyStore.saveKey(CloudProvider.CUSTOM, remote.apiKey)
                            Log.i(TAG, "CUSTOM: key written/updated in SecureApiKeyStore")
                        }
                        else -> {
                            // A keyless active endpoint must never inherit a credential that was
                            // saved for an earlier custom endpoint. The sentinel preserves the
                            // adapter's non-blank-key contract without disclosing that credential.
                            keyStore.saveKey(CloudProvider.CUSTOM, NO_AUTH_SENTINEL)
                            Log.i(TAG, "CUSTOM: keyless endpoint configured")
                        }
                    }
                    // ── Patch 3B: model discovery for LOCAL_SERVER providers ───────────
                    // When the server has no API key it is a LOCAL_SERVER (Ollama, LM
                    // Studio). Discover the first currently-loaded model via GET /v1/models
                    // and use it in the request, overriding the catalog placeholder.
                    // Falls back to remote.name (catalog defaultModel) on failure.
                    if (remote.apiKey.isBlank()) {
                        val discovered = discoverFirstModel(remote.serverUrl)
                        if (discovered != null) {
                            if (discovered != effectiveModel) {
                                Log.i(TAG, "CUSTOM: model discovery → '$discovered' (was '$effectiveModel')")
                            }
                            effectiveModel = discovered
                        } else {
                            Log.d(TAG, "CUSTOM: model discovery unavailable — using fallback '$effectiveModel'")
                        }
                    }
                    return super.streamGenerate(request, onToken, onUsage)
                }
            }
        }
        // No remote configured — return an unavailable stub
        Log.w(TAG, "CUSTOM: no RemoteModel configured")
        return object : OpenAIAdapter(keyStore, CloudProvider.CUSTOM) {
            override val isAvailable: Boolean get() = false
        }
    }

    /**
     * Patch 3B: Query the OpenAI-compatible `/v1/models` endpoint to discover
     * the first model currently loaded on a local server.
     *
     * Used by [buildCustomAdapter] for LOCAL_SERVER providers (Ollama, LM Studio)
     * whose loaded model ID is not known at catalog definition time. Returns the
     * first `id` field found in the `data` array of the standard response:
     * ```json
     * {"object":"list","data":[{"id":"llama-3.2-3b-instruct","object":"model"},…]}
     * ```
     * Returns `null` if the endpoint is unreachable, times out, or contains no
     * model entries. The caller falls back to the catalog's `defaultModel` on null.
     *
     * Connect and read are each bounded to 3 s; the call runs on [Dispatchers.IO].
     */
    private suspend fun discoverFirstModel(baseUrl: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL("$baseUrl/models").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3_000
                    readTimeout    = 3_000
                    requestMethod  = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code !in 200..299) { conn.disconnect(); return@runCatching null }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                // Minimal hand-rolled JSON parser: find the first "id" value inside
                // the "data" array. Searches past "data" to skip the top-level
                // "object":"list" key which also contains an "id"-like structure.
                val dataIdx = body.indexOf("\"data\"")
                val from    = if (dataIdx >= 0) dataIdx else 0
                val idIdx   = body.indexOf("\"id\"", from)
                if (idIdx < 0) return@runCatching null
                val colon = body.indexOf(":", idIdx)
                if (colon < 0) return@runCatching null
                val after = body.substring(colon + 1).trimStart()
                if (!after.startsWith("\"")) return@runCatching null
                var i = 1
                val sb = StringBuilder()
                while (i < after.length && after[i] != '"') {
                    if (after[i] == '\\') i++
                    if (i < after.length) sb.append(after[i++])
                }
                sb.toString().takeIf { it.isNotBlank() }
            }.getOrNull()
        }

    private fun logPresence(provider: CloudProvider, keyStore: SecureApiKeyStore, context: Context) {
        val has = when (provider) {
            CloudProvider.CUSTOM -> RemoteModelRegistry.getActive() != null || keyStore.hasKey(provider)
            else                 -> keyStore.hasKey(provider)
        }
        Log.d(TAG, "create: provider=${provider.name} hasKey=$has")
    }
}
