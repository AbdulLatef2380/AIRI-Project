package com.airi.assistant.execution.cloud

import android.content.Context
import android.util.Log
import com.airi.assistant.ai.remote.RemoteModelRegistry
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.security.SecureApiKeyStore

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
            CloudProvider.GEMINI     -> GeminiAdapter(keyStore)
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
            Log.i(TAG, "CUSTOM: using RemoteModel '${remote.name}' at ${remote.serverUrl.take(40)}")
            return object : OpenAIAdapter(keyStore, CloudProvider.CUSTOM, remote.serverUrl, remote.name) {
                override val isAvailable: Boolean get() = true

                override suspend fun streamGenerate(
                    request: com.airi.assistant.execution.ExecutionRequest,
                    onToken: suspend (String) -> Unit,
                    onUsage: suspend (Int, Int) -> Unit
                ): CloudProviderAdapter.AdapterResult {
                    when {
                        remote.apiKey.isNotBlank() -> {
                            // Fix B / legacy migration: always write the active remote's key so
                            // it overwrites any previously-stored sentinel or stale key. Ensures
                            // that switching between providers (e.g. Groq → custom server) always
                            // uses the correct credential without requiring an app restart.
                            keyStore.saveKey(CloudProvider.CUSTOM, remote.apiKey)
                            Log.i(TAG, "CUSTOM: key written/updated in SecureApiKeyStore")
                        }
                        !keyStore.hasKey(CloudProvider.CUSTOM) -> {
                            // Fix B: LOCAL_SERVER providers (Ollama, LM Studio) and any
                            // keyless custom server have a blank apiKey by design. OpenAIAdapter
                            // requires a non-null key from SecureApiKeyStore or it returns an
                            // UNAUTHORIZED failure before opening any socket. We write a sentinel
                            // value so the null-guard passes. Local servers universally ignore the
                            // Authorization header, so "Bearer $NO_AUTH_SENTINEL" is harmless.
                            // The sentinel is overwritten immediately if a real key is provided later.
                            keyStore.saveKey(CloudProvider.CUSTOM, NO_AUTH_SENTINEL)
                            Log.i(TAG, "CUSTOM: LOCAL_SERVER or keyless — sentinel written for null-guard bypass")
                        }
                        else -> {
                            // SecureApiKeyStore already holds a key (either a real key from a
                            // previous activateRemoteModel call, or the sentinel from a prior
                            // local-server session). Reuse it — local servers ignore real keys
                            // in the Authorization header, so this path is always safe.
                            Log.d(TAG, "CUSTOM: reusing existing SecureApiKeyStore entry")
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

    private fun logPresence(provider: CloudProvider, keyStore: SecureApiKeyStore, context: Context) {
        val has = when (provider) {
            CloudProvider.CUSTOM -> RemoteModelRegistry.getActive() != null || keyStore.hasKey(provider)
            else                 -> keyStore.hasKey(provider)
        }
        Log.d(TAG, "create: provider=${provider.name} hasKey=$has")
    }
}
