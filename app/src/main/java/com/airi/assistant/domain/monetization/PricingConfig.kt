package com.airi.assistant.domain.monetization

object PricingConfig {

    // ── Free Tier Daily Limits ────────────────────────────────────────────────

    const val FREE_DAILY_MESSAGES          = 30
    const val FREE_DAILY_AGENT_EXECUTIONS  = 10
    const val FREE_DAILY_SKILL_USES        = 5

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
        "priority_model_access"
    )
}
