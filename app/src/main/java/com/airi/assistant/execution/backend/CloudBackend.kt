package com.airi.assistant.execution.backend

import android.util.Log
import com.airi.assistant.ai.remote.RemoteModel
import com.airi.assistant.ai.remote.RemoteModelExecutor
import com.airi.assistant.ai.remote.RemoteModelRegistry
import com.airi.assistant.execution.CapabilityProfile
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.ExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud runtime backend.
 *
 * Wraps the existing [RemoteModelExecutor] (OpenAI-compatible SSE streaming)
 * and [RemoteModelRegistry] (active provider selection) without replacing them.
 * This backend is the clean interface through which [RuntimeRouter] and
 * [HybridOrchestrator] access all cloud providers.
 *
 * ## Provider selection
 * Uses [RemoteModelRegistry.getActive()] for the active provider at call time.
 * This intentionally defers provider selection to the last moment so changes
 * made in Settings take effect immediately on the next request.
 *
 * ## Capability profile
 * Cloud is declared as CLOUD_STREAMING by default (supportsLongContext,
 * supportsFastReasoning, supportsVision). Callers should not hard-code
 * provider-specific capabilities here — the profile represents the union of
 * what a typical cloud endpoint can do.
 *
 * ## Cancellation
 * The underlying [RemoteModelExecutor.generateStream] runs on [Dispatchers.IO]
 * and cooperates with coroutine cancellation through the structured concurrency
 * of the call site. The HTTP connection is closed on IOException which is
 * propagated on cancellation.
 */
class CloudBackend(
    private val executor: RemoteModelExecutor = RemoteModelExecutor()
) : RuntimeBackend {

    override val id:          String          = "cloud"
    override val displayName: String          = "Cloud"
    override val capabilities: CapabilityProfile = CapabilityProfile.CLOUD_STREAMING
    override val origin:       ExecOrigin     = ExecOrigin.CLOUD

    override val isAvailable: Boolean
        get() = RemoteModelRegistry.getActive() != null

    override suspend fun generateStream(
        request:    ExecutionRequest,
        onToken:    suspend (String) -> Unit,
        onComplete: suspend (String, Long) -> Unit,
        onError:    suspend (String)       -> Unit
    ) {
        val remote = RemoteModelRegistry.getActive()
        if (remote == null) {
            onError("CloudBackend: no remote provider configured")
            return
        }

        val startMs  = System.currentTimeMillis()
        val fullText = StringBuilder()

        val result = runCatching {
            executor.generateStream(
                model        = remote,
                prompt       = request.prompt,
                systemPrompt = request.systemPrompt,
                maxTokens    = request.maxTokens,
                temperature  = request.temperature,
                onToken      = { token ->
                    fullText.append(token)
                    onToken(token)
                }
            )
        }.getOrElse { e ->
            RemoteModelExecutor.RemoteResult.Failure(e.message ?: "CloudBackend exception")
        }

        val latencyMs = System.currentTimeMillis() - startMs
        when (result) {
            is RemoteModelExecutor.RemoteResult.Success ->
                onComplete(fullText.toString(), latencyMs)
            is RemoteModelExecutor.RemoteResult.Failure -> {
                Log.w(TAG, "CloudBackend stream failed: ${result.error}")
                onError(result.error)
            }
        }
    }

    override suspend fun generate(request: ExecutionRequest): ExecutionResult {
        val remote = RemoteModelRegistry.getActive()
            ?: return ExecutionResult.Failure(
                error     = "No remote provider configured",
                origin    = ExecOrigin.CLOUD,
                retryable = false,
                code      = "not_configured"
            )

        val startMs = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            when (val r = executor.generate(
                model        = remote,
                prompt       = request.prompt,
                systemPrompt = request.systemPrompt,
                maxTokens    = request.maxTokens,
                temperature  = request.temperature
            )) {
                is RemoteModelExecutor.RemoteResult.Success ->
                    ExecutionResult.Success(
                        fullText   = r.text,
                        origin     = ExecOrigin.CLOUD,
                        latencyMs  = System.currentTimeMillis() - startMs,
                        provider   = remote.name
                    )
                is RemoteModelExecutor.RemoteResult.Failure ->
                    ExecutionResult.Failure(
                        error     = r.error,
                        origin    = ExecOrigin.CLOUD,
                        retryable = true,
                        code      = "remote_failure"
                    )
            }
        }
    }

    companion object {
        private const val TAG = "AIRI_CloudBackend"
    }
}
