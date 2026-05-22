package com.airi.assistant.execution.backend

import android.content.Context
import android.util.Log
import com.airi.assistant.core.debug.EventSeverity
import com.airi.assistant.core.debug.RuntimeEventLog
import com.airi.assistant.execution.CapabilityProfile
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.ExecutionResult
import com.airi.assistant.execution.cloud.CloudAdapterFactory
import com.airi.assistant.execution.cloud.CloudErrorType
import com.airi.assistant.execution.cloud.CloudProviderAdapter
import com.airi.assistant.execution.cloud.RetryPolicy
import com.airi.assistant.execution.network.NetworkGuard
import com.airi.assistant.execution.prefs.ExecModePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud runtime backend.
 *
 * ── Phase-3 P0 hardening (M1) ─────────────────────────────────────────────────
 * Previous behavior: token-usage was reported via
 *   `runCatching { runBlocking { tokenAccountant?.recordSuccess(...) } }`
 * This blocked the IO dispatcher thread, defeating structured concurrency and
 * risking dispatcher starvation under retry storms. It also swallowed failures
 * silently.
 *
 * New behavior: [tokenAccountant.recordSuccess] is now invoked from the same
 * suspending coroutine that owns the streaming call. No `runBlocking`, no
 * thread-pinning, identical observable behavior to callers.
 */
