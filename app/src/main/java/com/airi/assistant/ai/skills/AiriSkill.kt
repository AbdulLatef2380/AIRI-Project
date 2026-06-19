package com.airi.assistant.ai.skills

/**
 * AiriSkill — the core interface every skill in the AIRI ecosystem must implement.
 *
 * Properties with default implementations maintain backward compatibility with
 * existing skill classes (GithubGuardianSkill, TelegramMessengerSkill, etc.)
 * that were written against the old minimal interface.
 *
 * New official skills should override all metadata properties for full manifest
 * fidelity. Custom / legacy skills only need [name], [description], [score],
 * and [execute].
 */
interface AiriSkill {

    // ── Identity ──────────────────────────────────────────────────────────────

    /** Unique machine-readable identifier (e.g. "web_search"). Defaults to [name]. */
    val skillId: String get() = name

    /** Display name shown in the UI. */
    val name: String

    /** Human-readable description. Used by the model for skill selection. */
    val description: String

    /** Semantic version string (SemVer). */
    val version: String get() = "1.0.0"

    /** Author / publisher display name. */
    val author: String get() = "AIRI"

    /** Skill category for UI grouping and discoverability. */
    val category: String get() = "UTILITY"

    /** Emoji icon for the UI card. */
    val iconEmoji: String get() = "🔧"

    // ── Status ────────────────────────────────────────────────────────────────

    /** True for first-party AIRI-published skills. */
    val isOfficial: Boolean get() = false

    /** True when the skill is enabled and can receive routing decisions. */
    val isEnabled: Boolean get() = true

    // ── Security & permissions ────────────────────────────────────────────────

    /** Android/system permissions this skill requires. */
    val requiredPermissions: List<String> get() = emptyList()

    /** Memory read/write access level. Default: no memory access. */
    val memoryAccess: SkillMemoryAccess get() = SkillMemoryAccess.NONE

    /** LLM access level. Default: skill does not call the model. */
    val modelAccess: SkillModelAccess get() = SkillModelAccess.NONE

    // ── Wiring ────────────────────────────────────────────────────────────────

    /** Parameter schema advertised to the model prompt. */
    val parameters: Map<String, String> get() = emptyMap()

    /** Other skill IDs this skill depends on. */
    val dependencies: List<String> get() = emptyList()

    /** Tool definitions this skill exposes to the agent loop. */
    val toolDefinitions: List<SkillToolDefinition> get() = emptyList()

    // ── Runtime ───────────────────────────────────────────────────────────────

    /**
     * Score how well this skill can handle [input] given [context].
     * Returns 0 (skip) to 100 (perfect match). Routing selects the highest scorer.
     */
    fun score(input: String, context: SkillContext): Int

    /**
     * Execute the skill with [params].
     *
     * Required params always include "input" (the raw user text).
     * Optional params: "context" (SkillContext), any skill-specific keys.
     */
    suspend fun execute(params: Map<String, Any>): SkillResult
}

/**
 * A tool definition that a skill exposes to the AgentLoop so the LLM can call it
 * directly by name (e.g. "web_search", "translate_text").
 */
data class SkillToolDefinition(
    val name:        String,
    val description: String,
    val parameters:  Map<String, SkillParamDef> = emptyMap(),
    val dangerous:   Boolean = false
)

data class SkillParamDef(
    val type:        String,
    val description: String = "",
    val required:    Boolean = true
)
