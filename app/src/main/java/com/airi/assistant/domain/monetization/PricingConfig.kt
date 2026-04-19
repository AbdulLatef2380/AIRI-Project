package com.airi.assistant.domain.monetization

object PricingConfig {

    // ── Free Tier Daily Limits ────────────────────────────────────────────────

    const val FREE_DAILY_MESSAGES          = 30
    const val FREE_DAILY_AGENT_EXECUTIONS  = 10
    const val FREE_DAILY_SKILL_USES        = 5

    // ── Soft Limit Thresholds (degrade before hard block) ─────────────────────

    const val FREE_SOFT_LIMIT_START    = 22          // phase 1: hint zone (22-26)
    const val FREE_NEAR_LIMIT          = 27          // phase 2: warning zone (27-29)
    const val SOFT_LIMIT_TOKEN_FACTOR  = 0.65f       // reduce max tokens by 35% in soft zone
    const val SOFT_LIMIT_DELAY_MS      = 200L        // add 200 ms delay in soft zone
    const val NEAR_LIMIT_TOKEN_FACTOR  = 0.45f       // reduce max tokens by 55% near limit

    // ── Trigger Thresholds ────────────────────────────────────────────────────

    const val SUCCESS_TRIGGER_COUNT      = 3         // trigger paywall after N consecutive successes
    const val SPEED_UPSELL_THRESHOLD_MS  = 5_000L   // slow response triggers speed upsell
    const val POWER_MIN                  = 0.45f     // minimum power level for free users

    // ── Premium Limits (effectively unlimited) ────────────────────────────────

    const val PREMIUM_DAILY_MESSAGES          = Int.MAX_VALUE
    const val PREMIUM_DAILY_AGENT_EXECUTIONS  = Int.MAX_VALUE
    const val PREMIUM_DAILY_SKILL_USES        = Int.MAX_VALUE

    // ── Pricing ───────────────────────────────────────────────────────────────

    const val PREMIUM_MONTHLY_PRICE_USD = 4.99
    const val PREMIUM_ANNUAL_PRICE_USD  = 39.99

    // ── Features gated behind Premium ─────────────────────────────────────────

    val PREMIUM_FEATURES: Set<String> = setOf(
        "background_agent",
        "unlimited_chat",
        "priority_model_access",
        "custom_skills"
    )
}