class CloudBackend(
    private val prefs: ExecModePreferences,
    private val context: Context,
    private val tokenAccountant: com.airi.assistant.execution.accounting.TokenAccountant? = null
) : RuntimeBackend {

    override val id: String = "cloud"
    override val displayName: String = "Cloud"
    override val capabilities: CapabilityProfile = CapabilityProfile.CLOUD_STREAMING
    override val origin: ExecOrigin = ExecOrigin.CLOUD

    override val isAvailable: Boolean
        get() {
            val guard = NetworkGuard.evaluate(prefs)
            if (guard is NetworkGuard.Decision.Block) return false
            val adapter = CloudAdapterFactory.create(prefs.preferredProvider, context)
            return adapter.isAvailable
        }

    override suspend fun generateStream(
        request: ExecutionRequest,
        onToken: suspend (String) -> Unit,
        onComplete: suspend (String, Long) -> Unit,
        onError: suspend (String) -> Unit
    ) {
        when (val guard = NetworkGuard.evaluate(prefs)) {
            is NetworkGuard.Decision.Block -> { onError("Network blocked: ${guard.reason}"); return }
            NetworkGuard.Decision.Allow -> {}
        }

        val provider = prefs.preferredProvider
        val adapter = CloudAdapterFactory.create(provider, context, request)
        if (!adapter.isAvailable) {
            onError("${provider.displayName}: no API key configured"); return
        }

        RuntimeEventLog.post("CLOUD_BACKEND", EventSeverity.INFO,
            "Streaming via ${provider.displayName} (${adapter.providerId})")

        // Keep token counts captured per attempt; closure mutated by adapter.
        var promptTok = 0
        var compTok = 0

        val result = RetryPolicy.withRetry(maxAttempts = MAX_RETRIES) { attempt ->
            if (attempt > 0) {
                RuntimeEventLog.post("CLOUD_BACKEND", EventSeverity.WARN,
                    "Retry attempt $attempt/${MAX_RETRIES - 1} for ${provider.displayName}")
            }
            promptTok = 0
            compTok = 0
            adapter.streamGenerate(
                request = request,
                onToken = { token -> onToken(token) },
                onUsage = { p, c -> promptTok = p; compTok = c }
            )
        }

        when (result) {
            is CloudProviderAdapter.AdapterResult.Success -> {
                val totalTokens = promptTok + compTok
                if (totalTokens > 0) {
                    prefs.recordCloudTokens(totalTokens)
                    // Suspending call — same coroutine, no runBlocking.
                    runCatching {
                        tokenAccountant?.recordSuccess(
                            provider = provider,
                            promptTokens = promptTok,
                            completionTokens = compTok,
                            latencyMs = result.latencyMs
                        )
                    }.onFailure { e ->
                        Log.w(TAG, "tokenAccountant.recordSuccess failed: ${e.message}")
                    }
                }
                RuntimeEventLog.post("CLOUD_BACKEND", EventSeverity.INFO,
                    "${provider.displayName} OK: ${result.latencyMs}ms tokens=${promptTok}p+${compTok}c")
                onComplete(result.fullText, result.latencyMs)
            }
            is CloudProviderAdapter.AdapterResult.Failure -> {
                val errMsg = buildUserErrorMessage(result.errorType, result.error, provider)
                RuntimeEventLog.post("CLOUD_BACKEND", EventSeverity.ERROR,
                    "${provider.displayName} failed [${result.errorType}]: ${result.error.take(80)}")
                Log.w(TAG, "CloudBackend failure: type=${result.errorType} http=${result.httpCode} ${result.error}")
                onError(errMsg)
            }
        }
    }

    override suspend fun generate(request: ExecutionRequest): ExecutionResult =
        withContext(Dispatchers.IO) {
            when (val guard = NetworkGuard.evaluate(prefs)) {
                is NetworkGuard.Decision.Block -> ExecutionResult.Failure(
                    error = guard.reason, origin = ExecOrigin.CLOUD,
                    retryable = false, code = "network_blocked"
                )
                NetworkGuard.Decision.Allow -> {
                    val provider = prefs.preferredProvider
                    val adapter = CloudAdapterFactory.create(provider, context, request)
                    if (!adapter.isAvailable) {
                        return@withContext ExecutionResult.Failure(
                            error = "${provider.displayName}: no API key",
                            origin = ExecOrigin.CLOUD,
                            retryable = false, code = "not_configured"
                        )
                    }
                    val startMs = System.currentTimeMillis()
                    val fullText = StringBuilder()
                    val result = RetryPolicy.withRetry(MAX_RETRIES) {
                        adapter.streamGenerate(request, onToken = { fullText.append(it) })
                    }
                    when (result) {
                        is CloudProviderAdapter.AdapterResult.Success ->
                            ExecutionResult.Success(
                                fullText = result.fullText,
                                origin = ExecOrigin.CLOUD,
                                latencyMs = System.currentTimeMillis() - startMs,
                                provider = provider.name
                            )
                        is CloudProviderAdapter.AdapterResult.Failure ->
                            ExecutionResult.Failure(
                                error = result.error, origin = ExecOrigin.CLOUD,
                                retryable = result.retryable,
                                code = result.errorType.name.lowercase()
                            )
                    }
                }
            }
        }

    private fun buildUserErrorMessage(type: CloudErrorType, raw: String, provider: CloudProvider): String =
        when (type) {
            CloudErrorType.UNAUTHORIZED     -> "Invalid API key for ${provider.displayName}. Check Settings → API Keys."
            CloudErrorType.QUOTA_EXCEEDED   -> "${provider.displayName} quota exhausted. Check your billing dashboard."
            CloudErrorType.RATE_LIMITED     -> "${provider.displayName} is rate-limiting requests. Please wait a moment."
            CloudErrorType.CONTEXT_LENGTH   -> "Prompt too long for ${provider.displayName}. Try a shorter message."
            CloudErrorType.CONTENT_FILTERED -> "Your message was filtered by ${provider.displayName}'s safety policy."
            CloudErrorType.TIMEOUT          -> "${provider.displayName} timed out. Check your internet connection."
            CloudErrorType.CONNECTION_LOST  -> "Connection to ${provider.displayName} was lost mid-stream."
            CloudErrorType.CANCELLED        -> "Request cancelled."
            CloudErrorType.SERVER_ERROR     -> "${provider.displayName} is experiencing issues (server error). Try again."
            CloudErrorType.INVALID_REQUEST  -> "Request error: $raw"
            CloudErrorType.UNKNOWN          -> "Cloud error: ${raw.take(100)}"
        }

    companion object {
        private const val TAG = "AIRI_CloudBackend"
        private const val MAX_RETRIES = 3
    }
}
