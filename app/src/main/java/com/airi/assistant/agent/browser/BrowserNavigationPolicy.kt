package com.airi.assistant.agent.browser

import java.net.URI

/**
 * Shared browser boundary for cloud fetches and local hand-offs.
 *
 * Read-only HTTPS navigation may proceed when it targets a public host. Any
 * login, upload, form submission, payment, download, or external hand-off is
 * surfaced as an explicit user-controlled operation rather than treated as a
 * background browser action.
 */
object BrowserNavigationPolicy {
    enum class Operation {
        READ,
        OPEN_EXTERNAL,
        DOWNLOAD,
        UPLOAD,
        FORM_SUBMIT,
        LOGIN,
        PAYMENT
    }

    sealed class Decision {
        data class Allow(val normalizedUrl: String) : Decision()
        data class RequiresApproval(val normalizedUrl: String, val reason: String) : Decision()
        data class RequiresUserTakeover(val normalizedUrl: String, val reason: String) : Decision()
        data class Blocked(val reason: String) : Decision()
    }

    fun evaluate(url: String, operation: Operation): Decision {
        val normalized = normalizePublicHttpUrl(url) ?: return Decision.Blocked("URL is not a public HTTP(S) address")
        return when (operation) {
            Operation.READ -> Decision.Allow(normalized)
            Operation.OPEN_EXTERNAL -> Decision.RequiresUserTakeover(normalized, "Opening an external browser transfers control to the user")
            Operation.DOWNLOAD -> Decision.RequiresApproval(normalized, "Downloading a file changes local device storage")
            Operation.UPLOAD -> Decision.RequiresUserTakeover(normalized, "Uploading requires selecting user-controlled data")
            Operation.FORM_SUBMIT -> Decision.RequiresUserTakeover(normalized, "Submitting a form can create an external side effect")
            Operation.LOGIN -> Decision.RequiresUserTakeover(normalized, "Authentication must be completed by the user")
            Operation.PAYMENT -> Decision.RequiresUserTakeover(normalized, "Payment and purchase actions require the user")
        }
    }

    fun inferOperation(input: String): Operation {
        val normalized = input.lowercase()
        return when {
            LOGIN_TERMS.any { it in normalized } -> Operation.LOGIN
            PAYMENT_TERMS.any { it in normalized } -> Operation.PAYMENT
            UPLOAD_TERMS.any { it in normalized } -> Operation.UPLOAD
            DOWNLOAD_TERMS.any { it in normalized } -> Operation.DOWNLOAD
            SUBMIT_TERMS.any { it in normalized } -> Operation.FORM_SUBMIT
            else -> Operation.READ
        }
    }

    fun normalizePublicHttpUrl(value: String): String? {
        val raw = value.trim().let { if (it.startsWith("www.", ignoreCase = true)) "https://$it" else it }
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        if (scheme !in setOf("http", "https") || uri.userInfo != null || uri.port !in -1..65535) return null
        if (isPrivateHost(host)) return null
        return uri.normalize().toString()
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return true
        if (host == "0.0.0.0" || host == "::1" || host == "[::1]") return true
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return true
        if (host.matches(Regex("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))) return true
        if (host.startsWith("169.254.")) return true
        return false
    }

    private val LOGIN_TERMS = listOf("login", "log in", "sign in", "authenticate", "password", "otp")
    private val PAYMENT_TERMS = listOf("buy", "purchase", "pay", "checkout", "payment", "order")
    private val UPLOAD_TERMS = listOf("upload", "attach file", "send file")
    private val DOWNLOAD_TERMS = listOf("download", "save file", "export file")
    private val SUBMIT_TERMS = listOf("submit form", "send form", "publish", "post this")
}
