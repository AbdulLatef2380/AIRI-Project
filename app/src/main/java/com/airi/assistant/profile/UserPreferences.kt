package com.airi.assistant.profile

/**
 * UserPreferences — complete user configuration model.
 *
 * All fields have sensible defaults so the repository can hydrate a fresh
 * profile with a single `UserPreferences()` call.
 *
 * ── DESIGN NOTES ─────────────────────────────────────────────────────────
 *
 *   • This is a pure data class — no Android dependencies — so it can be
 *     serialised to/from JSON, Room columns, or Firestore documents without
 *     any adapter boilerplate.
 *   • Sensitive fields (API keys, tokens) are intentionally absent here;
 *     they live in SecureStorage / EncryptedSharedPreferences.
 *   • The profile is owned by UserProfileRepository. All writes go through
 *     that class so observers are always notified.
 */
data class UserPreferences(

    // ── Identity ──────────────────────────────────────────────────────────
    val displayName:          String  = "",
    val avatarUrl:            String  = "",
    val preferredLanguage:    String  = "en",

    // ── Personality / AI persona ──────────────────────────────────────────
    val airiPersonaName:      String  = "AIRI",
    val airiPersonaTone:      Tone    = Tone.BALANCED,
    val airiResponseLength:   Length  = Length.ADAPTIVE,
    val airiCreativityLevel:  Float   = 0.7f,  // 0.0 = precise, 1.0 = creative

    // ── Model / execution preferences ─────────────────────────────────────
    val preferredExecutionMode: ExecutionMode = ExecutionMode.AUTO,
    val preferredRemoteProvider: String       = "openai",
    val preferLocalModel:        Boolean      = false,
    val maxContextTokens:        Int          = 4096,

    // ── Memory ───────────────────────────────────────────────────────────
    val enableEpisodicMemory:  Boolean = true,
    val enableSemanticMemory:  Boolean = true,
    val enableLongTermMemory:  Boolean = true,
    val memoryRetentionDays:   Int     = 90,

    // ── Privacy ───────────────────────────────────────────────────────────
    val analyticsOptIn:        Boolean = false,
    val crashReportingOptIn:   Boolean = false,
    val sendAgentTelemetry:    Boolean = false,
    val cloudSyncEnabled:      Boolean = false,

    // ── Accessibility ─────────────────────────────────────────────────────
    val accessibilityServiceEnabled: Boolean = false,
    val proactiveAssistEnabled:      Boolean = false,

    // ── Voice ─────────────────────────────────────────────────────────────
    val voiceEnabled:          Boolean = false,
    val hotwordEnabled:        Boolean = false,
    val preferredTtsVoice:     String  = "default",
    val speechRate:            Float   = 1.0f,

    // ── Notifications ─────────────────────────────────────────────────────
    val taskCompletionNotify:  Boolean = true,
    val dailyDigestEnabled:    Boolean = false,
    val dailyDigestHour:       Int     = 8,

    // ── Theme ─────────────────────────────────────────────────────────────
    val darkMode:              DarkMode = DarkMode.SYSTEM,
    val accentColorHex:        String   = "#6C63FF",

    // ── Metadata ─────────────────────────────────────────────────────────
    val createdAtMs:           Long    = System.currentTimeMillis(),
    val lastUpdatedAtMs:       Long    = System.currentTimeMillis()
) {
    enum class Tone    { FORMAL, BALANCED, CASUAL, PLAYFUL }
    enum class Length  { CONCISE, ADAPTIVE, DETAILED }
    enum class ExecutionMode { LOCAL, CLOUD, AUTO }
    enum class DarkMode      { LIGHT, DARK, SYSTEM }
}
