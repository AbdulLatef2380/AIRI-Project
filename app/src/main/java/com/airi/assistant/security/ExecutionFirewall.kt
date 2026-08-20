package com.airi.assistant.security

import com.airi.assistant.domain.logging.LoggingService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ExecutionFirewall — intercepts tool-call and capability requests from
 * sub-agents and enforces [ScopedPermissionRegistry] grants before
 * allowing execution to proceed.
 *
 * ── INTEGRATION POINT ─────────────────────────────────────────────────────
 *
 *   The firewall is called by ProductionAgentOrchestrator before dispatching
 *   any tool call from a sub-agent. The call chain is:
 *
 *     SubAgent.execute() → ProductionAgentOrchestrator.executeSingle()
 *       → ExecutionFirewall.guard(agentId, toolName)
 *         → rate-limit check (per-agent token bucket)
 *           → ScopedPermissionRegistry.require(agentId, mappedPermission)
 *             → tool dispatch if allowed, exception otherwise
 *
 * ── TOOL → PERMISSION MAPPING ────────────────────────────────────────────
 *
 *   Tool names (as registered in ToolRegistry) are mapped to the narrowest
 *   AgentPermission that covers them. Unknown tools are DENIED by default
 *   (allowlist model, not blocklist).
 *
 * ── RATE LIMITING ─────────────────────────────────────────────────────────
 *
 *   A per-agent token bucket limits tool-call throughput to at most
 *   [BUCKET_CAPACITY] calls per [REFILL_WINDOW_MS] window. The bucket refills
 *   fully once per window (sliding-window variant). Burst calls beyond the
 *   capacity are rejected with [RateLimitException] before permission checks.
 *
 *   This prevents a runaway agent from saturating shared API quotas or
 *   triggering denial-of-service via rapid-fire tool invocations.
 */
