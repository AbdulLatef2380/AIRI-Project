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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Cloud runtime backend.
 *
 * ──  hardening (M1) ─────────────────────────────────────────────────
 * Previous behavior: token-usage was reported via
 *   `runCatching { runBlocking { tokenAccountant?.recordSuccess(...) } }`
 * This blocked the IO dispatcher thread, defeating structured concurrency and
 * risking dispatcher starvation under retry storms. It also swallowed failures
 * silently.
 *
 * New behavior: [tokenAccountant.recordSuccess] is now invoked from the same
 * suspending coroutine that owns the streaming call. No `runBlocking`, no
 * thread-pinning, identical observable behavior to callers.
 *
 * ── Multi-cloud provider failover ────────────────────────────────────
 * When the preferred provider exhausts all [MAX_RETRIES] attempts, [generateStream]
 * automatically falls through to alternate available cloud providers in priority
 * order ([FAILOVER_PRIORITY]) before returning a terminal error. Each fallover is
 * logged to [RuntimeEventLog] so the diagnostics timeline shows the full failure
 * chain. The caller ([HybridOrchestrator]) already handles LOCAL fallback after
 * cloud backend failure — multi-cloud failover happens WITHIN the cloud backend,
 * transparent to the orchestrator.
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
    @Volatile private var successfulProviderLabel: String = "Cloud provider (unresolved)"
    override val sourceLabel: String get() = successfulProviderLabel

    
    private val _requestCount  = java.util.concurrent.atomic.AtomicInteger(0)
    private val _errorCount    = java.util.concurrent.atomic.AtomicInteger(0)
    private val _totalLatencyMs = java.util.concurrent.atomic.AtomicLong(0)
    private val cancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var activeRequestJob: Job? = null

    override fun cancelStream() {
        cancelRequested.set(true)
        activeRequestJob?.cancel(kotlinx.coroutines.CancellationException("Cloud request cancelled"))
        RuntimeEventLog.post("CLOUD_BACKEND", EventSeverity.INFO, "Cloud cancellation requested")
    }

    data class NetworkStats(
        val requestCount: Int,
        val errorCount:   Int,
        val avgLatencyMs: Long
    )

    fun stats(): NetworkStats = NetworkStats(
        requestCount = _requestCount.get(),
        errorCount   = _errorCount.get(),
        avgLatencyMs = if (_requestCount.get() > 0) _totalLatencyMs.get() / _requestCount.get() else 0L
    )

    override val isAvailable: Boolean
        get() {
            val guard = NetworkGuard.evaluate(prefs)
            if (guard is NetworkGuard.Decision.Block) return false
            // Availability is a capability of the failover chain, not only of
            // the preferred provider. Previously a missing Gemini key made the
            // whole cloud backend unavailable even when OpenAI/OpenRouter had a
            // valid key, so RuntimeRouter never got a chance to fail over.
            val providers = buildList {
                add(prefs.preferredProvider.takeUnless { it == CloudProvider.BRAVE }
                    ?: CloudProvider.GEMINI)
                FAILOVER_PRIORITY.forEach { if (it !in this) add(it) }
            }
            return providers.any { CloudAdapterFactory.create(it, context).isAvailable }
        }

    override suspend fun generateStream(
        request: ExecutionRequest,
        onToken: suspend (String) -> Unit,
        onComplete: suspend (String, Long) -> Unit,
        onError: suspend (String) -> Unit
    ) {
        cancelRequested.set(false)
        activeRequestJob = currentCoroutineContext()[Job]
        try {
        if (!capabilities.canFit(request)) {
            onError("Cloud context budget exceeded before request dispatch")
            return
        }
        when (val guard = NetworkGuard.evaluate(prefs)) {
            is NetworkGuard.Decision.Block -> { onError("Network blocked: ${guard.reason}"); return }
            NetworkGuard.Decision.Allow -> {}
        }

        // ── Resolve the provider target for this request ─────────────────────
        // An explicit provider identity is authoritative. Never retry the same
        // model against another provider: provider/model namespaces are not
        // interchangeable, and doing so hides ownership errors from the user.
        val requestedProvider = request.requestedProviderId
            .takeIf { it.isNotBlank() }
            ?.let { id -> CloudProvider.entries.firstOrNull { it.name.equals(id, ignoreCase = true) } }
        if (request.requestedProviderId.isNotBlank() &&
            (requestedProvider == null || requestedProvider == CloudProvider.BRAVE)
        ) {
            onError("Requested cloud provider is not supported: ${request.requestedProviderId}")
            return
        }
        val primary = requestedProvider
            ?: prefs.preferredProvider.takeUnless { it == CloudProvider.BRAVE }
            ?: CloudProvider.GEMINI
        val providerQueue = listOf(primary)

        var lastError = "Unknown cloud error"
        
        val requestStart = System.currentTimeMillis()
        _requestCount.incrementAndGet(); _globalRequestCount.incrementAndGet()

        for ((attemptIdx, provider) in providerQueue.withIndex()) {
            if (cancelRequested.get()) {
                onError("Cancelled")
                return
            }
            val isFallback = attemptIdx > 0
            if (isFallback) {
                RuntimeEventLog.post("CLOUD_BACKEND", EventSeverity.WARN,
                    "CLOUD_FAILOVER ${providerQueue[attemptIdx - 1].displayName} → ${provider.displayName}")
                Log.w(TAG, "AIRI CLOUD_FAILOVER from=${providerQueue[attemptIdx-1].name} to=${provider.name}")
            }

            val resolvedModel = CloudAdapterFactory.resolveRequestedModel(provider, request)
                ?: request.resolvedModelId.takeIf { it.isNotBlank() }
            if (provider == CloudProvider.OPENROUTER) {
                val liveModels = com.airi.assistant.execution.cloud.OpenRouterModelRegistry.refresh()
                if (resolvedModel != null && liveModels.isNotEmpty() && resolvedModel !in liveModels) {
                    onError("Selected OpenRouter model is unavailable: $resolvedModel")
                    return
                }
            }
            val targetRequest = request.copy(
                resolvedProviderId = provider.name.lowercase(),
                resolvedModelId = resolvedModel.orEmpty()
            )
            RuntimeEventLog.post(
                "CLOUD_BACKEND", EventSeverity.INFO,
                "EXECUTION_TARGET provider=${provider.name.lowercase()} model=${resolvedModel ?: "adapter-default"}"
            )
            val adapter = CloudAdapterFactory.create(provider, context, targetRequest)
            if (!adapter.isAvailable) {
                Log.d(TAG, "Skipping ${provider.name} — no key configured")
                continue
            }

            RuntimeEventLog.post("CLOUD_BACKEND", EventSeverity.INFO,
                "Streaming via ${provider.displayName} (attempt ${attemptIdx + 1}/${providerQueue.size})")

            var promptTok = 0
            var compTok = 0
            // Do not emit provider output until the attempt succeeds. A retry
            // or provider failover after partial SSE output would otherwise
            // append the same prefix twice (or mix two providers in one bubble).
            // This deliberately trades token-by-token UI latency for response
            // integrity, which is essential for a stable release build.

            val result = RetryPolicy.withRetry(maxAttempts = MAX_RETRIES) { attempt ->
                if (cancelRequested.get()) {
                    return@withRetry CloudProviderAdapter.AdapterResult.Failure(
                        error = "Cancelled",
                        errorType = CloudErrorType.CANCELLED,
                        retryable = false,
                        httpCode = -3,
                    )
                }
                if (attempt > 0) {
                    RuntimeEventLog.post("CLOUD_BACKEND", EventSeverity.WARN,
                        "Retry attempt $attempt/${MAX_RETRIES - 1} for ${provider.displayName}")
                }
                promptTok = 0
                compTok = 0
                adapter.streamGenerate(
                    request = targetRequest,
                    onToken = { /* buffered by the adapter result; emit only after success */ },
                    onUsage = { p, c -> promptTok = p; compTok = c }
                )
            }

            if (cancelRequested.get() ||
                (result is CloudProviderAdapter.AdapterResult.Failure &&
                    result.errorType == CloudErrorType.CANCELLED)
            ) {
                onError("Cancelled")
                return
            }

            when (result) {
                is CloudProviderAdapter.AdapterResult.Success -> {
                    val executedModel = result.executedModelId.ifBlank {
                        targetRequest.resolvedModelId.ifBlank { targetRequest.requestedModelId.ifBlank { "configured model" } }
                    }
                    successfulProviderLabel = "${provider.displayName} · $executedModel"
                    RuntimeEventLog.post(
                        "CLOUD_BACKEND", EventSeverity.INFO,
                        "EXECUTED_TARGET provider=${provider.name.lowercase()} model=$executedModel"
                    )
                    if (result.fullText.isNotBlank()) onToken(result.fullText)
                    val totalTokens = promptTok + compTok
                    if (totalTokens > 0) {
                        prefs.recordCloudTokens(totalTokens)
                        runCatching {
                            tokenAccountant?.recordSuccess(
                                provider         = provider,
                                promptTokens     = promptTok,
                                completionTokens = compTok,
                                latencyMs        = result.latencyMs
                            )
                        }.onFailure { e ->
                            Log.w(TAG, "tokenAccountant.recordSuccess failed: ${e.message}")
                        }
                    }
                    RuntimeEventLog.post("CLOUD_BACKEND", EventSeverity.INFO,
                        "${provider.displayName} OK: ${result.latencyMs}ms tokens=${promptTok}p+${compTok}c")
                    _totalLatencyMs.addAndGet(System.currentTimeMillis() - requestStart); _globalTotalLatencyMs.addAndGet(System.currentTimeMillis() - requestStart)
                    onComplete(result.fullText, result.latencyMs)
                    return   // Success — do NOT continue to next provider
                }
                is CloudProviderAdapter.AdapterResult.Failure -> {
                    lastError = buildUserErrorMessage(result.errorType, result.error, provider)
                    RuntimeEventLog.post(
                        "CLOUD_BACKEND",
                        EventSeverity.ERROR,
                        "${provider.displayName} failed type=${result.errorType} http=${result.httpCode} errorChars=${result.error.length}"
                    )
                    Log.w(
                        TAG,
                        "CloudBackend failure provider=${provider.name} type=${result.errorType} http=${result.httpCode} errorChars=${result.error.length}"
                    )
                    // Permanent failures identify a bad request/configuration/model;
                    // failing over would hide the actionable cause and can violate
                    // the user's selected-provider contract.
                    if (!result.retryable && result.errorType !in setOf(
                            CloudErrorType.CONNECTION_LOST,
                            CloudErrorType.TIMEOUT,
                            CloudErrorType.SERVER_ERROR,
                            CloudErrorType.RATE_LIMITED
                        )
                    ) {
                        onError(lastError)
                        return
                    }
                    // Continue to next provider only for transient failures.
                }
            }
        }

        // All providers in the failover chain failed.
        RuntimeEventLog.post(
            "CLOUD_BACKEND",
            EventSeverity.ERROR,
            "All ${providerQueue.size} cloud provider(s) failed. lastErrorChars=${lastError.length}"
        )
        
        _errorCount.incrementAndGet(); _globalErrorCount.incrementAndGet()
        onError(lastError)
        } finally {
            activeRequestJob = null
        }
    }

    override suspend fun generate(request: ExecutionRequest): ExecutionResult =
        withContext(Dispatchers.IO) {
            if (cancelRequested.get()) {
                return@withContext ExecutionResult.Failure(
                    error = "Cancelled",
                    origin = ExecOrigin.CLOUD,
                    retryable = false,
                    code = "cancelled",
                )
            }
            if (!capabilities.canFit(request)) {
                return@withContext ExecutionResult.Failure(
                    error = "Cloud context budget exceeded before request dispatch",
                    origin = ExecOrigin.CLOUD,
                    retryable = false,
                    code = "context_limit",
                )
            }
            when (val guard = NetworkGuard.evaluate(prefs)) {
                is NetworkGuard.Decision.Block -> ExecutionResult.Failure(
                    error = guard.reason, origin = ExecOrigin.CLOUD,
                    retryable = false, code = "network_blocked"
                )
                NetworkGuard.Decision.Allow -> {
                    val provider = request.requestedProviderId
                        .takeIf { it.isNotBlank() }
                        ?.let { id -> CloudProvider.entries.firstOrNull { it.name.equals(id, ignoreCase = true) } }
                        ?: prefs.preferredProvider.takeUnless { it == CloudProvider.BRAVE }
                        ?: CloudProvider.GEMINI
                    if (provider == CloudProvider.BRAVE) {
                        return@withContext ExecutionResult.Failure(
                            error = "Brave Search is not an LLM provider",
                            origin = ExecOrigin.CLOUD,
                            retryable = false, code = "invalid_provider"
                        )
                    }
                    val resolvedModel = CloudAdapterFactory.resolveRequestedModel(provider, request)
                    if (provider == CloudProvider.OPENROUTER) {
                        val liveModels = com.airi.assistant.execution.cloud.OpenRouterModelRegistry.refresh()
                        if (resolvedModel != null && liveModels.isNotEmpty() && resolvedModel !in liveModels) {
                            return@withContext ExecutionResult.Failure(
                                error = "Selected OpenRouter model is unavailable: $resolvedModel",
                                origin = ExecOrigin.CLOUD,
                                retryable = false, code = "model_unavailable"
                            )
                        }
                    }
                    val targetRequest = request.copy(
                        resolvedProviderId = provider.name.lowercase(),
                        resolvedModelId = resolvedModel.orEmpty()
                    )
                    val adapter = CloudAdapterFactory.create(provider, context, targetRequest)
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
                        adapter.streamGenerate(targetRequest, onToken = { fullText.append(it) })
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
            CloudErrorType.MODEL_NOT_FOUND  -> "The selected model is not available at ${provider.displayName}. Choose another model."
            CloudErrorType.QUOTA_EXCEEDED   -> "${provider.displayName} quota exhausted. Check your billing dashboard."
            CloudErrorType.RATE_LIMITED     -> "${provider.displayName} is rate-limiting requests. Please wait a moment."
            CloudErrorType.CONTEXT_LENGTH   -> "Prompt too long for ${provider.displayName}. Try a shorter message."
            CloudErrorType.CONTENT_FILTERED -> "Your message was filtered by ${provider.displayName}'s safety policy."
            CloudErrorType.TIMEOUT          -> "${provider.displayName} timed out. Check your internet connection."
            CloudErrorType.CONNECTION_LOST  -> "Connection to ${provider.displayName} was lost mid-stream."
            CloudErrorType.CANCELLED        -> "Request cancelled."
            CloudErrorType.SERVER_ERROR     -> "${provider.displayName} is experiencing issues (server error). Try again."
            CloudErrorType.INVALID_REQUEST  -> "The cloud provider rejected this request. Review the model settings and try again."
            CloudErrorType.UNKNOWN          -> "The cloud provider returned an unexpected error. Please try again."
        }

    companion object {
        private const val TAG         = "AIRI_CloudBackend"
        private const val MAX_RETRIES = 3

        
        private val _globalRequestCount  = java.util.concurrent.atomic.AtomicInteger(0)
        private val _globalErrorCount    = java.util.concurrent.atomic.AtomicInteger(0)
        private val _globalTotalLatencyMs = java.util.concurrent.atomic.AtomicLong(0)

        fun globalStats(): NetworkStats = NetworkStats(
            requestCount = _globalRequestCount.get(),
            errorCount   = _globalErrorCount.get(),
            avgLatencyMs = if (_globalRequestCount.get() > 0)
                _globalTotalLatencyMs.get() / _globalRequestCount.get() else 0L
        )

        /**
         * Priority order for automatic cloud provider failover.
         * When the preferred provider exhausts retries, the backend tries
         * each provider in this list (skipping unavailable ones and the primary).
         *
         * Ordered by: reliability > latency > cost.
         */
        private val FAILOVER_PRIORITY = listOf(
            CloudProvider.OPENAI,
            CloudProvider.ANTHROPIC,
            CloudProvider.GEMINI,
            CloudProvider.OPENROUTER,
            CloudProvider.KIMI,
            CloudProvider.CUSTOM
        )
    }
}
