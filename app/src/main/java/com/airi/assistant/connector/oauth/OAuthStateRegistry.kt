package com.airi.assistant.connector.oauth

import java.util.Base64
import java.security.MessageDigest
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

    private const val TOKEN_EXPIRY_MS = 5 * 60 * 1_000L
    private val rng = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    data class ConsumedRequest(
        val connectorId: String,
        val codeVerifier: String?,
        val requestedScopes: Set<String> = emptySet(),
        /** Optional owner binding (for example, a validated project id). */
        val ownerBinding: String? = null,
    )

    /** Non-secret routing metadata only; callback handlers must still consume and validate the state. */
    data class PendingRoute(val connectorId: String, val ownerBinding: String?)

    data class PkceAuthorization(
        val state: String,
        val codeChallenge: String
    )

    private data class Entry(
        val connectorId: String,
        val codeVerifier: String? = null,
        val requestedScopes: Set<String> = emptySet(),
        val ownerBinding: String? = null,
        val issuedAtMs: Long = System.currentTimeMillis()
    )

    private val store = ConcurrentHashMap<String, Entry>()

    /**
     * Issue a fresh CSRF state token for [connectorId].
     * Returns a 144-bit URL-safe Base64 token.
     */
    fun issue(connectorId: String): String = issueEntry(connectorId).first

    /** Issue a one-use state bound to scopes for confidential OAuth flows. */
    fun issueWithScopes(connectorId: String, requestedScopes: Set<String>): String =
        issueEntry(connectorId, requestedScopes = requestedScopes).first

    fun issuePkce(
        connectorId: String,
        requestedScopes: Set<String> = emptySet(),
        ownerBinding: String? = null,
    ): PkceAuthorization {
        val verifierBytes = ByteArray(48).also { rng.nextBytes(it) }
        val verifier = urlEncoder.encodeToString(verifierBytes)
        val state = issueEntry(connectorId, verifier, requestedScopes, ownerBinding).first
        val challenge = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return PkceAuthorization(
            state = state,
            codeChallenge = urlEncoder.encodeToString(challenge)
        )
    }

    private fun issueEntry(
        connectorId: String,
        codeVerifier: String? = null,
        requestedScopes: Set<String> = emptySet(),
        ownerBinding: String? = null,
    ): Pair<String, String?> {
        require(connectorId.isNotBlank()) { "connectorId must not be blank" }
        val bytes = ByteArray(18).also { rng.nextBytes(it) }
        val token = urlEncoder.encodeToString(bytes)
        evictExpired()
        store[token] = Entry(connectorId, codeVerifier, requestedScopes.toSet(), ownerBinding)
        return token to codeVerifier
    }

    /**
     * Validate and consume [state]. Returns the [connectorId] it was issued
     * for, or null if the token is unknown, expired, or already consumed.
     *
     * Thread-safe: the ConcurrentHashMap.remove() is atomic.
     */
    fun consume(state: String): String? = consumeRequest(state)?.connectorId

    fun consumeRequest(state: String): ConsumedRequest? {
        val entry = store.remove(state) ?: return null
        val ageMs = System.currentTimeMillis() - entry.issuedAtMs
        return if (ageMs in 0..TOKEN_EXPIRY_MS) {
            ConsumedRequest(entry.connectorId, entry.codeVerifier, entry.requestedScopes, entry.ownerBinding)
        } else {
            null
        }
    }

    /**
     * Check whether a state token is currently pending (without consuming it).
     * Used for debugging / diagnostics only — do NOT use this as the validation path.
     */
    fun isPending(state: String): Boolean {
        val entry = store[state] ?: return false
        return (System.currentTimeMillis() - entry.issuedAtMs) in 0..TOKEN_EXPIRY_MS
    }

    /**
     * Returns only connector and owner metadata so the activity can choose the
     * correct callback handler without consuming the one-use PKCE request.
     * This is not a validation path; the selected handler must call consumeRequest.
     */
    fun pendingRoute(state: String): PendingRoute? {
        if (state.isBlank()) return null
        val entry = store[state] ?: return null
        return if ((System.currentTimeMillis() - entry.issuedAtMs) in 0..TOKEN_EXPIRY_MS) {
            PendingRoute(entry.connectorId, entry.ownerBinding)
        } else {
            null
        }
    }

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        store.entries.removeAll { (_, v) -> now - v.issuedAtMs !in 0..TOKEN_EXPIRY_MS }
    }
}
