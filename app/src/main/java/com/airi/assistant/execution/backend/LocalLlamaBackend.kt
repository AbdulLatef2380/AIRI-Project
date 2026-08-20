package com.airi.assistant.execution.backend

import android.util.Log
import com.airi.assistant.ai.LlamaManager
import com.airi.assistant.ai.ModelCapabilities
import com.airi.assistant.ai.ModelManager
import com.airi.assistant.execution.CapabilityProfile
import com.airi.assistant.ai.context.ContextBudget
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.ExecutionResult
import com.airi.assistant.execution.accounting.TokenAccountant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/**
 * Local llama.cpp runtime backend.
 *
 * Wraps [LlamaManager] and exposes it through the [RuntimeBackend] interface
 * so [RuntimeRouter] and [HybridOrchestrator] can treat local inference
 * identically to cloud inference — both as interchangeable capability-declared
 * backends.
 *
 * ## Token Accounting ()
 * When [tokenAccountant] is provided, each successful generation is recorded via
 * [TokenAccountant.recordLocal]. The character length of the full generated text
 * is used as the token count input (estimation mode); the actual native
 * nativeTokenCount is not surfaced through the [RuntimeBackend] interface.
 * Recording uses [TokenAccountant.LOCAL_CHARS_PER_TOKEN] for the conversion.
 *
 * ## Capability profile
 * Built lazily from the currently loaded model's detected capabilities.
 * Vision support is true only when an mmproj projector is loaded.
 * Context window is conservatively reported as 4096 tokens.
 *
 * ## Availability
 * [isAvailable] is true only when a model is loaded. The router uses this
 * to route to cloud when local is genuinely unavailable.
 *
 * ## Streaming
 * Delegates entirely to [LlamaManager.generateStream] — the existing
 * lifecycle-lock, watchdog, and cancellation behaviour is preserved.
 *
 * ## Batch generation
 * [generate] collects the streaming output via [CompletableDeferred],
 * suspending the caller until [LlamaManager]'s onComplete or onError
 * callbacks fire. Used by tool pipelines and tests only.
 */
