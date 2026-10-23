package com.airi.assistant.integrations.google

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.google.android.gms.common.api.ApiException
import java.util.Collections
import java.util.IdentityHashMap

/** Stable, non-sensitive categories shared by Firebase sign-in and Google connector authorization. */
enum class GoogleSignInFailureReason {
    CANCELLED,
    NO_ACCOUNT,
    CLIENT_ID_MISSING,
    FIREBASE_CONFIGURATION,
    APP_MISCONFIGURED,
    PROVIDER_DISABLED,
    SIGNING_CERTIFICATE_NOT_REGISTERED,
    NETWORK,
    PLAY_SERVICES_UNAVAILABLE,
    CREDENTIAL_PROVIDER_UNAVAILABLE,
    ACTIVITY_CONTEXT_REQUIRED,
    NO_ID_TOKEN,
    INVALID_CREDENTIAL,
    SECURE_STORAGE_UNAVAILABLE,
    UNKNOWN,
}

class GoogleSignInFailureException(
    val reason: GoogleSignInFailureReason,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(reason.name, cause)

/** Converts provider exceptions into safe categories; raw exception text never reaches UI. */
object GoogleSignInFailureClassifier {
    fun classify(error: Throwable): GoogleSignInFailureReason {
        val chain = throwableChain(error)
        chain.filterIsInstance<GoogleSignInFailureException>().firstOrNull()?.let { return it.reason }

        val apiStatus = chain.filterIsInstance<ApiException>().firstOrNull()?.statusCode
            ?: statusCodeFromMessages(chain)
        reasonForStatus(apiStatus)?.let { return it }

        if (chain.any { it.javaClass.simpleName == "GetCredentialCancellationException" || it.javaClass.simpleName == "CancellationException" }) {
            return GoogleSignInFailureReason.CANCELLED
        }
        if (chain.any { it.javaClass.simpleName == "NoCredentialException" }) {
            return GoogleSignInFailureReason.NO_ACCOUNT
        }

        val diagnostic = chain.joinToString(" ") { it.message.orEmpty() }
            .lowercase()
            .replace('_', '-')
        return reasonForDiagnostic(diagnostic)
    }

    fun classifyCode(code: String, message: String = ""): GoogleSignInFailureReason {
        val normalized = code.lowercase().replace('_', '-').trim()
        normalized.toIntOrNull()?.let { numericCode -> reasonForStatus(numericCode)?.let { return it } }
        when {
            normalized.contains("cancel") || normalized == "16" -> return GoogleSignInFailureReason.CANCELLED
            normalized.contains("no-credential") || normalized.contains("no-account") -> return GoogleSignInFailureReason.NO_ACCOUNT
            normalized.contains("client-id") || normalized.contains("web-client") -> return GoogleSignInFailureReason.CLIENT_ID_MISSING
            normalized.contains("sha") || normalized.contains("fingerprint") || normalized.contains("certificate") ->
                return GoogleSignInFailureReason.SIGNING_CERTIFICATE_NOT_REGISTERED
            normalized.contains("developer-error") || normalized.contains("app-not-authorized") ||
                normalized.contains("app-misconfigured") ->
                return GoogleSignInFailureReason.APP_MISCONFIGURED
            normalized.contains("play-services") -> return GoogleSignInFailureReason.PLAY_SERVICES_UNAVAILABLE
            normalized.contains("provider-disabled") || normalized.contains("operation-not-allowed") ->
                return GoogleSignInFailureReason.PROVIDER_DISABLED
            normalized.contains("activity-context") -> return GoogleSignInFailureReason.ACTIVITY_CONTEXT_REQUIRED
            normalized.contains("network") || normalized.contains("timeout") -> return GoogleSignInFailureReason.NETWORK
            normalized.contains("secure-storage") || normalized.contains("storage-unavailable") ->
                return GoogleSignInFailureReason.SECURE_STORAGE_UNAVAILABLE
            normalized.contains("firebase") || normalized.contains("google-services") ||
                normalized.contains("configuration") || normalized.contains("invalid-api-key") ->
                return GoogleSignInFailureReason.FIREBASE_CONFIGURATION
            normalized.contains("no-id-token") || normalized.contains("token-missing") -> return GoogleSignInFailureReason.NO_ID_TOKEN
            normalized.contains("invalid-credential") -> return GoogleSignInFailureReason.INVALID_CREDENTIAL
            normalized.contains("credential") || normalized.contains("authorization-unavailable") ->
                return GoogleSignInFailureReason.CREDENTIAL_PROVIDER_UNAVAILABLE
        }
        return reasonForDiagnostic(message.lowercase().replace('_', '-'))
    }

    fun reasonForStatus(statusCode: Int?): GoogleSignInFailureReason? = when (statusCode) {
        7, 15 -> GoogleSignInFailureReason.NETWORK // NETWORK_ERROR / TIMEOUT
        10 -> GoogleSignInFailureReason.APP_MISCONFIGURED // DEVELOPER_ERROR is broader than a certificate mismatch
        16 -> GoogleSignInFailureReason.CANCELLED
        2, 3 -> GoogleSignInFailureReason.PLAY_SERVICES_UNAVAILABLE
        else -> null
    }

    private fun reasonForDiagnostic(value: String): GoogleSignInFailureReason = when {
        value.containsAny("operation-not-allowed", "provider disabled", "provider is disabled") ->
            GoogleSignInFailureReason.PROVIDER_DISABLED
        value.containsAny("sha1", "sha-1", "sha256", "sha-256", "fingerprint", "signing certificate") ->
            GoogleSignInFailureReason.SIGNING_CERTIFICATE_NOT_REGISTERED
        value.containsAny("developer-error", "developer error", "status code: 10", "app-not-authorized") ->
            GoogleSignInFailureReason.APP_MISCONFIGURED
        value.containsAny("default-web-client-id", "web client id", "server client id", "oauth client id is not configured", "client id is not configured") ->
            GoogleSignInFailureReason.CLIENT_ID_MISSING
        value.containsAny("firebaseapp", "firebase app is not initialized", "google-services.json", "firebase configuration", "invalid-api-key", "invalid api key") ->
            GoogleSignInFailureReason.FIREBASE_CONFIGURATION
        value.containsAny("network", "timed out", "timeout", "unable to resolve host", "connection refused") ->
            GoogleSignInFailureReason.NETWORK
        value.containsAny("play services", "google play services", "service-version-update-required", "service-disabled") ->
            GoogleSignInFailureReason.PLAY_SERVICES_UNAVAILABLE
        value.containsAny("no credentials available", "no credential", "no google account") ->
            GoogleSignInFailureReason.NO_ACCOUNT
        value.containsAny("id token is blank", "id token is empty", "did not provide an id token") ->
            GoogleSignInFailureReason.NO_ID_TOKEN
        value.containsAny("invalid credential", "malformed or has expired", "invalid id token") ->
            GoogleSignInFailureReason.INVALID_CREDENTIAL
        value.containsAny("credentialmanager", "credential manager", "credential provider", "provider configuration") ->
            GoogleSignInFailureReason.CREDENTIAL_PROVIDER_UNAVAILABLE
        value.containsAny("encrypted storage", "secure storage", "keystore") ->
            GoogleSignInFailureReason.SECURE_STORAGE_UNAVAILABLE
        else -> GoogleSignInFailureReason.UNKNOWN
    }

    private fun statusCodeFromMessages(chain: List<Throwable>): Int? {
        val pattern = Regex("(?:status(?:\\s*code)?|error(?:\\s*code)?)\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
        return chain.asSequence().mapNotNull { throwable ->
            pattern.find(throwable.message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
        }.firstOrNull()
    }

    private fun throwableChain(root: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>().apply { add(root) }
        val result = mutableListOf<Throwable>()
        while (pending.isNotEmpty() && result.size < 16) {
            val next = pending.removeFirst()
            if (!seen.add(next)) continue
            result += next
            next.cause?.let(pending::addLast)
            next.suppressed.forEach(pending::addLast)
        }
        return result
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any(::contains)
}

/** Credential Manager and interactive Google consent must be launched from an Activity context. */
object GooglePresentationContextPolicy {
    fun hasActivity(context: Context): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
        var current: Context? = context
        while (current != null && seen.add(current)) {
            if (current is Activity) return true
            current = (current as? ContextWrapper)?.baseContext
        }
        return false
    }
}

/** SHA-1 values are public signing fingerprints, not credentials; normalize JSON and PackageManager values. */
object GoogleSigningCertificatePolicy {
    fun normalizeSha1(value: String): String = value.filter(Char::isLetterOrDigit).lowercase()

    fun isRegistered(actualSha1: String, configuredSha1Csv: String): Boolean {
        val actual = normalizeSha1(actualSha1)
        if (actual.length != 40) return false
        return configuredSha1Csv.split(',')
            .asSequence()
            .map(::normalizeSha1)
            .any { it.length == 40 && it == actual }
    }
}
