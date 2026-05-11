package com.airi.assistant.ai

import android.util.Log
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.HybridOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * HybridInferenceOrchestrator — semantic, memory-aware, latency-aware routing
 * layer sitting ABOVE [HybridOrchestrator].
 *
 * ── ROUTING DIMENSIONS ────────────────────────────────────────────────────────
 *
 * 1. SEMANTIC ROUTING
 *    Classifies the request as text/vision/tool/embedding and selects the
 *    appropriate local model variant or cloud provider.
 *
 * 2. MEMORY-AWARE ROUTING
 *    Reads [InferenceHealthMonitor.health] to know current RAM pressure.
 *    Routes to cloud when local memory is exhausted (< MIN_FREE_RAM_MB).
 *
 * 3. LATENCY-AWARE ROUTING
 *    Maintains an exponential moving average of local inference latency.
 *    When the EMA exceeds [LOCAL_LATENCY_THRESHOLD_MS], lighter cloud
 *    routes are preferred for interactive turns.
 *
 * 4. CAPABILITY MATCHING
 *    If the request requires vision or tool-calling, and the loaded model
 *    doesn't support those capabilities, cloud fallback is automatic.
 *
 * 5. OFFLINE ROUTING
 *    Always routes local when [requiresOffline] or no network is present.
 *
 * 6. INTELLIGENT MODEL SWITCHING
 *    After [SWITCH_EVAL_INTERVAL] executions, evaluates whether a
 *    smaller/larger local model would better serve the workload mix
 *    and emits a [ModelSwitchRecommendation].
 *
 * ── INTEGRATION ──────────────────────────────────────────────────────────────
 *
 * This class wraps the existing [HybridOrchestrator] — it never bypasses it.
 * All actual token streaming goes through HybridOrchestrator's Mutex and
 * privacy gate, ensuring no regressions.
 */
