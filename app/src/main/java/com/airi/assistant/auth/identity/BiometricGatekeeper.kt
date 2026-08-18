package com.airi.assistant.auth.identity

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * BiometricGatekeeper — gate any sensitive operation behind biometric/PIN auth.
 *
 * Usage:
 *   val ok = BiometricGatekeeper.authenticate(activity, "Unlock secure storage")
 *   if (ok) { ... proceed ... }
 *
 * This class never stores biometric data. It delegates entirely to the platform
 * BiometricPrompt API (androidx.biometric). On devices that do not support
 * BIOMETRIC_STRONG, it falls back to DEVICE_CREDENTIAL (PIN/pattern/password).
 */
object BiometricGatekeeper {

    private const val TAG = "BiometricGatekeeper"

    enum class Availability {
        AVAILABLE,
        NO_HARDWARE,
        NOT_ENROLLED,
        UNAVAILABLE
    }

    fun checkAvailability(context: Context): Availability {
        val bm = BiometricManager.from(context)
        return when (bm.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS          -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED  -> Availability.NOT_ENROLLED
            else                                             -> Availability.UNAVAILABLE
        }
    }

    /**
     * Suspend until the user successfully authenticates or cancels/fails.
     * Returns true on success, false on any failure or cancellation.
     *
     * Must be called from the Main thread (BiometricPrompt constraint).
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String = "Authenticate",
        subtitle: String = "AIRI needs to verify your identity",
        negativeButtonText: String = "Cancel"
    ): Boolean = suspendCancellableCoroutine { cont ->

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.i(TAG, "Biometric auth succeeded type=${result.authenticationType}")
                LoggingService.info(TAG, "AIRI BIOMETRIC_AUTH_SUCCESS")
                if (cont.isActive) cont.resume(true)
            }

            override fun onAuthenticationFailed() {
                Log.w(TAG, "Biometric auth attempt failed (but not yet cancelled)")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.w(TAG, "Biometric auth error code=$errorCode msg=$errString")
                LoggingService.warn(TAG, "AIRI BIOMETRIC_AUTH_ERROR code=$errorCode")
                if (cont.isActive) cont.resume(false)
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        cont.invokeOnCancellation { prompt.cancelAuthentication() }

        prompt.authenticate(promptInfo)
    }

    /**
     * Returns true if biometric is available AND enrolled — useful for gating
     * UI "unlock with biometric" affordances.
     */
    fun isAvailable(context: Context): Boolean =
        checkAvailability(context) == Availability.AVAILABLE
}
