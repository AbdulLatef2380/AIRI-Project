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
import com.airi.assistant.execution.cloud.RetryPolicy
import com.airi.assistant.execution.network.NetworkGuard
import com.airi.assistant.execution.prefs.ExecModePreferences
import com.airi.assistant.execution.security.SecureApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud runtime backend — production-grade.
 *
 * Routes to the correct provider adapter via [CloudAdapterFactory], applies
 * [NetworkGuard] as a last-line firewall, runs [RetryPolicy] for transient
 * failures, and reports accurate token usage to [ExecModePreferences].
 *
 * ## Provider selection
 * Uses [prefs.preferredProvider] at call time so changes in Settings take
 * effect on the next request without any restart.
 *
 * ## Network firewall
 * [NetworkGuard.evaluate] is called BEFORE any HTTP connection. If the mode
 * is LOCAL_ONLY (e.g. changed mid-session), the request is blocked here even
 * if the caller somehow reached CloudBackend.
 *
 * ## Retry policy
 * Retryable errors (RATE_LIMITED, SERVER_ERROR, TIMEOUT, CONNECTION_LOST)
 * are retried up to [MAX_RETRIES] times with exponential back-off + jitter.
 * Non-retryable errors (UNAUTHORIZED, QUOTA_EXCEEDED, CANCELLED) fail fast.
 *
 * ## Token accounting
 * Accurate token counts from the provider's `usage` field are persisted via
 * [ExecModePreferences.recordCloudTokens] for the daily budget display.
 */
