package com.airi.assistant.integrity

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * PlayIntegrityVerifier — thin wrapper around Google Play Integrity.
 *
 * ──  hardening ──────────────────────────────────────────────────────
 * Previous behavior:
 *   • A successful token request set `_lastVerdict = MEETS_BASIC_INTEGRITY`
 *     even though the JWS was never decrypted by a backend. Callers reading
 *     [isVerified] were therefore trusting an opaque opaque token as proof of
 *     basic integrity, which is incorrect — Play Integrity tokens are JWS
 *     blobs that *only* a server holding the decryption key can interpret.
 *   • Nonces were generated per-request but never tracked, so the API allowed
 *     trivial replay if a caller cached a token.
 *
 * New behavior:
 *   • [isVerified] is now FALSE whenever the verdict is anything other than
 *     `MEETS_DEVICE_INTEGRITY`. We never auto-promote a token to BASIC_INTEGRITY
 *     locally. A non-null token without backend verification yields
 *     [IntegrityVerdict.UNVERIFIED].
 *   • A backend wiring point [BackendVerifier] is exposed. When set (typically
 *     by AIRIApplication after configuration), [warmUp] / [requestToken] forward
 *     the JWS to it and use the returned verdict as the source of truth.
 *   • Issued nonces are tracked in a small bounded set; [validateNonce] lets
 *     callers reject replays.
 *   • All log output is rate-limited so an offline device cannot spam logcat.
 */
object PlayIntegrityVerifier {

    private const val TAG = "PlayIntegrityVerifier"

    @Volatile private var _lastVerdict: IntegrityVerdict = IntegrityVerdict.UNAVAILABLE
    @Volatile private var _initialized = false

    /**
     * Pluggable backend verifier. When null we cannot decrypt the JWS, so the
     * verdict stays at UNVERIFIED (never BASIC_INTEGRITY) — see hardening note.
     */
    @Volatile var backendVerifier: BackendVerifier? = null

    /** Nonces issued in this process — used to reject replays. Bounded. */
    private val issuedNonces: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private const val MAX_TRACKED_NONCES = 256

    /** Last warn-log timestamp per category (rate-limit by category). */
    private val lastLogMs = ConcurrentHashMap<String, Long>()
    private const val LOG_RATE_LIMIT_MS = 60_000L

    /** Most recent verdict returned by the API + backend. */
    val lastVerdict: IntegrityVerdict get() = _lastVerdict

    /**
     * True ONLY when the device passes full Play Integrity (genuine,
     * unmodified, on Play Services). Callers gating sensitive operations
     * should read this property — not [lastVerdict] — and provide a
     * graceful-degradation path (NOT outright denial) when it returns false,
     * because plenty of legitimate users (sideload, MIUI) never reach this
     * verdict.
     */
    val isVerified: Boolean get() = _lastVerdict == IntegrityVerdict.MEETS_DEVICE_INTEGRITY

    interface BackendVerifier {
        /**
         * Forward [token] to your server, which decrypts the JWS and returns
         * the parsed verdict. Implementations must validate the included nonce
         * against [validateNonce]. Should be safe to call from IO dispatcher.
         */
        suspend fun verify(token: String, nonce: String): IntegrityVerdict
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun warmUp(context: Context) {
        if (_initialized) return
        runCatching { check(context, nonce = generateNonce()) }.onFailure { e ->
            rateLimitedWarn("WARMUP", "INTEGRITY_WARMUP_FAILED reason=${e.message?.take(80)}")
        }
        _initialized = true
    }

    suspend fun requestToken(context: Context, nonce: String = generateNonce()): String? =
        runCatching {
            val manager = IntegrityManagerFactory.create(context.applicationContext)
            val request = IntegrityTokenRequest.builder().setNonce(nonce).build()
            manager.requestIntegrityToken(request).await().token()
        }.onFailure { e ->
            rateLimitedWarn("TOKEN", "INTEGRITY_TOKEN_REQUEST_FAILED: ${e.message?.take(80)}")
        }.getOrNull()

    /**
     * True if [nonce] was issued by this process and has not been consumed yet.
     * Calling this consumes the nonce — replay attempts return false.
     */
    fun validateNonce(nonce: String): Boolean = issuedNonces.remove(nonce)

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun check(context: Context, nonce: String) {
        val token = requestToken(context, nonce)
        _lastVerdict = if (token == null) {
            rateLimitedWarn("UNAVAIL", "INTEGRITY_UNAVAILABLE")
            IntegrityVerdict.UNAVAILABLE
        } else {
            val backend = backendVerifier
            if (backend == null) {
                // SECURITY: do NOT promote to BASIC_INTEGRITY locally — the JWS
                // hasn't been decrypted. Callers gating sensitive flows on
                // isVerified will correctly see "not verified".
                Log.i(TAG, "AIRI INTEGRITY_TOKEN_OBTAINED verdict=UNVERIFIED (no backend)")
                IntegrityVerdict.UNVERIFIED
            } else {
                runCatching { backend.verify(token, nonce) }
                    .onFailure { e ->
                        rateLimitedWarn("BACKEND",
                            "INTEGRITY_BACKEND_VERIFY_FAILED: ${e.message?.take(80)}")
                    }
                    .getOrDefault(IntegrityVerdict.UNVERIFIED)
            }
        }
    }

    /** Cryptographically-random URL-safe base64 nonce (22 chars ≈ 132 bits). */
    private fun generateNonce(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val n = android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
        // Bound the in-memory set so a long-running process cannot leak.
        if (issuedNonces.size >= MAX_TRACKED_NONCES) {
            issuedNonces.clear()
        }
        issuedNonces.add(n)
        return n
    }

    private fun rateLimitedWarn(category: String, message: String) {
        val now = System.currentTimeMillis()
        val last = lastLogMs[category] ?: 0L
        if (now - last >= LOG_RATE_LIMIT_MS) {
            lastLogMs[category] = now
            Log.w(TAG, "AIRI $message")
        }
    }
}

enum class IntegrityVerdict {
    /** Real Android device, meets CTS, unmodified APK (verified by backend). */
    MEETS_DEVICE_INTEGRITY,
    /** Basic Android CTS passes; may be rooted / developer device (verified by backend). */
    MEETS_BASIC_INTEGRITY,
    /** Token received but no backend wired to decrypt the JWS — opaque. */
    UNVERIFIED,
    /** Play Integrity API unreachable (no Play Services, offline, sideloaded). */
    UNAVAILABLE
}
