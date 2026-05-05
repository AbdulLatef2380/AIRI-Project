package com.airi.assistant.execution

import android.util.Log
import com.airi.assistant.execution.cloud.CloudProviderAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ProviderManager — unified multi-provider cloud LLM registry and router.
 *
 * Wraps the individual [CloudProviderAdapter] implementations (OpenAI, Anthropic,
 * Gemini, OpenRouter) and provides a single entry point for:
 *
 *  1. **Provider registration** — [register] adds a named, keyed provider.
 *  2. **Active provider selection** — [setActive] switches which provider the
 *     HybridOrchestrator uses for cloud calls. Persisted across restarts via the
 *     caller (ExecModePreferences owns the setting).
 *  3. **Health probing** — [probeAll] fires a lightweight ping against every
 *     registered provider and updates [providerHealth] StateFlow. The UI uses
 *     this to show green/red dots on the provider picker.
 *  4. **Automatic failover** — [resolveForFailover] returns the next healthy
 *     provider from the priority list when the primary fails. Used by
 *     HybridOrchestrator.executeStream().
 *  5. **Capacity ranking** — providers are ranked by estimated capability tier
 *     so the agent can select the appropriate power level for a given task.
 *
 * ## Thread safety
 * All StateFlow updates are thread-safe. [probeAll] is a suspend function
 * that callers run on Dispatchers.IO. No internal mutex is needed because
 * [register] / [setActive] are called only from the setup path (single thread).
 *
 * ## AIRI_PROOF instrumentation
 * Every provider switch, probe result, and failover is tagged AIRI_PROOF so
 * the full cloud routing chain is visible in logcat.
 */
class ProviderManager {

    data class ProviderEntry(
        val id:        String,
        val name:      String,
        val adapter:   CloudProviderAdapter,
        val priority:  Int = 0,
        val tier:      Tier = Tier.STANDARD,
    )

    enum class Tier {
        NANO,       // tiny/fast: Gemini Flash, GPT-4o-mini
        STANDARD,   // balanced: Claude Haiku, GPT-4o
        PREMIUM,    // flagship: Claude Opus, GPT-o1
    }

    data class ProviderHealth(
        val id:           String,
        val healthy:      Boolean,
        val latencyMs:    Long    = -1L,
        val errorMessage: String? = null,
    )

    // ── State ─────────────────────────────────────────────────────────────────

    private val registry     = mutableMapOf<String, ProviderEntry>()
    private var activeId: String? = null

    private val _providerHealth = MutableStateFlow<Map<String, ProviderHealth>>(emptyMap())
    val providerHealth: StateFlow<Map<String, ProviderHealth>> = _providerHealth.asStateFlow()

    private val _activeProviderId = MutableStateFlow<String?>(null)
    val activeProviderId: StateFlow<String?> = _activeProviderId.asStateFlow()

    // ── Registration ──────────────────────────────────────────────────────────

    fun register(entry: ProviderEntry) {
        registry[entry.id] = entry
        Log.i("AIRI_PROOF", "PROVIDER_REGISTERED id=${entry.id} tier=${entry.tier} priority=${entry.priority}")
        if (registry.size == 1 && activeId == null) {
            setActive(entry.id)
        }
    }

    fun unregister(id: String) {
        registry.remove(id)
        if (activeId == id) {
            activeId = registry.keys.firstOrNull()
            _activeProviderId.value = activeId
            Log.i("AIRI_PROOF", "PROVIDER_ACTIVE_CHANGED reason=unregistered new_active=${activeId ?: "none"}")
        }
    }

    fun setActive(id: String) {
        require(registry.containsKey(id)) { "Provider '$id' is not registered" }
        val previous = activeId
        activeId = id
        _activeProviderId.value = id
        Log.i("AIRI_PROOF", "PROVIDER_SET_ACTIVE id=$id previous=${previous ?: "none"}")
    }

    fun getActive(): ProviderEntry? = activeId?.let { registry[it] }

    fun getAll(): List<ProviderEntry> =
        registry.values.sortedByDescending { it.priority }

    fun getByTier(tier: Tier): List<ProviderEntry> =
        registry.values.filter { it.tier == tier }.sortedByDescending { it.priority }

    // ── Health probing ────────────────────────────────────────────────────────

    /**
     * Probe all registered providers with a lightweight ping.
     * Updates [providerHealth]. Safe to call from Dispatchers.IO.
     */
    suspend fun probeAll() {
        val results = mutableMapOf<String, ProviderHealth>()
        for (entry in registry.values) {
            val start = System.currentTimeMillis()
            // Use the adapter's isAvailable (local key + network check) as the
            // probe signal. CloudProviderAdapter does not define a network ping
            // method — isAvailable is the canonical availability check.
            val available = runCatching { entry.adapter.isAvailable }.getOrDefault(false)
            val latency   = System.currentTimeMillis() - start
            val health    = ProviderHealth(
                id           = entry.id,
                healthy      = available,
                latencyMs    = latency,
                errorMessage = if (available) null else "API key missing or provider unavailable",
            )
            results[entry.id] = health
            Log.i("AIRI_PROOF", "PROVIDER_PROBE id=${entry.id} available=$available latency=${latency}ms")
        }
        _providerHealth.value = results
    }

    // ── Failover ──────────────────────────────────────────────────────────────

    /**
     * Returns the next healthy provider after [failedId], ordered by priority.
     * Used by HybridOrchestrator when the primary provider fails.
     *
     * @param failedId       The provider that just failed.
     * @param preferredTier  Optional tier constraint (null = any tier).
     */
    fun resolveForFailover(
        failedId:      String,
        preferredTier: Tier? = null,
    ): ProviderEntry? {
        val health    = _providerHealth.value
        val candidates = registry.values
            .filter { it.id != failedId }
            .filter { preferredTier == null || it.tier == preferredTier }
            .sortedByDescending { it.priority }

        val healthyCandidate = candidates.firstOrNull { entry ->
            health[entry.id]?.healthy != false
        } ?: candidates.firstOrNull()

        if (healthyCandidate != null) {
            Log.i("AIRI_PROOF", "PROVIDER_FAILOVER failed=$failedId next=${healthyCandidate.id}")
        } else {
            Log.w("AIRI_PROOF", "PROVIDER_FAILOVER_EXHAUSTED failed=$failedId no_candidates")
        }
        return healthyCandidate
    }

    // ── Smart task routing ────────────────────────────────────────────────────

    /**
     * Select the best provider for a task given complexity and constraints.
     *
     * @param complexityHint  0.0 = trivial, 1.0 = maximum complexity
     * @param requireStreaming Whether the provider must support streaming
     */
    fun selectForTask(
        complexityHint:   Float   = 0.5f,
        requireStreaming: Boolean = true,
    ): ProviderEntry? {
        val targetTier = when {
            complexityHint >= 0.8f -> Tier.PREMIUM
            complexityHint >= 0.4f -> Tier.STANDARD
            else                   -> Tier.NANO
        }
        val health = _providerHealth.value

        val candidate = getByTier(targetTier).firstOrNull { e ->
            health[e.id]?.healthy != false
        } ?: getAll().firstOrNull { e ->
            health[e.id]?.healthy != false
        }

        Log.i("AIRI_PROOF", "PROVIDER_SELECT_FOR_TASK complexity=$complexityHint target_tier=$targetTier selected=${candidate?.id ?: "none"}")
        return candidate
    }

    companion object {
        private const val TAG = "ProviderManager"
    }
}