class ExecutionFirewall(
    private val registry: ScopedPermissionRegistry
) {

    private val TAG = "ExecutionFirewall"

    private val toolPermissionMap: Map<String, ScopedPermissionRegistry.AgentPermission> = mapOf(
        // Calendar
        "calendar_read"       to ScopedPermissionRegistry.AgentPermission.READ_CALENDAR,
        "calendar_create"     to ScopedPermissionRegistry.AgentPermission.WRITE_CALENDAR,
        "calendar_update"     to ScopedPermissionRegistry.AgentPermission.WRITE_CALENDAR,
        "calendar_delete"     to ScopedPermissionRegistry.AgentPermission.WRITE_CALENDAR,

        // Alarms
        "alarm_set"           to ScopedPermissionRegistry.AgentPermission.SET_ALARM,
        "alarm_cancel"        to ScopedPermissionRegistry.AgentPermission.SET_ALARM,

        // Notifications
        "notification_post"   to ScopedPermissionRegistry.AgentPermission.POST_NOTIFICATIONS,

        // Search / web
        "search_web"          to ScopedPermissionRegistry.AgentPermission.SEARCH_WEB,
        "web_search"          to ScopedPermissionRegistry.AgentPermission.SEARCH_WEB,
        "browser_open"        to ScopedPermissionRegistry.AgentPermission.OPEN_BROWSER,

        // Notes
        "notes_create"        to ScopedPermissionRegistry.AgentPermission.WRITE_NOTES,
        "notes_read"          to ScopedPermissionRegistry.AgentPermission.READ_FILES,

        // Files
        "file_read"           to ScopedPermissionRegistry.AgentPermission.READ_FILES,
        "file_write"          to ScopedPermissionRegistry.AgentPermission.WRITE_FILES,

        // Memory
        "memory_read"         to ScopedPermissionRegistry.AgentPermission.READ_MEMORY,
        "memory_write"        to ScopedPermissionRegistry.AgentPermission.WRITE_MEMORY,

        // Remote LLM
        "llm_call"            to ScopedPermissionRegistry.AgentPermission.CALL_REMOTE_LLM,
        "remote_llm"          to ScopedPermissionRegistry.AgentPermission.CALL_REMOTE_LLM,

        // Integrations
        "github_api"          to ScopedPermissionRegistry.AgentPermission.CALL_GITHUB_API,
        "telegram_send"       to ScopedPermissionRegistry.AgentPermission.CALL_TELEGRAM_API,

        // Accessibility
        "accessibility_tap"   to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "accessibility_type"  to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "accessibility_scroll" to ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
        "intent_launch"       to ScopedPermissionRegistry.AgentPermission.TRIGGER_INTENT,

        // Orchestration
        "spawn_subagent"      to ScopedPermissionRegistry.AgentPermission.SPAWN_SUBAGENT,
        "cloud_sync"          to ScopedPermissionRegistry.AgentPermission.CLOUD_SYNC
    )

    // ── Per-agent token buckets ────────────────────────────────────────────────

    private val buckets = ConcurrentHashMap<String, AgentRateBucket>()

    private inner class AgentRateBucket {
        private val tokens      = AtomicInteger(BUCKET_CAPACITY)
        private val windowStart = AtomicLong(System.currentTimeMillis())

        /**
         * Attempt to consume one token.
         * Returns true if a token was available (call permitted),
         * false if the bucket is empty (call should be rejected).
         */
        fun tryConsume(): Boolean {
            val now = System.currentTimeMillis()
            val win = windowStart.get()
            if (now - win >= REFILL_WINDOW_MS) {
                // New window — refill bucket and reset clock.
                // Use CAS so only the first thread to see the expired window refills.
                if (windowStart.compareAndSet(win, now)) {
                    tokens.set(BUCKET_CAPACITY)
                }
            }
            return tokens.decrementAndGet() >= 0
        }
    }

    private fun bucketFor(agentId: String): AgentRateBucket =
        buckets.getOrPut(agentId) { AgentRateBucket() }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Guard a tool call from [agentId].
     *
     * @throws RateLimitException if the agent has exceeded its call budget.
     * @throws ScopedPermissionRegistry.PermissionDeniedException if not allowed.
     * @throws UnknownToolException if [toolName] is not in the allowlist.
     */
    fun guard(agentId: String, toolName: String) {
        // Rate limit check — before permission check for cheapness.
        if (!bucketFor(agentId).tryConsume()) {
            LoggingService.warn(TAG,
                "AIRI FIREWALL_RATE_LIMITED agent=$agentId tool=$toolName")
            throw RateLimitException(agentId, toolName)
        }

        val permission = toolPermissionMap[toolName.lowercase()]
            ?: run {
                LoggingService.warn(TAG, "AIRI FIREWALL_UNKNOWN_TOOL agent=$agentId tool=$toolName")
                throw UnknownToolException(agentId, toolName)
            }

        registry.require(agentId, permission)
        LoggingService.debug(TAG, "AIRI FIREWALL_ALLOWED agent=$agentId tool=$toolName permission=$permission")
    }

    /**
     * Non-throwing guard — returns false if denied or rate-limited instead of throwing.
     */
    fun allows(agentId: String, toolName: String): Boolean {
        if (!bucketFor(agentId).tryConsume()) return false
        val permission = toolPermissionMap[toolName.lowercase()] ?: return false
        return registry.check(agentId, permission)
    }

    /**
     * Expose live stats for the DeveloperCenter diagnostics tab.
     * Returns a snapshot: agentId → remaining tokens in current window.
     */
    fun rateLimitSnapshot(): Map<String, Int> =
        buckets.mapValues { (_, bucket) ->
            // Non-mutating read — just peek at the bucket's atomic counter.
            // A race here is benign; this is telemetry only.
            @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
            bucket.javaClass.getDeclaredField("tokens")
                .also { it.isAccessible = true }
                .let { (it.get(bucket) as AtomicInteger).get() }
                .coerceAtLeast(0)
        }

    class UnknownToolException(agentId: String, toolName: String) :
        SecurityException("Agent '$agentId' tried to call unknown tool: '$toolName'")

    class RateLimitException(agentId: String, toolName: String) :
        SecurityException("Agent '$agentId' rate-limited on tool '$toolName' — bucket exhausted")

    companion object {
        /** Maximum tool calls an agent may make in a single [REFILL_WINDOW_MS] window. */
        const val BUCKET_CAPACITY   = 60
        /** Window duration in milliseconds (1 minute). */
        const val REFILL_WINDOW_MS  = 60_000L
    }
}
