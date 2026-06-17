package com.airi.assistant.execution.security

import android.content.Context
import android.util.Log
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.execution.CloudProvider

/**
 * Encrypted API key vault for cloud provider credentials.
 *
 * ## Storage
 * Delegates to [SecureStorage] which uses AndroidX Security Crypto
 * [EncryptedSharedPreferences] (AES256-SIV keys, AES256-GCM values).
 * Keys are NEVER stored in plaintext SharedPreferences, NEVER logged,
 * and NEVER included in crash reports.
 *
 * ## Key namespace
 * Uses the same `llm_key_{provider}` namespace as [SecureStorage.saveLlmKey]
 * so keys entered via the existing Settings → API Keys screen are immediately
 * available here without migration.
 *
 * Supported providers and their storage identifiers:
 *  - GEMINI       → "gemini"
 *  - OPENAI       → "openai"
 *  - ANTHROPIC    → "anthropic"
 *  - OPENROUTER   → "openrouter"
 *  - KIMI         → "kimi"
 *  - CUSTOM       → "custom"
 *
 * ## Usage
 * ```kotlin
 * val store = SecureApiKeyStore(context)
 * val key = store.getKey(CloudProvider.OPENAI) ?: error("No OpenAI key")
 * ```
 *
 * ## Security contract
 *  - [getKey] never logs the returned value.
 *  - [saveKey] scrubs leading/trailing whitespace before persisting.
 *  - [hasKey] is safe to call from any thread (reads only).
 *  - Keys are removed from memory as soon as the returned [String] is GC'd
 *    (no internal caching).
 */
class SecureApiKeyStore(context: Context) {

    private val storage = SecureStorage(context)

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Retrieve the API key for [provider].
     * Returns null if no key has been saved.
     * NEVER logs the returned value.
     */
    fun getKey(provider: CloudProvider): String? =
        storage.getLlmKey(provider.storageId)
            ?.takeIf { it.isNotBlank() }

    /**
     * True when a non-blank API key is stored for [provider].
     * Does not reveal the key value.
     */
    fun hasKey(provider: CloudProvider): Boolean =
        !storage.getLlmKey(provider.storageId).isNullOrBlank()

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Persist [key] for [provider] in encrypted storage.
     * Trims whitespace and removes the entry if [key] is blank.
     */
    fun saveKey(provider: CloudProvider, key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            clearKey(provider)
            return
        }
        storage.saveLlmKey(provider.storageId, trimmed)
        Log.i(TAG, "Saved key for ${provider.displayName} (${trimmed.length} chars, value hidden)")
    }

    /**
     * Remove the stored key for [provider].
     */
    fun clearKey(provider: CloudProvider) {
        storage.clearLlmKey(provider.storageId)
        Log.i(TAG, "Cleared key for ${provider.displayName}")
    }

    // ── Diagnostics (no key values exposed) ───────────────────────────────────

    /**
     * Returns a map of provider → has-key (boolean only — NO key values).
     * Safe to log.
     */
    fun keyPresenceMap(): Map<CloudProvider, Boolean> =
        CloudProvider.entries.associateWith { hasKey(it) }

    companion object {
        private const val TAG = "AIRI_SecureApiKeyStore"
    }
}

// ── Extension: map CloudProvider to SecureStorage key identifier ─────────────

private val CloudProvider.storageId: String get() = when (this) {
    CloudProvider.GEMINI     -> "gemini"
    CloudProvider.OPENAI     -> "openai"
    CloudProvider.ANTHROPIC  -> "anthropic"
    CloudProvider.OPENROUTER -> "openrouter"
    CloudProvider.KIMI       -> "kimi"
    CloudProvider.CUSTOM     -> "custom"
    CloudProvider.BRAVE      -> "brave_search"   // Brave Search API key
}
