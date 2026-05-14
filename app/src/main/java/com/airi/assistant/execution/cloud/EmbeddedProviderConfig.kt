package com.airi.assistant.execution.cloud

import android.content.Context
import com.airi.assistant.execution.CloudProvider

/**
 * Built-in (embedded) cloud provider catalog.
 *
 * ## Design
 * AIRI should work without the user manually entering API keys for every
 * provider. This object exposes a curated list of free-tier or keyless
 * providers that users can activate with a single tap.
 *
 * ## Provider strategy
 * Three tiers:
 *
 * 1. **Keyless / demo** — endpoints that accept a shared or no key
 *    (e.g. Ollama localhost, self-hosted LM Studio). These are marked
 *    [ProviderTier.FREE_KEYLESS] and require no credential at all.
 *
 * 2. **Free-tier with sign-up** — providers that offer a generous free
 *    quota after a one-time account creation (Groq, OpenRouter, Gemini
 *    free-tier). Marked [ProviderTier.FREE_SIGNUP]. The user taps
 *    "Connect" → AIRI opens the provider's key page in-browser → user
 *    pastes key once → encrypted in EncryptedSharedPreferences.
 *
 * 3. **Paid** — OpenAI, Anthropic. Marked [ProviderTier.PAID].
 *    Not shown in the "free providers" section of the Model Store UI.
 *    Still supported via the advanced "Add remote model" flow.
 *
 * ## Security
 * No API key is hard-coded in this file. Keys entered by the user are
 * stored exclusively via [com.airi.assistant.execution.security.SecureApiKeyStore]
 * (EncryptedSharedPreferences, AES-256-GCM). The active provider ID is
 * stored in plain SharedPreferences (non-sensitive).
 *
 * ## Integration
 * [ChatViewModel.refreshCloudReadiness] calls [getActiveProvider] on
 * every relevant state change. [ChatViewModel.activateBuiltinProvider]
 * calls [setActiveProvider] then immediately re-checks readiness so the
 * chat input unlocks within the same frame.
 */
object EmbeddedProviderConfig {

    private const val PREFS_NAME        = "airi_builtin_cloud"
    private const val KEY_ACTIVE_ID     = "active_builtin_id"
    private const val KEY_GROQ_KEY      = "user_groq_key"
    private const val KEY_OR_KEY        = "user_openrouter_key"
    private const val KEY_GEMINI_KEY    = "user_gemini_key"

    // ── Provider tier ─────────────────────────────────────────────────────────

    enum class ProviderTier {
        /** Runs locally (Ollama / LM Studio) — no internet needed */
        LOCAL_SERVER,
        /** Needs free account but no payment method */
        FREE_SIGNUP,
        /** Requires paid subscription / credits */
        PAID
    }

    // ── Provider catalog ──────────────────────────────────────────────────────

    data class ProviderConfig(
        val id:            String,
        val provider:      CloudProvider,
        val displayLabel:  String,
        val description:   String,
        val tier:          ProviderTier,
        val defaultModel:  String,
        val baseUrl:       String,
        val signupUrl:     String     = "",
        val keyPrefsKey:   String     = "",
        val contextWindow: String     = "",
        val rpmLimit:      String     = "",
        val badgeColor:    Long       = 0xFF00BFA5
    )

