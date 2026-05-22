package com.airi.assistant.oauth

import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuthStateRegistry — per-process CSRF-protection store for OAuth deep-link
 * callbacks (airi://oauth/callback?code=...&state=...).
 *
 * ## Why
 * The previous flow accepted any incoming `state` value and emitted an
 * `OAuthCallbackReceived` event without validation. A malicious app/website
 * could trigger callbacks from arbitrary providers and cause the integrations
 * VM to consume forged codes. This is the standard CSRF gap that OAuth's
 * `state` parameter is designed to close — it just wasn't being checked.
 *
 * ## Lifecycle
 *   1. The flow that initiates OAuth (e.g. opening a CustomTab to GitHub)
 *      calls [issue] to generate a fresh, cryptographically-random state token
 *      tied to a `provider` id.
 *   2. The provider redirects back to airi://oauth/callback?state=…&code=…
 *      and `MainActivity.onNewIntent` receives it.
 *   3. MainActivity calls [consume] with the inbound state.
 *      • If the state was issued and has not been used: returns the bound
 *        provider id and the entry is consumed (single-use).
 *      • Otherwise: returns null and the callback is rejected.
 *
 * ## Safety
 *   • States are URL-safe base64, 24 chars (~144 bits) — replay-resistant.
 *   • Entries auto-expire after [TTL_MS] to bound memory usage.
 *   • The map is bounded by [MAX_PENDING] — when full, the oldest entry is
 *     evicted before a new one is issued.
 *   • Single-use: once a state is consumed it cannot be replayed.
 *
 * ## Thread safety
 * All mutations go through a `ConcurrentHashMap`. Safe for concurrent
 * issue/consume calls from any dispatcher.
 *
 * ## Out of scope
 * Persistence across process death. If the user starts an OAuth flow and
 * AIRI is killed before the redirect, the callback will fail validation. This
 * is the same fail-closed behavior most production OAuth clients use; the
 * user can simply retry. Persisting issued states across process restarts
 * would weaken the CSRF guarantee unless paired with a per-launch nonce.
 */
object OAuthStateRegistry {

    private const val TAG = "OAuthStateRegistry"
    private const val TTL_MS = 5L * 60 * 1000           // 5 min
    private const val MAX_PENDING = 32

    private data class Entry(val provider: String, val createdAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val rng = SecureRandom()

    /**
     * Issue a fresh single-use state token for [provider] (e.g. "github",
     * "google", "slack"). The caller must include this string verbatim as the
     * OAuth `state=` parameter when opening the provider's authorization URL.
     */
    fun issue(provider: String): String {
        prune()
        if (entries.size >= MAX_PENDING) {
            // Evict oldest.
            val oldest = entries.entries.minByOrNull { it.value.createdAtMs }
            oldest?.let { entries.remove(it.key) }
        }
        val bytes = ByteArray(18).also { rng.nextBytes(it) }
        val state = android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        entries[state] = Entry(provider, System.currentTimeMillis())
        Log.d(TAG, "Issued state for provider=$provider (${state.take(6)}…)")
        return state
    }

    /**
     * Validate and consume an incoming [state]. Returns the bound provider id
     * on success, or null if the state was unknown, expired, or already used.
     *
     * Always single-use — even on success, the state is removed.
     */
    fun consume(state: String?): String? {
        if (state.isNullOrBlank()) return null
        prune()
        val entry = entries.remove(state) ?: run {
            Log.w(TAG, "Rejected unknown OAuth state (${state.take(6)}…)")
            return null
        }
        if (System.currentTimeMillis() - entry.createdAtMs > TTL_MS) {
            Log.w(TAG, "Rejected expired OAuth state (provider=${entry.provider})")
            return null
        }
        Log.d(TAG, "Consumed state for provider=${entry.provider}")
        return entry.provider
    }

    /** Drop expired entries. */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        entries.entries.removeAll { it.value.createdAtMs < cutoff }
    }
}
