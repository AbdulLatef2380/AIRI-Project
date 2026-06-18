package com.airi.assistant.connector.oauth

import android.util.Base64
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuthStateRegistry — per-process CSRF state token store for all OAuth flows.
 *
 * SECURITY CONTRACT:
 *  - Each token is 144-bit URL-safe Base64 (SecureRandom) — indistinguishable
 *    from random by an attacker who does not control the process.
 *  - Tokens are consumed exactly once ([consume] removes them). A second call
 *    with the same state returns null, preventing replay attacks.
 *  - Tokens expire after 5 minutes. An attacker intercepting a deep link and
 *    replaying it after expiry is denied.
 *  - The registry is in-process only (never persisted), so a fresh install
 *    starts with an empty store — no stale tokens survive app updates.
 *
 * USAGE PATTERN:
 *  1. [issue] before launching the OAuth browser intent — keep the returned
 *     state string. Include it in the `state` query param of the auth URL.
 *  2. [consume] in the deep-link handler. If it returns null or the connector
 *     id does not match, reject the callback and do not exchange the code.
 */
object OAuthStateRegistry {

    private const val TOKEN_EXPIRY_MS = 5 * 60 * 1_000L  // 5 minutes
    private val rng = SecureRandom()

    private data class Entry(
        val connectorId: String,
        val issuedAtMs:  Long = System.currentTimeMillis()
    )

    private val store = ConcurrentHashMap<String, Entry>()

    /**
     * Issue a fresh CSRF state token for [connectorId].
     * Returns a 144-bit URL-safe Base64 token.
     */
    fun issue(connectorId: String): String {
        val bytes = ByteArray(18).also { rng.nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        // Eagerly evict any expired entries on each issue (bounded store size)
        evictExpired()
        store[token] = Entry(connectorId)
        return token
    }

    /**
     * Validate and consume [state]. Returns the [connectorId] it was issued
     * for, or null if the token is unknown, expired, or already consumed.
     *
     * Thread-safe: the ConcurrentHashMap.remove() is atomic.
     */
    fun consume(state: String): String? {
        val entry = store.remove(state) ?: return null
        val ageMs  = System.currentTimeMillis() - entry.issuedAtMs
        return if (ageMs <= TOKEN_EXPIRY_MS) entry.connectorId else null
    }

    /**
     * Check whether a state token is currently pending (without consuming it).
     * Used for debugging / diagnostics only — do NOT use this as the validation path.
     */
    fun isPending(state: String): Boolean {
        val entry = store[state] ?: return false
        return (System.currentTimeMillis() - entry.issuedAtMs) <= TOKEN_EXPIRY_MS
    }

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        store.entries.removeAll { (_, v) -> now - v.issuedAtMs > TOKEN_EXPIRY_MS }
    }
}
