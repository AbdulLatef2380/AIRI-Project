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
     * Create the best adapter for [provider].
     *
     * Returns null only if:
     *  - No API key is available AND
     *  - The provider has no fallback (Custom with no RemoteModel configured)
     *
     * In all other cases a non-null adapter is returned; availability is
     * checked via [CloudProviderAdapter.isAvailable] at call time.
     */
    fun create(
        provider: CloudProvider,
        context:  Context
    ): CloudProviderAdapter {
        val keyStore = SecureApiKeyStore(context)
        logPresence(provider, keyStore, context)

        return when (provider) {
            CloudProvider.GEMINI     -> GeminiAdapter(keyStore)
            CloudProvider.OPENAI     -> OpenAIAdapter(keyStore, CloudProvider.OPENAI)
            CloudProvider.ANTHROPIC  -> AnthropicAdapter(keyStore)
            CloudProvider.OPENROUTER -> OpenRouterAdapter(keyStore)
            CloudProvider.KIMI       -> OpenAIAdapter(keyStore, CloudProvider.KIMI)
            CloudProvider.CUSTOM     -> buildCustomAdapter(keyStore, context)
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
            // Legacy path — key from RemoteModelRegistry
            Log.i(TAG, "CUSTOM: using RemoteModel '${remote.name}' at ${remote.serverUrl.take(40)}")
            return object : OpenAIAdapter(keyStore, CloudProvider.CUSTOM, remote.serverUrl, remote.name) {
                override val isAvailable: Boolean get() = true
                // Override streamGenerate to inject the legacy API key directly
                // since SecureApiKeyStore may not have a CUSTOM key yet.
                override suspend fun streamGenerate(
                    request:  com.airi.assistant.execution.ExecutionRequest,
                    onToken:  suspend (String) -> Unit,
                    onUsage:  suspend (Int, Int) -> Unit
                ): CloudProviderAdapter.AdapterResult {
                    // Temporarily write the legacy key to the secure store so
                    // the parent adapter can read it — this migrates old keys
                    // to encrypted storage transparently.
                    if (!keyStore.hasKey(CloudProvider.CUSTOM) && remote.apiKey.isNotBlank()) {
                        keyStore.saveKey(CloudProvider.CUSTOM, remote.apiKey)
                        Log.i(TAG, "CUSTOM: migrated legacy API key to SecureApiKeyStore")
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
