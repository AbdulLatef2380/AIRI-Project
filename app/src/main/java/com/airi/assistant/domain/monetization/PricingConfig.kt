package com.airi.assistant.domain.monetization

/** Central Free/Pro commercial definition. Billing and ads are intentionally frozen. */
object PricingConfig {
    const val FREE_DAILY_MESSAGES = 30
    const val FREE_DAILY_AGENT_EXECUTIONS = 0
    const val FREE_DAILY_SKILL_USES = 5
    const val FREE_SOFT_LIMIT_START = 22
    const val FREE_NEAR_LIMIT = 27
    const val SOFT_LIMIT_TOKEN_FACTOR = 0.65f
    const val SOFT_LIMIT_DELAY_MS = 200L
    const val NEAR_LIMIT_TOKEN_FACTOR = 0.45f
    const val SUCCESS_TRIGGER_COUNT = 3
    const val SPEED_UPSELL_THRESHOLD_MS = 5_000L
    const val POWER_MIN = 0.45f

    const val PRO_DAILY_MESSAGES = Int.MAX_VALUE
    const val PRO_DAILY_AGENT_EXECUTIONS = Int.MAX_VALUE
    const val PRO_DAILY_SKILL_USES = Int.MAX_VALUE
    const val PRO_MONTHLY_PRICE_USD = 4.90
    const val PRO_ANNUAL_PRICE_USD = 19.90

    // Future integrations: remain false until the user configures providers.
    const val BILLING_ENABLED = false
    const val ADS_ENABLED = false
    const val SHOW_LAUNCH_AD_ONCE = true

    val PRO_FEATURES: Set<String> = setOf(
        "background_agent", "autonomous_agent", "unlimited_chat",
        "priority_model_access", "custom_skills", "connectors",
        "developer_api", "scheduled_tasks", "advanced_voice"
    )

    // Compatibility aliases for existing metering and legacy screens.
    const val PREMIUM_DAILY_MESSAGES = PRO_DAILY_MESSAGES
    const val PREMIUM_DAILY_AGENT_EXECUTIONS = PRO_DAILY_AGENT_EXECUTIONS
    const val PREMIUM_DAILY_SKILL_USES = PRO_DAILY_SKILL_USES
    const val PREMIUM_MONTHLY_PRICE_USD = PRO_MONTHLY_PRICE_USD
    const val PREMIUM_ANNUAL_PRICE_USD = PRO_ANNUAL_PRICE_USD
    val PREMIUM_FEATURES: Set<String> = PRO_FEATURES
}

object AdPolicy {
    fun shouldShowLaunchAd(hasShownThisLaunch: Boolean): Boolean =
        PricingConfig.ADS_ENABLED && PricingConfig.SHOW_LAUNCH_AD_ONCE && !hasShownThisLaunch
}

enum class PlanActionState { FROZEN_UNTIL_BILLING_CONFIGURED, AVAILABLE }

fun currentPlanActionState(): PlanActionState =
    if (PricingConfig.BILLING_ENABLED) PlanActionState.AVAILABLE
    else PlanActionState.FROZEN_UNTIL_BILLING_CONFIGURED