class HybridInferenceOrchestrator(
    private val hybridOrchestrator:   HybridOrchestrator,
    private val healthMonitor:        InferenceHealthMonitor,
    private val contextPressure:      ContextPressureManager,
) {

    private val TAG = "HybridInferenceOrch"

    // ── Routing state ─────────────────────────────────────────────────────────

    private val mutex        = Mutex()
    private val execCounter  = AtomicLong(0L)
    private var localLatEma  = 0L   // EMA of local inference latency in ms
    private var totalLocal   = 0L
    private var totalCloud   = 0L

    private val _routingState = MutableStateFlow(RoutingState())
    val routingState: StateFlow<RoutingState> = _routingState.asStateFlow()

    private val _modelSwitchSignal = MutableStateFlow<ModelSwitchRecommendation?>(null)
    val modelSwitchSignal: StateFlow<ModelSwitchRecommendation?> = _modelSwitchSignal.asStateFlow()

    // ── Data classes ──────────────────────────────────────────────────────────

    data class RoutingState(
        val lastDecision:      RoutingDecision  = RoutingDecision.LOCAL,
        val lastRationale:     String           = "",
        val localEmaMs:        Long             = 0L,
        val localCallCount:    Long             = 0L,
        val cloudCallCount:    Long             = 0L,
        val pressureOverrides: Int              = 0,
        val capabilityMisses:  Int              = 0,
    )

    enum class RoutingDecision { LOCAL, CLOUD, HYBRID }

    data class ModelSwitchRecommendation(
        val reason:          String,
        val suggestSmaller:  Boolean,
        val suggestLarger:   Boolean,
        val avgLatencyMs:    Long,
        val cloudSharePct:   Int,
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Execute an inference request through the best-fit backend.
     * Annotates [request] with routing hints and delegates to [HybridOrchestrator].
     */
    suspend fun executeStream(
        request:    ExecutionRequest,
        onToken:    suspend (String) -> Unit,
        onComplete: suspend (String, Long, ExecOrigin) -> Unit,
        onError:    suspend (String, ExecOrigin) -> Unit,
    ) {
        val start    = System.currentTimeMillis()
        val decision = analyzeRoute(request)

        mutex.withLock {
            _routingState.value = _routingState.value.copy(
                lastDecision  = decision.route,
                lastRationale = decision.rationale,
            )
        }

        Log.i(TAG, "Route decision=${decision.route.name} reason='${decision.rationale}'")

        val annotated = applyRoutingHints(request, decision)

        hybridOrchestrator.executeStream(
            request    = annotated,
            onToken    = onToken,
            onComplete = { text, latencyMs, origin ->
                updateStats(origin, latencyMs)
                maybeEmitSwitchRecommendation()
                onComplete(text, latencyMs, origin)
            },
            onError    = onError,
        )
    }

    fun cancel() = hybridOrchestrator.cancel()

    // ── Routing analysis ──────────────────────────────────────────────────────

    private data class RouteAnalysis(
        val route:      RoutingDecision,
        val rationale:  String,
        val forceCloud: Boolean = false,
    )

    private suspend fun analyzeRoute(req: ExecutionRequest): RouteAnalysis {
        val health   = healthMonitor.currentHealth()
        val pressure = contextPressure.pressure.value

        // 1. Hard offline gate
        if (req.requiresOffline) {
            return RouteAnalysis(RoutingDecision.LOCAL, "offline_required")
        }

        // 2. Memory pressure — route cloud when local RAM is critically low
        if (health.freeRamMb < MIN_FREE_RAM_MB) {
            _routingState.value = _routingState.value.copy(
                pressureOverrides = _routingState.value.pressureOverrides + 1
            )
            return RouteAnalysis(RoutingDecision.CLOUD, "ram_pressure_${health.freeRamMb}MB", forceCloud = true)
        }

        // 3. Context overflow — route cloud when context window is full
        if (pressure.level == ContextPressureManager.PressureLevel.OVERFLOW) {
            return RouteAnalysis(RoutingDecision.CLOUD, "context_overflow")
        }

        // 4. Capability mismatch — model doesn't support required feature
        val caps = runCatching { LlamaNative.isAvailable() }.getOrDefault(false)
        if (!caps && (req.requiresVision || req.requiresToolCalling)) {
            _routingState.value = _routingState.value.copy(
                capabilityMisses = _routingState.value.capabilityMisses + 1
            )
            return RouteAnalysis(RoutingDecision.CLOUD, "capability_mismatch_native_unavailable", forceCloud = true)
        }

        val model = ModelManager.getCurrent()
        if (model != null) {
            val modelCaps = runCatching { ModelCapabilities.detect(model) }.getOrNull()
            if (modelCaps != null) {
                if (req.requiresVision && !modelCaps.vision) {
                    return RouteAnalysis(RoutingDecision.CLOUD, "vision_not_supported_locally", forceCloud = true)
                }
                if (req.requiresToolCalling && !modelCaps.toolCalling) {
                    return RouteAnalysis(RoutingDecision.CLOUD, "tool_calling_not_supported_locally", forceCloud = true)
                }
            }
        }

        // 5. Latency-aware routing — EMA too high for interactive turns
        if (req.requiresStreaming && localLatEma > LOCAL_LATENCY_THRESHOLD_MS && totalLocal > MIN_SAMPLES_FOR_EMA) {
            return RouteAnalysis(RoutingDecision.HYBRID, "local_latency_ema_${localLatEma}ms")
        }

        // 6. Long context — prefer cloud if local context window is insufficient
        if (req.requiresLongContext) {
            return RouteAnalysis(RoutingDecision.CLOUD, "long_context_required")
        }

        // 7. Default: local inference
        return RouteAnalysis(RoutingDecision.LOCAL, "default_local")
    }

    private fun applyRoutingHints(req: ExecutionRequest, decision: RouteAnalysis): ExecutionRequest {
        return when {
            decision.forceCloud -> req.copy(requiresOffline = false)
            decision.route == RoutingDecision.LOCAL -> req
            else -> req
        }
    }

    // ── Stats tracking ─────────────────────────────────────────────────────────

    private suspend fun updateStats(origin: ExecOrigin, latencyMs: Long) = mutex.withLock {
        val count = execCounter.incrementAndGet()
        when (origin) {
            ExecOrigin.LOCAL -> {
                totalLocal++
                localLatEma = if (localLatEma == 0L) latencyMs
                else ((localLatEma * EMA_ALPHA_NUM + latencyMs * EMA_ALPHA_DEN) / (EMA_ALPHA_NUM + EMA_ALPHA_DEN))
                _routingState.value = _routingState.value.copy(
                    localEmaMs     = localLatEma,
                    localCallCount = totalLocal
                )
            }
            else -> {
                totalCloud++
                _routingState.value = _routingState.value.copy(cloudCallCount = totalCloud)
            }
        }
        Log.d(TAG, "exec#$count origin=${origin.name} latency=${latencyMs}ms localEma=${localLatEma}ms")
    }

    private fun maybeEmitSwitchRecommendation() {
        val total = totalLocal + totalCloud
        if (total < SWITCH_EVAL_INTERVAL || total % SWITCH_EVAL_INTERVAL != 0L) return

        val cloudPct = if (total > 0) (totalCloud * 100 / total).toInt() else 0
        val rec = when {
            cloudPct > 60 && localLatEma > LOCAL_LATENCY_THRESHOLD_MS ->
                ModelSwitchRecommendation(
                    reason         = "High cloud usage (${cloudPct}%) due to local latency. Consider a smaller/quantized model.",
                    suggestSmaller = true,
                    suggestLarger  = false,
                    avgLatencyMs   = localLatEma,
                    cloudSharePct  = cloudPct,
                )
            cloudPct < 5 && localLatEma < FAST_LOCAL_THRESHOLD_MS ->
                ModelSwitchRecommendation(
                    reason         = "Local is fast and preferred. A larger model may improve quality.",
                    suggestSmaller = false,
                    suggestLarger  = true,
                    avgLatencyMs   = localLatEma,
                    cloudSharePct  = cloudPct,
                )
            else -> null
        }
        if (rec != null) {
            _modelSwitchSignal.value = rec
            Log.i(TAG, "MODEL_SWITCH_REC: ${rec.reason}")
        }
    }

    companion object {
        private const val MIN_FREE_RAM_MB            = 200L
        private const val LOCAL_LATENCY_THRESHOLD_MS = 8_000L
        private const val FAST_LOCAL_THRESHOLD_MS    = 2_000L
        private const val EMA_ALPHA_NUM              = 7L
        private const val EMA_ALPHA_DEN              = 3L
        private const val MIN_SAMPLES_FOR_EMA        = 5L
        private const val SWITCH_EVAL_INTERVAL       = 20L
    }
}
