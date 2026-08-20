package com.airi.assistant.auth.identity

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.domain.logging.LoggingService
import java.security.MessageDigest
import java.util.UUID

/**
 * DeviceBindingService — derives a stable, opaque device fingerprint and
 * binds the current session to that fingerprint.
 *
 * ── FINGERPRINT COMPOSITION ───────────────────────────────────────────────
 *
 *   SHA-256( ANDROID_ID + MANUFACTURER + MODEL + SDK_INT + INSTALL_UUID )
 *
 *   ANDROID_ID:   hardware/SIM-derived 64-bit hex, stable across reboots.
 *   INSTALL_UUID: a random UUID persisted in EncryptedSharedPreferences on
 *                 first launch (survives reboot, reset on factory reset).
 *
 *   The combined hash is 64 hex chars. It is stored in SecureStorage under
 *   the key "device_fingerprint" and re-verified on every app launch.
 *
 * ── BINDING SEMANTICS ─────────────────────────────────────────────────────
 *
 *   On first bind: the fingerprint is computed, stored, and returned.
 *   On subsequent launches: the stored fingerprint is compared to the
 *   freshly computed one. A mismatch raises DeviceChangedException, which
 *   the identity layer can use to force re-authentication.
 *
 * ── PRIVACY ───────────────────────────────────────────────────────────────
 *
 *   The fingerprint never leaves the device. It is only used locally to
 *   detect device swaps and to scope session tokens.
 */
class DeviceBindingService(private val context: Context) {

    private val TAG = "DeviceBindingService"
    private val secureStorage = SecureStorage(context)

    class DeviceChangedException(stored: String, current: String) :
        SecurityException("Device fingerprint mismatch: stored=$stored current=$current")

    /**
     * Returns the stored fingerprint, creating and binding one if this is
     * the first launch. Throws [DeviceChangedException] if the device has
     * changed since the last bind.
     */
    fun bindOrThrow(): String {
        val current = computeFingerprint()
        val stored  = secureStorage.getDeviceFingerprint()

        return when {
            stored == null -> {
                secureStorage.saveDeviceFingerprint(current)
                LoggingService.info(TAG, "AIRI DEVICE_BIND_INITIAL fingerprint=${current.take(12)}…")
                current
            }
            stored == current -> {
                LoggingService.info(TAG, "AIRI DEVICE_BIND_OK fingerprint=${current.take(12)}…")
                current
            }
            else -> {
                LoggingService.warn(TAG, "AIRI DEVICE_BIND_MISMATCH stored=${stored.take(12)}… current=${current.take(12)}…")
                throw DeviceChangedException(stored, current)
            }
        }
    }

    /**
     * Returns the current fingerprint without binding or checking.
     * Use for informational purposes only.
     */
    fun currentFingerprint(): String = computeFingerprint()

    /**
     * Clears the stored fingerprint. Call this on sign-out so the next
     * sign-in re-binds to the current device.
     */
    fun clearBinding() {
        secureStorage.clearDeviceFingerprint()
        LoggingService.info(TAG, "AIRI DEVICE_BIND_CLEARED")
    }

    @SuppressLint("HardwareIds")
    private fun computeFingerprint(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val installUuid = ensureInstallUuid()
        val raw = "$androidId|${Build.MANUFACTURER}|${Build.MODEL}|${Build.VERSION.SDK_INT}|$installUuid"
        return sha256Hex(raw)
    }

    private fun ensureInstallUuid(): String {
        val existing = secureStorage.getInstallUuid()
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        secureStorage.saveInstallUuid(fresh)
        Log.i(TAG, "Generated fresh install UUID")
        return fresh
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes  = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
