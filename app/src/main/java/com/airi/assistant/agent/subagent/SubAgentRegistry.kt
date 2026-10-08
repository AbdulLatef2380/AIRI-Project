package com.airi.assistant.agent.subagent

import com.airi.assistant.agent.learning.reinforcement.AdaptivePolicy
import kotlinx.coroutines.CancellationException

/**
 * Central registry of AIRI sub-agents.
 *
 * Reads always use one immutable snapshot. Registration, capability changes and
 * freeze publish a complete replacement under one lock, so routing cannot see
 * a partially updated registry. Automatic and explicit routing share the same
 * [SubAgentAuthorization] gate.
 */
object SubAgentRegistry {
    private val writeLock = Any()

    @Volatile
    private var snapshot = RegistrySnapshot()

    private data class RegistrySnapshot(
        val agentsById: Map<String, SubAgent> = emptyMap(),
        val runtimeCapabilities: Set<String> = emptySet(),
        val frozen: Boolean = false,
    )

    fun initialize(agentList: List<SubAgent>) {
        synchronized(writeLock) {
            val current = snapshot
            if (current.frozen) {
                return
            }
            val replacement = linkedMapOf<String, SubAgent>()
            agentList.forEach { replacement[it.capability.agentId] = it }
            snapshot = current.copy(agentsById = replacement.toMap())
        }
    }

    fun register(agent: SubAgent) {
        synchronized(writeLock) {
            val current = snapshot
            check(!current.frozen) { "SubAgentRegistry is frozen — cannot register new agents" }
            val replacement = current.agentsById.toMutableMap()
            replacement[agent.capability.agentId] = agent
            snapshot = current.copy(agentsById = replacement.toMap())
        }
    }

    fun freeze() {
        synchronized(writeLock) {
            if (!snapshot.frozen) {
                snapshot = snapshot.copy(frozen = true)
            }
        }
    }

    fun grantCapability(capability: String) {
        synchronized(writeLock) {
            snapshot = snapshot.copy(runtimeCapabilities = snapshot.runtimeCapabilities + capability)
        }
    }

    fun revokeCapability(capability: String) {
        synchronized(writeLock) {
            snapshot = snapshot.copy(runtimeCapabilities = snapshot.runtimeCapabilities - capability)
        }
    }

    fun hasCapability(capability: String): Boolean = capability in snapshot.runtimeCapabilities

    fun activeCapabilities(): List<String> = snapshot.runtimeCapabilities.toList()

    fun getAll(): List<SubAgent> = snapshot.agentsById.values.toList()
    fun all(): List<SubAgent> = getAll()
    fun capabilities(): List<SubAgentCapability> = snapshot.agentsById.values.map { it.capability }

    /**
     * Resolve and authorize an explicit agent id. This is the only supported
     * explicit-dispatch lookup; callers must provide the request context.
     */
    fun authorizedAgent(agentId: String, context: SubAgentContext): SubAgent? {
        val current = snapshot
        val agent = current.agentsById[agentId] ?: return null
        return agent.takeIf {
            SubAgentAuthorization.evaluate(it.capability, context, current.runtimeCapabilities) is
                SubAgentAuthorization.Decision.Allow
        }
    }

    suspend fun route(input: String, context: SubAgentContext): SubAgent? {
        val current = snapshot
        val effectiveContext = context.copy(
            grantedPermissions = (context.grantedPermissions + current.runtimeCapabilities).distinct()
        )
        val normalized = input.lowercase().trim()
        val scored = current.agentsById.values
            .filter { agent ->
                SubAgentAuthorization.evaluate(agent.capability, effectiveContext) is
                    SubAgentAuthorization.Decision.Allow &&
                    agent.capability.intentKeywords.any { kw -> normalized.contains(kw.lowercase()) }
            }
            .map { agent ->
                val keywordScore = agent.capability.intentKeywords.count { normalized.contains(it.lowercase()) }
                val domainScore = agent.capability.domains.count { normalized.contains(it.lowercase()) }
                val rawScore = keywordScore * 2 + domainScore
                agent to AdaptivePolicy.adjustScore(rawScore, "routing", agent.capability.agentId)
            }
            .sortedByDescending { (_, score) -> score }

        for ((agent, _) in scored) {
            val handles = try {
                agent.canHandle(input, effectiveContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (handles) {
                return agent
            }
        }
        return null
    }

    /** Test-only reset; production callers must initialize once and freeze. */
    internal fun resetForTests() {
        synchronized(writeLock) { snapshot = RegistrySnapshot() }
    }
}