class LocalLlamaBackend(
    private val llamaManager:    LlamaManager,
    private val tokenAccountant: TokenAccountant? = null
) : RuntimeBackend {

    override val id:          String     = "local_llama"
    override val displayName: String     = "Local (llama.cpp)"
    override val origin:      ExecOrigin = ExecOrigin.LOCAL

    /**
     * SPRINT 1: Capability profile derived from the live ContextBudget.
     *
     * Before Sprint 1 this returned CapabilityProfile.LOCAL_CPU.copy(supportsVision=...)
     * which hardcoded maxContextTokens=4096 and supportsLongContext=false regardless of
     * the actual loaded model. Now it calls CapabilityProfile.forLocalModel() with the
     * live budget from LlamaManager so:
     *   - maxContextTokens = actual nCtx from getNCtx() (e.g. 8192, 32768)
     *   - supportsLongContext = true for 8K+ models → router won't wrongly
     *     send long-context requests to cloud when a capable local model is loaded.
     */
    override val capabilities: CapabilityProfile
        get() {
            val model     = ModelManager.getCurrent()
            val hasVision = model?.let {
                runCatching { ModelCapabilities.detect(it).vision }.getOrDefault(false)
            } ?: false
            val budget = llamaManager.contextBudget
            return if (budget.nCtx > ContextBudget.UNLOADED.nCtx || model != null) {
                CapabilityProfile.forLocalModel(budget, hasVision)
            } else {
                CapabilityProfile.LOCAL_CPU.copy(supportsVision = hasVision)
            }
        }

    override val isAvailable: Boolean
        get() = ModelManager.getCurrent() != null
        // LlamaManager protects concurrent access via its internal Mutex.
        // We do not expose an isGenerating flag — the backend simply
        // attempts the call; if a generation is already running it will
        // queue behind the Mutex and proceed when the lock is released.

    /**
     * Propagate cancellation to the native engine.
     * Previously missing: HybridOrchestrator.cancel() set its own AtomicBoolean
     * but never reached LlamaNative.nativeCancel(), letting llama_decode() run
     * unchecked for the full prefill duration (~180 s) — the direct ANR cause.
     * Lock-free: only sets atomics, never acquires lifecycleLock.
     */
    override fun cancelStream() {
        Log.i(TAG, "cancelStream() → propagating to LlamaManager")
        llamaManager.cancelStream()
    }

    /** Event type used to bridge non-suspend LlamaManager callbacks to suspend callers. */
    private sealed class LlamaEvent {
        data class Token(val value: String)                      : LlamaEvent()
        data class Complete(val text: String, val latency: Long) : LlamaEvent()
        data class Error(val message: String)                    : LlamaEvent()
    }

    override suspend fun generateStream(
        request:    ExecutionRequest,
        onToken:    suspend (String) -> Unit,
        onComplete: suspend (String, Long) -> Unit,
        onError:    suspend (String)       -> Unit
    ) {
        if (!isAvailable) {
            onError("LocalLlamaBackend: no model loaded")
            return
        }
        val startMs  = System.currentTimeMillis()
        val fullText = StringBuilder()

        // Use an unlimited Channel to bridge non-suspend LlamaManager callbacks
        // back to this suspend caller without blocking any thread.
        val events = Channel<LlamaEvent>(Channel.UNLIMITED)

        llamaManager.generateStream(
            prompt         = request.prompt,
            systemPrompt   = request.systemPrompt,
            maxTokens      = request.maxTokens,
            temperature    = request.temperature,
            repeatPenalty  = 1.1f,
            timeoutMs      = 120_000L,
            onToken        = { token ->
                fullText.append(token)
                events.trySend(LlamaEvent.Token(token))
            },
            onComplete     = { _ ->
                events.trySend(LlamaEvent.Complete(fullText.toString(), System.currentTimeMillis() - startMs))
                events.close()
            },
            onError        = { error ->
                Log.w(TAG, "generateStream error: $error")
                events.trySend(LlamaEvent.Error(error))
                events.close()
            },
            onStallWarning = {
                Log.w(TAG, "generateStream: stall warning")
            }
        )

        // Drain events on the caller's coroutine dispatcher — no thread is blocked.
        for (event in events) {
            when (event) {
                is LlamaEvent.Token    -> onToken(event.value)
                is LlamaEvent.Complete -> {
                    
                    // since nativeTokenCount is not surfaced through this interface.
                    tokenAccountant?.let { accountant ->
                        runCatching {
                            accountant.recordLocal(
                                tokensGenerated = event.text.length,
                                latencyMs       = event.latency,
                                isExactCount    = false
                            )
                        }.onFailure { e ->
                            Log.w(TAG, "tokenAccountant.recordLocal failed: ${e.message}")
                        }
                    }
                    onComplete(event.text, event.latency)
                }
                is LlamaEvent.Error    -> onError(event.message)
            }
        }
    }

    /**
     * Non-streaming (batch) generation via [CompletableDeferred].
     *
     * [LlamaManager.generateStream] is a non-suspend function that launches
     * async work on its internal llamaDispatcher. The callbacks (onComplete /
     * onError) fire on the Main thread. [CompletableDeferred.await] suspends
     * the caller until one of them fires, making this a clean coroutine bridge.
     */
    override suspend fun generate(request: ExecutionRequest): ExecutionResult {
        if (!isAvailable) {
            return ExecutionResult.Failure(
                error     = "No local model loaded",
                origin    = ExecOrigin.LOCAL,
                retryable = false,
                code      = "not_loaded"
            )
        }

        val startMs  = System.currentTimeMillis()
        val fullText = StringBuilder()
        val deferred = CompletableDeferred<ExecutionResult>()

        llamaManager.generateStream(
            prompt         = request.prompt,
            systemPrompt   = request.systemPrompt,
            maxTokens      = request.maxTokens,
            temperature    = request.temperature,
            repeatPenalty  = 1.1f,
            timeoutMs      = 90_000L,
            onToken        = { token -> fullText.append(token) },
            onComplete     = { _ ->
                val latencyMs = System.currentTimeMillis() - startMs
                deferred.complete(ExecutionResult.Success(
                    fullText   = fullText.toString(),
                    origin     = ExecOrigin.LOCAL,
                    latencyMs  = latencyMs,
                    tokenCount = fullText.count { it == ' ' } + 1,
                    provider   = "llama.cpp"
                ))
            },
            onError        = { error ->
                deferred.complete(ExecutionResult.Failure(
                    error  = error,
                    origin = ExecOrigin.LOCAL,
                    code   = "local_error"
                ))
            },
            onStallWarning = {
                Log.w(TAG, "generate: stall warning")
            }
        )

        // Await the result in suspend context — this is the correct place to call
        // suspend functions. tokenAccountant.recordLocal is a suspend fun (uses Mutex)
        // and cannot be called inside the non-suspend onComplete lambda above.
        
        val result = deferred.await()
        if (result is ExecutionResult.Success) {
            tokenAccountant?.let { accountant ->
                runCatching {
                    accountant.recordLocal(
                        tokensGenerated = result.fullText.length,
                        latencyMs       = result.latencyMs,
                        isExactCount    = false
                    )
                }.onFailure { e ->
                    Log.w(TAG, "tokenAccountant.recordLocal failed: ${e.message}")
                }
            }
        }
        return result
    }

    companion object {
        private const val TAG = "AIRI_LocalLlamaBackend"
    }
}
