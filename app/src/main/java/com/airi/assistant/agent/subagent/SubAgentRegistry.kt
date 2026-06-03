package com.airi.assistant.agent.subagent

import android.util.Log
import com.airi.assistant.agent.learning.reinforcement.AdaptivePolicy

/**
 * Central registry of all AIRI sub-agents.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * ROUTING ALGORITHM
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   1. Keyword pass: check if any [SubAgentCapability.intentKeywords]
 *      appears in the normalized input (case-insensitive, word boundary).
 *   2. For all keyword-matched agents, call [SubAgent.canHandle] in
 *      priority order (highest score first).
 *   3. Return the first agent that returns true from [SubAgent.canHandle].
 *   4. If no agent matches, return null — the orchestrator falls back to
 *      the general LLM path via [AgentController].
 *
 * ─────────────────────────────────────────────────────────────────────────
 * REGISTRATION
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Agents are registered once at application startup via [initialize].
 * Third-party agents (skills, plugins) can register via [register].
 * The registry is read-only after [freeze] is called.
 */
object SubAgentRegistry {

    private const val TAG = "SubAgentRegistry"

    private val agents = mutableListOf<SubAgent>()
    @Volatile private var frozen = false

    // ── Runtime capability grants (set by system components at runtime) ────────
    //
    // Example: AiriAccessibilityService sets "airi_accessibility_enabled"
    //          when it connects, unlocking AndroidAgent routing.
    //          This is separate from Android runtime permissions.

    private val runtimeCapabilities = mutableSetOf<String>()

    /**
     * Grant a runtime capability token. Call from system components (e.g., services)
     * when the underlying resource becomes available. Thread-safe.
     */
    @Synchronized
    fun grantCapability(capability: String) {
        runtimeCapabilities.add(capability)
        Log.i(TAG, "Runtime capability granted: $capability")
    }

    /**
     * Revoke a runtime capability token. Call when the underlying resource disconnects.
     */
    @Synchronized
    fun revokeCapability(capability: String) {
        runtimeCapabilities.remove(capability)
        Log.i(TAG, "Runtime capability revoked: $capability")
    }

    /** Check if a runtime capability token has been granted. */
    @Synchronized
    fun hasCapability(capability: String): Boolean = runtimeCapabilities.contains(capability)

    // ── Setup ──────────────────────────────────────────────────────────────────

    /**
     * Initialize the registry with a pre-built list of sub-agents.
     *
     * Callers (ServiceLocator.initSubAgentSystem) are responsible for constructing
     * agents with their tool dependencies injected. This allows each agent to receive
     * real CalendarTool, AlarmTool, SearchTool, etc. at startup instead of
     * constructing them with no-arg stubs.
     *
     * @param agentList Pre-built agents with real tool dependencies injected.
     *                  Defaults to empty — must pass agents explicitly.
     */
    fun initialize(agentList: List<SubAgent>) {
        if (frozen) {
            Log.w(TAG, "initialize() called after freeze() — ignored")
            return
        }
        agents.clear()
        agents.addAll(agentList)
        Log.i(TAG, "SubAgentRegistry initialized with ${agents.size} agents: " +
                agents.joinToString { it.capability.agentId })
    }

    /** Return an immutable snapshot of all registered agents. */
    fun getAll(): List<SubAgent> = agents.toList()

    /**
     * Returns a snapshot of all runtime capability tokens currently granted.
     * Used by ChatViewModel when building [SubAgentContext.grantedPermissions]
     * so the routing permission gate can see both Android permissions and
     * AIRI runtime tokens (e.g. [AndroidAgent.CAPABILITY_ACCESSIBILITY]).
     */
    @Synchronized
    fun activeCapabilities(): List<String> = runtimeCapabilities.toList()

    /**
     * Register an additional sub-agent (plugin / skill marketplace).
     * Must be called before [freeze].
     */
    fun register(agent: SubAgent) {
        check(!frozen) { "SubAgentRegistry is frozen — cannot register new agents" }
        val existing = agents.find { it.capability.agentId == agent.capability.agentId }
        if (existing != null) {
            Log.w(TAG, "Agent '${agent.capability.agentId}' already registered — replacing")
            agents.remove(existing)
        }
        agents.add(agent)
        Log.i(TAG, "Registered agent: ${agent.capability.displayName}")
    }

    /**
     * Prevent further registrations (call after all plugins have been loaded).
     */
    fun freeze() {
        frozen = true
        Log.i(TAG, "SubAgentRegistry frozen with ${agents.size} agents")
    }

    // ── Routing ────────────────────────────────────────────────────────────────

    /**
     * Find the best [SubAgent] for the given [input] and [context].
     *
     * Returns null if no agent can handle the input (caller falls back to LLM).
     * Suspended because [SubAgent.canHandle] may do lightweight I/O.
     */
    suspend fun route(input: String, context: SubAgentContext): SubAgent? {
        val normalized = input.lowercase().trim()

        // Step 1: Keyword scoring
        val scored = agents
            .filter { agent ->
                // Privacy gate: block cloud agents when privacy = MAXIMUM
                if (agent.capability.requiresCloud && !context.cloudAllowed) return@filter false
                // Permission gate: block agents with ungranted permissions
                if (agent.capability.requiredPermissions.isNotEmpty()) {
                    val ungranted = agent.capability.requiredPermissions
                        .filterNot { context.grantedPermissions.contains(it) }
                    if (ungranted.isNotEmpty()) {
                        Log.d(TAG, "Agent '${agent.capability.agentId}' blocked — missing: $ungranted")
                        return@filter false
                    }
                }
                // Keyword match
                agent.capability.intentKeywords.any { kw ->
                    normalized.contains(kw.lowercase())
                }
            }
            .map { agent ->
                // Score = keyword matches × domain boost + learned preference signal
                val keywordScore = agent.capability.intentKeywords.count { kw ->
                    normalized.contains(kw.lowercase())
                }
                val domainScore = agent.capability.domains.count { d ->
                    normalized.contains(d.lowercase())
                }
                val rawScore = keywordScore * 2 + domainScore
                val adaptedScore = AdaptivePolicy.adjustScore(
                    baseScore = rawScore,
                    context   = "routing",
                    key       = agent.capability.agentId
                )
                agent to adaptedScore
            }
            .sortedByDescending { (_, score) -> score }

        if (scored.isEmpty()) {
            Log.d(TAG, "No keyword match for input='${input.take(60)}'")
            return null
        }

        // Step 2: Fine-grained canHandle check in score order
        for ((agent, score) in scored) {
            Log.d(TAG, "Checking '${agent.capability.agentId}' score=$score")
            val result = runCatching { agent.canHandle(input, context) }.getOrElse { false }
            if (result) {
                Log.i(TAG, "Routed to '${agent.capability.displayName}' score=$score")
                return agent
            }
        }

        Log.d(TAG, "No agent confirmed canHandle for input='${input.take(60)}'")
        return null
    }

    /**
     * Resolve an agent by its stable [agentId].
     * Used by the orchestrator for explicit delegation ([AgentEvent.Delegate]).
     */
    fun findById(agentId: String): SubAgent? =
        agents.find { it.capability.agentId == agentId }

    /** All registered agents (read-only snapshot). */
    fun all(): List<SubAgent> = agents.toList()

    /** All registered capabilities (lightweight metadata). */
    fun capabilities(): List<SubAgentCapability> = agents.map { it.capability }
}
