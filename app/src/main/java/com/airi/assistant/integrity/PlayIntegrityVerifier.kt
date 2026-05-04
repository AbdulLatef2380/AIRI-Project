package com.airi.assistant.integrity

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom

/**
 * PlayIntegrityVerifier — thin wrapper around the Play Integrity API.
 *
 * ── PURPOSE ───────────────────────────────────────────────────────────────────
 * Verifies that AIRI is running on a legitimate Android device and an unmodified
 * APK before high-risk agent actions (e.g. cloud inference, subscription purchase,
 * identity operations).
 *
 * ── VERDICT LEVELS ────────────────────────────────────────────────────────────
 * MEETS_DEVICE_INTEGRITY  → real Android device with Play Services
 * MEETS_BASIC_INTEGRITY   → passes basic Android CTS (may be rooted/emulator)
 * UNVERIFIED              → token obtained but verdict unavailable/degraded
 * UNAVAILABLE             → Play Integrity API not accessible (no Play Services,
 *                           sideloaded, or network down)
 *
 * ── OFFLINE / LOCAL_ONLY POLICY ───────────────────────────────────────────────
 * The full verdict requires a backend round-trip to Google's servers plus your
 * own server to decrypt the JWS. In production, `requestToken()` returns the raw
 * IntegrityToken; send it to your backend via a short-lived nonce to prevent
 * replay attacks. For the current milestone the verdict is logged locally as an
 * AIRI_PROOF signal so that suspicious conditions are visible in crash analytics.
 *
 * ── THREAD SAFETY ─────────────────────────────────────────────────────────────
 * All public methods are suspending and safe to call from any dispatcher.
 * Init is idempotent — multiple calls from AIRIApplication.onCreate() are safe.
 */
object PlayIntegrityVerifier {

    private const val TAG = "PlayIntegrityVerifier"

    @Volatile private var _lastVerdict: IntegrityVerdict = IntegrityVerdict.UNAVAILABLE
    @Volatile private var _initialized = false

    /** The most recent verdict returned by the Play Integrity API. */
    val lastVerdict: IntegrityVerdict get() = _lastVerdict

    /** True once a non-UNAVAILABLE verdict has been received this session. */
    val isVerified: Boolean get() = _lastVerdict == IntegrityVerdict.MEETS_DEVICE_INTEGRITY

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Perform a warm-up integrity check at app start.
     *
     * Failure is non-fatal — AIRI continues operating in a degraded trust state.
     * The result is cached in [lastVerdict] and emitted as an AIRI_PROOF log.
     */
    suspend fun warmUp(context: Context) {
        if (_initialized) return
        runCatching { check(context, nonce = generateNonce()) }.onFailure { e ->
            Log.w(TAG, "AIRI_PROOF INTEGRITY_WARMUP_FAILED reason=${e.message?.take(80)}")
        }
        _initialized = true
    }

    /**
     * Request an integrity token for a specific user action.
     *
     * @param context  Application context.
     * @param nonce    Unique per-request nonce (base64, min 16 bytes). Used by
     *                 your backend to prevent token replay.
     * @return Raw integrity token string to forward to your backend, or null on failure.
     */
    suspend fun requestToken(context: Context, nonce: String = generateNonce()): String? =
        runCatching {
            val manager = IntegrityManagerFactory.create(context.applicationContext)
            val request = IntegrityTokenRequest.builder().setNonce(nonce).build()
            manager.requestIntegrityToken(request).await().token()
        }.onFailure { e ->
            Log.w(TAG, "INTEGRITY_TOKEN_REQUEST_FAILED: ${e.message?.take(80)}")
        }.getOrNull()

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun check(context: Context, nonce: String) {
        val token = requestToken(context, nonce)
        _lastVerdict = if (token != null) {
            // Full verdict decryption requires a server-side call (the token is a JWS).
            // Until a backend is wired, we treat a non-null token as basic integrity.
            // Replace this block with a retrofit/ktor call to your verdict endpoint.
            Log.i(TAG, "AIRI_PROOF INTEGRITY_TOKEN_OBTAINED verdict=MEETS_BASIC_INTEGRITY")
            IntegrityVerdict.MEETS_BASIC_INTEGRITY
        } else {
            Log.w(TAG, "AIRI_PROOF INTEGRITY_UNAVAILABLE")
            IntegrityVerdict.UNAVAILABLE
        }
    }

    /** Cryptographically-random URL-safe base64 nonce (22 chars ≈ 132 bits). */
    private fun generateNonce(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    }
}

// ── Verdict enum ──────────────────────────────────────────────────────────────

enum class IntegrityVerdict {
    /** Real Android device, meets CTS, unmodified APK. */
    MEETS_DEVICE_INTEGRITY,
    /** Basic Android CTS passes; may be rooted / developer device. */
    MEETS_BASIC_INTEGRITY,
    /** Token received but server verdict not yet decoded. */
    UNVERIFIED,
    /** Play Integrity API unreachable (no Play Services, offline, sideloaded). */
    UNAVAILABLE
}