class CloudBackend(
    private val prefs:          ExecModePreferences,
    private val context:        Context,
    // Optional — when provided, successful cloud generations are recorded
    // with full prompt/completion breakdown for the Diagnostics screen.
    // Injected by ChatViewModel which owns the singleton TokenAccountant.
    private val tokenAccountant: com.airi.assistant.execution.accounting.TokenAccountant? = null
) : RuntimeBackend {

    override val id:           String          = "cloud"
    override val displayName:  String          = "Cloud"
    override val capabilities: CapabilityProfile = CapabilityProfile.CLOUD_STREAMING
    override val origin:       ExecOrigin      = ExecOrigin.CLOUD

    override val isAvailable: Boolean
        get() {
            val guard = NetworkGuard.evaluate(prefs)
            if (guard is NetworkGuard.Decision.Block) return false
            val adapter = CloudAdapterFactory.create(prefs.preferredProvider, context)
            return adapter.isAvailable
        }

    override suspend fun generateStream(
        request:    ExecutionRequest,
        onToken:    suspend (String) -> Unit,
        onComplete: suspend (String, Long) -> Unit,
        onError:    suspend (String)       -> Unit
    ) {
        // ── NetworkGuard firewall ──────────────────────────────────────────────
        when (val guard = NetworkGuard.evaluate(prefs)) {
            is NetworkGuard.Decision.Block -> {
                onError("Network blocked: ${guard.reason}")
                return
            }
            NetworkGuard.Decision.Allow -> {}
        }

        val provider = prefs.preferredProvider
        val adapter  = CloudAdapterFactory.create(provider, context, request)

        if (!adapter.isAvailable) {
            onError("${provider.displayName}: no API key configured")
            return
        }

        RuntimeEventLog.post("CLOUD_BACKEND", EventSeverity.INFO,
            "Streaming via ${provider.displayName} (${adapter.providerId})")

        // ── Retry loop ────────────────────────────────────────────────────────
        val result = RetryPolicy.withRetry(maxAttempts = MAX_RETRIES) { attempt ->
            if (attempt > 0) {
                RuntimeEventLog.post("CLOUD_BACKEND", EventSeverity.WARN,
                    "Retry attempt $attempt/${MAX_RETRIES - 1} for ${provider.displayName}")
            }
            val fullText  = StringBuilder()
            var promptTok = 0
            var compTok   = 0
            val startMs   = System.currentTimeMillis()

            adapter.streamGenerate(
                request = request,
                onToken = { token ->
                    fullText.append(token)
                    onToken(token)
                },
                onUsage = { p, c ->
                    promptTok = p
                    compTok   = c
                }
            ).also { adapterResult ->
                if (adapterResult is com.airi.assistant.execution.cloud.CloudProviderAdapter.AdapterResult.Success) {
                    val totalTokens = promptTok + compTok
                    if (totalTokens > 0) prefs.recordCloudTokens(totalTokens)
                    // Also feed the detailed TokenAccountant for per-provider stats + UI StateFlow
                    if (totalTokens > 0) {
                        runCatching {
                            kotlinx.coroutines.runBlocking {
                                tokenAccountant?.recordSuccess(
                                    provider         = provider,
                                    promptTokens     = promptTok,
                                    completionTokens = compTok,
                                    latencyMs        = adapterResult.latencyMs
                                )
                            }
                        }
                    }
                    RuntimeEventLog.post("CLOUD_BACKEND", EventSeverity.INFO,
                        "${provider.displayName} OK: ${adapterResult.latencyMs}ms " +
                        "tokens=${promptTok}p+${compTok}c")
                }
            }
        }

        when (result) {
            is com.airi.assistant.execution.cloud.CloudProviderAdapter.AdapterResult.Success -> {
                onComplete(result.fullText, result.latencyMs)
            }
            is com.airi.assistant.execution.cloud.CloudProviderAdapter.AdapterResult.Failure -> {
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
                    error     = guard.reason,
                    origin    = ExecOrigin.CLOUD,
                    retryable = false,
                    code      = "network_blocked"
                )
                NetworkGuard.Decision.Allow -> {
                    val provider = prefs.preferredProvider
                    val adapter  = CloudAdapterFactory.create(provider, context, request)
                    if (!adapter.isAvailable) {
                        return@withContext ExecutionResult.Failure(
                            error     = "${provider.displayName}: no API key",
                            origin    = ExecOrigin.CLOUD,
                            retryable = false,
                            code      = "not_configured"
                        )
                    }
                    val startMs  = System.currentTimeMillis()
                    val fullText = StringBuilder()
                    val result = RetryPolicy.withRetry(MAX_RETRIES) {
                        adapter.streamGenerate(request, onToken = { fullText.append(it) })
                    }
                    when (result) {
                        is com.airi.assistant.execution.cloud.CloudProviderAdapter.AdapterResult.Success ->
                            ExecutionResult.Success(
                                fullText  = result.fullText,
                                origin    = ExecOrigin.CLOUD,
                                latencyMs = System.currentTimeMillis() - startMs,
                                provider  = provider.name
                            )
                        is com.airi.assistant.execution.cloud.CloudProviderAdapter.AdapterResult.Failure ->
                            ExecutionResult.Failure(
                                error     = result.error,
                                origin    = ExecOrigin.CLOUD,
                                retryable = result.retryable,
                                code      = result.errorType.name.lowercase()
                            )
                    }
                }
            }
        }

    // ── User-facing error messages ─────────────────────────────────────────────

    private fun buildUserErrorMessage(
        type:     CloudErrorType,
        raw:      String,
        provider: CloudProvider
    ): String = when (type) {
        CloudErrorType.UNAUTHORIZED    -> "Invalid API key for ${provider.displayName}. Check Settings → API Keys."
        CloudErrorType.QUOTA_EXCEEDED  -> "${provider.displayName} quota exhausted. Check your billing dashboard."
        CloudErrorType.RATE_LIMITED    -> "${provider.displayName} is rate-limiting requests. Please wait a moment."
        CloudErrorType.CONTEXT_LENGTH  -> "Prompt too long for ${provider.displayName}. Try a shorter message."
        CloudErrorType.CONTENT_FILTERED -> "Your message was filtered by ${provider.displayName}'s safety policy."
        CloudErrorType.TIMEOUT         -> "${provider.displayName} timed out. Check your internet connection."
        CloudErrorType.CONNECTION_LOST -> "Connection to ${provider.displayName} was lost mid-stream."
        CloudErrorType.CANCELLED       -> "Request cancelled."
        CloudErrorType.SERVER_ERROR    -> "${provider.displayName} is experiencing issues (server error). Try again."
        CloudErrorType.INVALID_REQUEST -> "Request error: $raw"
        CloudErrorType.UNKNOWN         -> "Cloud error: ${raw.take(100)}"
    }

    companion object {
        private const val TAG         = "AIRI_CloudBackend"
        private const val MAX_RETRIES = 3
    }
}