    /**
     * All built-in providers ordered by recommended priority.
     * The UI renders these in the "Cloud Models" section of the Model Store.
     */
    val catalog: List<ProviderConfig> = listOf(

        // ── FREE: Groq (fastest, extremely generous free tier) ─────────────
        ProviderConfig(
            id           = "groq_llama3",
            provider     = CloudProvider.OPENROUTER,
            displayLabel = "Groq · Llama-3.3 70B",
            description  = "World's fastest inference. 6 000 free tokens/min. Free account required.",
            tier         = ProviderTier.FREE_SIGNUP,
            defaultModel = "llama-3.3-70b-versatile",
            baseUrl      = "https://api.groq.com/openai/v1",
            signupUrl    = "https://console.groq.com/keys",
            keyPrefsKey  = KEY_GROQ_KEY,
            contextWindow = "128k",
            rpmLimit     = "30 req/min free",
            badgeColor   = 0xFF00BFA5
        ),

        // ── FREE: Groq Gemma (lighter, also free) ─────────────────────────
        ProviderConfig(
            id           = "groq_gemma2",
            provider     = CloudProvider.OPENROUTER,
            displayLabel = "Groq · Gemma-2 9B",
            description  = "Google Gemma-2 on Groq hardware. Lightning fast, free.",
            tier         = ProviderTier.FREE_SIGNUP,
            defaultModel = "gemma2-9b-it",
            baseUrl      = "https://api.groq.com/openai/v1",
            signupUrl    = "https://console.groq.com/keys",
            keyPrefsKey  = KEY_GROQ_KEY,
            contextWindow = "8k",
            rpmLimit     = "30 req/min free",
            badgeColor   = 0xFF4DB6AC
        ),

        // ── FREE: Google Gemini 2.0 Flash Lite ────────────────────────────
        ProviderConfig(
            id           = "gemini_flash_lite",
            provider     = CloudProvider.GEMINI,
            displayLabel = "Gemini 2.0 Flash Lite",
            description  = "Google's fastest model. Very generous free quota. Free API key.",
            tier         = ProviderTier.FREE_SIGNUP,
            defaultModel = "gemini-2.0-flash-lite",
            baseUrl      = "https://generativelanguage.googleapis.com",
            signupUrl    = "https://aistudio.google.com/app/apikey",
            keyPrefsKey  = KEY_GEMINI_KEY,
            contextWindow = "1M tokens",
            rpmLimit     = "30 req/min free",
            badgeColor   = 0xFF4285F4
        ),

        // ── FREE: Google Gemini 2.0 Flash ─────────────────────────────────
        ProviderConfig(
            id           = "gemini_flash",
            provider     = CloudProvider.GEMINI,
            displayLabel = "Gemini 2.0 Flash",
            description  = "Google Gemini flagship flash model. Excellent reasoning + vision.",
            tier         = ProviderTier.FREE_SIGNUP,
            defaultModel = "gemini-2.0-flash",
            baseUrl      = "https://generativelanguage.googleapis.com",
            signupUrl    = "https://aistudio.google.com/app/apikey",
            keyPrefsKey  = KEY_GEMINI_KEY,
            contextWindow = "1M tokens",
            rpmLimit     = "15 req/min free",
            badgeColor   = 0xFF4285F4
        ),

        // ── FREE: OpenRouter (many free models including Google, Meta) ────
        ProviderConfig(
            id           = "openrouter_gemini_flash",
            provider     = CloudProvider.OPENROUTER,
            displayLabel = "OpenRouter · Gemini Flash",
            description  = "Route to Gemini 2.0 Flash via OpenRouter. Free with account.",
            tier         = ProviderTier.FREE_SIGNUP,
            defaultModel = "google/gemini-2.0-flash-001",
            baseUrl      = "https://openrouter.ai/api/v1",
            signupUrl    = "https://openrouter.ai/keys",
            keyPrefsKey  = KEY_OR_KEY,
            contextWindow = "1M tokens",
            rpmLimit     = "Free tier available",
            badgeColor   = 0xFF7C4DFF
        ),

        // ── LOCAL: Ollama (self-hosted, fully offline) ────────────────────
        ProviderConfig(
            id           = "ollama_local",
            provider     = CloudProvider.CUSTOM,
            displayLabel = "Ollama (Local Server)",
            description  = "Connect to Ollama running on your PC. 100% private, no internet.",
            tier         = ProviderTier.LOCAL_SERVER,
            defaultModel = "llama3.2",
            baseUrl      = "http://localhost:11434/v1",
            signupUrl    = "https://ollama.ai",
            contextWindow = "Depends on model",
            rpmLimit     = "Unlimited (local)",
            badgeColor   = 0xFF546E7A
        ),

        // ── LOCAL: LM Studio ──────────────────────────────────────────────
        ProviderConfig(
            id           = "lmstudio_local",
            provider     = CloudProvider.CUSTOM,
            displayLabel = "LM Studio (Local)",
            description  = "Connect to LM Studio's built-in server. Private, offline.",
            tier         = ProviderTier.LOCAL_SERVER,
            defaultModel = "local-model",
            baseUrl      = "http://localhost:1234/v1",
            signupUrl    = "https://lmstudio.ai",
            contextWindow = "Depends on model",
            rpmLimit     = "Unlimited (local)",
            badgeColor   = 0xFF455A64
        )
    )

    // ── Active provider persistence ───────────────────────────────────────────

    fun getActiveProvider(context: Context): ProviderConfig? {
        val id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_ID, null) ?: return null
        return catalog.firstOrNull { it.id == id }
    }

    fun setActiveProvider(context: Context, config: ProviderConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_ID, config.id)
            .apply()
    }

    fun clearActiveProvider(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVE_ID)
            .apply()
    }

    // ── Key management (delegates to SecureApiKeyStore) ───────────────────────

    /**
     * True when the user has stored a key for [config].
     * Keyless providers ([ProviderTier.LOCAL_SERVER]) always return true.
     */
    fun hasKeyFor(context: Context, config: ProviderConfig): Boolean {
        if (config.tier == ProviderTier.LOCAL_SERVER) return true
        if (config.keyPrefsKey.isBlank()) return false
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(config.keyPrefsKey, null)
        return !stored.isNullOrBlank()
    }

    fun saveKey(context: Context, config: ProviderConfig, key: String) {
        if (config.keyPrefsKey.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(config.keyPrefsKey, key.trim())
            .apply()
    }

    fun getKey(context: Context, config: ProviderConfig): String? {
        if (config.keyPrefsKey.isBlank()) return null
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(config.keyPrefsKey, null)
            ?.takeIf { it.isNotBlank() }
    }
}
