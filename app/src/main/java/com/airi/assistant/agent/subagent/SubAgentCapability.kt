package com.airi.assistant.agent.subagent

/**
 * Structured capability declaration for a named AIRI sub-agent.
 *
 * Exposes metadata consumed by [SubAgentRegistry] for:
 *   - Routing: which agent can handle this input?
 *   - Cost gating: cloud token budget decisions
 *   - Latency calibration: UX indicator timing
 *   - Permission gating: privacy + Android permission pre-checks
 *   - Observability: execution graph annotation
 *
 * All fields are read-only and declared at construction time.
 * Implementations must NOT modify these values at runtime.
 */
data class SubAgentCapability(

    // ── Identity ────────────────────────────────────────────────────────────

    /** Stable unique identifier used in routing tables and task graphs. */
    val agentId: String,

    /** Human-readable name shown in agent trace logs and UI. */
    val displayName: String,

    /** Single-line description of what this agent does. */
    val description: String,

    // ── Routing ─────────────────────────────────────────────────────────────

    /**
     * Intent keyword fragments used for first-pass routing heuristics.
     *
     * If ANY keyword matches (case-insensitive) in the user input,
     * [SubAgent.canHandle] is invoked for fine-grained confirmation.
     * Keep keywords specific — broad terms increase false-positive routing.
     */
    val intentKeywords: List<String>,

    /**
     * Domains / task categories this agent specializes in.
     * Used for vector-similarity routing when keyword matching is ambiguous.
     */
    val domains: List<String> = emptyList(),

    // ── Permissions ─────────────────────────────────────────────────────────

    /**
     * Android runtime permissions required before [SubAgent.execute].
     * [SubAgentRegistry] pre-checks these; execution is blocked if any
     * required permission is not granted.
     */
    val requiredPermissions: List<String> = emptyList(),

    /**
     * [ToolDefinition] names (from [ToolRegistry]) that this agent uses.
     * Registry validates availability before dispatch.
     */
    val requiredTools: List<String> = emptyList(),

    // ── Privacy ─────────────────────────────────────────────────────────────

    /**
     * Whether this agent makes outbound cloud API calls.
     * Blocked when the user's privacy level is MAXIMUM.
     */
    val requiresCloud: Boolean = false,

    /**
     * Whether this agent reads private user data
     * (calendar, email, contacts, files, SMS).
     * Requires explicit user consent before first use.
     * Never silently uploads private data to cloud.
     */
    val accessesPrivateData: Boolean = false,

    // ── Resource profile ─────────────────────────────────────────────────────

    /**
     * Cloud token cost tier. Used to gate execution against the daily
     * cloud token budget in [ExecModePreferences].
     */
    val costTier: CostTier = CostTier.LOW,

    /**
     * Expected latency class for UX calibration.
     * Determines which progress indicator the UI shows.
     */
    val latencyProfile: LatencyProfile = LatencyProfile.FAST,

    // ── Concurrency ──────────────────────────────────────────────────────────

    /**
     * Whether this agent can execute as a background task while the user
     * continues using other parts of the app.
     */
    val supportsBackground: Boolean = false,

    /**
     * Maximum number of parallel sub-tasks this agent may spawn internally.
     * 1 = strictly sequential.
     */
    val maxParallelSubTasks: Int = 1,

    /**
     * Whether this agent supports resumable execution (checkpoint + restart).
     * Required for tasks that may outlive a single app session.
     */
    val supportsResume: Boolean = false

) {

    // ── Enums ────────────────────────────────────────────────────────────────

    enum class CostTier(val estimatedTokensPerCall: IntRange) {
        /** No cloud tokens consumed — fully local. */
        FREE(0..0),
        /** Light cloud usage: classification, short generation. */
        LOW(1..2_000),
        /** Moderate: summarization, research queries, code explanation. */
        MEDIUM(2_000..15_000),
        /** Heavy: long-form generation, deep research, coding tasks. */
        HIGH(15_000..100_000)
    }

    enum class LatencyProfile(val typicalRangeMs: IntRange) {
        /** < 100 ms — local lookup, memory query. */
        INSTANT(0..100),
        /** 100–1500 ms — short cloud call or local inference. */
        FAST(100..1_500),
        /** 1.5–8 s — full LLM turn, tool chain, cloud search. */
        MODERATE(1_500..8_000),
        /** 8 s+ — deep research, long generation, multi-step automation. */
        SLOW(8_000..120_000)
    }
}
