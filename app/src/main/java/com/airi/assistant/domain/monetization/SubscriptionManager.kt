package com.airi.assistant.domain.monetization

import android.content.Context
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import com.airi.assistant.domain.logging.LoggingService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SubscriptionManager(context: Context) {

    private val prefs = context.getSharedPreferences("airi_subscription", Context.MODE_PRIVATE)

    companion object {
        private const val TAG                      = "SubscriptionManager"
        private const val KEY_TIER                 = "subscription_tier"
        private const val KEY_MESSAGES             = "daily_messages"
        private const val KEY_AGENTS               = "daily_agents"
        private const val KEY_SKILLS               = "daily_skills"
        private const val KEY_DATE                 = "usage_date"
        private const val KEY_CONSECUTIVE_SUCCESSES = "consecutive_successes"
        private const val DATE_FORMAT              = "yyyy-MM-dd"
    }

    // ── Tier management ───────────────────────────────────────────────────────

    fun getCurrentTier(): SubscriptionTier {
        val saved = prefs.getString(KEY_TIER, SubscriptionTier.FREE.name) ?: SubscriptionTier.FREE.name
        return runCatching { SubscriptionTier.valueOf(saved) }.getOrDefault(SubscriptionTier.FREE)
    }

    fun setTier(tier: SubscriptionTier) {
        prefs.edit().putString(KEY_TIER, tier.name).apply()
        LoggingService.info(TAG, "Subscription set to: ${tier.displayName}")
    }

    fun isPremium(): Boolean = getCurrentTier() == SubscriptionTier.PREMIUM

    // ── Daily usage tracking ──────────────────────────────────────────────────

    private fun today(): String = SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(Date())

    private fun resetIfNewDay() {
        val savedDate = prefs.getString(KEY_DATE, "") ?: ""
        if (savedDate != today()) {
            prefs.edit()
                .putString(KEY_DATE, today())
                .putInt(KEY_MESSAGES, 0)
                .putInt(KEY_AGENTS, 0)
                .putInt(KEY_SKILLS, 0)
                .apply()
        }
    }

    // ── Message quota ─────────────────────────────────────────────────────────

    fun canSendMessage(): Boolean {
        resetIfNewDay()
        if (isPremium()) return true
        val count   = prefs.getInt(KEY_MESSAGES, 0)
        val allowed = count < PricingConfig.FREE_DAILY_MESSAGES
        EventBus.emitSync(AppEvent.SubscriptionChecked(getCurrentTier().name, allowed, "message"))
        if (!allowed) EventBus.emitSync(
            AppEvent.UsageLimitReached("daily_messages", count, PricingConfig.FREE_DAILY_MESSAGES)
        )
        return allowed
    }

    fun recordMessage() {
        resetIfNewDay()
        prefs.edit().putInt(KEY_MESSAGES, prefs.getInt(KEY_MESSAGES, 0) + 1).apply()
    }

    // ── Agent quota ───────────────────────────────────────────────────────────

    fun canExecuteAgent(): Boolean {
        resetIfNewDay()
        if (isPremium()) return true
        val count   = prefs.getInt(KEY_AGENTS, 0)
        val allowed = count < PricingConfig.FREE_DAILY_AGENT_EXECUTIONS
        EventBus.emitSync(AppEvent.SubscriptionChecked(getCurrentTier().name, allowed, "agent"))
        if (!allowed) EventBus.emitSync(
            AppEvent.UsageLimitReached("daily_agents", count, PricingConfig.FREE_DAILY_AGENT_EXECUTIONS)
        )
        return allowed
    }

    fun recordAgentExecution() {
        resetIfNewDay()
        prefs.edit().putInt(KEY_AGENTS, prefs.getInt(KEY_AGENTS, 0) + 1).apply()
    }

    // ── Skill quota ───────────────────────────────────────────────────────────

    fun canUseSkill(): Boolean {
        resetIfNewDay()
        if (isPremium()) return true
        val count   = prefs.getInt(KEY_SKILLS, 0)
        val allowed = count < PricingConfig.FREE_DAILY_SKILL_USES
        EventBus.emitSync(AppEvent.SubscriptionChecked(getCurrentTier().name, allowed, "skill"))
        if (!allowed) EventBus.emitSync(
            AppEvent.UsageLimitReached("daily_skills", count, PricingConfig.FREE_DAILY_SKILL_USES)
        )
        return allowed
    }

    fun recordSkillUse() {
        resetIfNewDay()
        prefs.edit().putInt(KEY_SKILLS, prefs.getInt(KEY_SKILLS, 0) + 1).apply()
    }

    // ── Feature gate ──────────────────────────────────────────────────────────

    fun isPremiumFeature(featureName: String): Boolean =
        featureName in PricingConfig.PREMIUM_FEATURES

    fun canAccessFeature(featureName: String): Boolean {
        val requiresPremium = isPremiumFeature(featureName)
        val allowed         = !requiresPremium || isPremium()
        if (!allowed) EventBus.emitSync(AppEvent.PremiumRequired(featureName))
        return allowed
    }

    // ── Soft limit phase (0=normal, 1=hint, 2=warning, 3=hard block) ─────────

    fun getSoftLimitPhase(): Int {
        if (isPremium()) return 0
        resetIfNewDay()
        val used = prefs.getInt(KEY_MESSAGES, 0)
        return when {
            used >= PricingConfig.FREE_DAILY_MESSAGES  -> 3
            used >= PricingConfig.FREE_NEAR_LIMIT      -> 2
            used >= PricingConfig.FREE_SOFT_LIMIT_START -> 1
            else                                       -> 0
        }
    }

    // ── AI Power Level (1.0 = full; decreases with free usage) ───────────────

    fun getPowerLevel(): Float {
        if (isPremium()) return 1.0f
        resetIfNewDay()
        val used  = prefs.getInt(KEY_MESSAGES, 0)
        val limit = PricingConfig.FREE_DAILY_MESSAGES
        val ratio = used.toFloat() / limit.toFloat()
        return (1.0f - ratio * 0.55f).coerceAtLeast(PricingConfig.POWER_MIN)
    }

    // ── Consecutive success tracking (for success_moment trigger) ─────────────

    fun recordConsecutiveSuccess() {
        val n = prefs.getInt(KEY_CONSECUTIVE_SUCCESSES, 0) + 1
        prefs.edit().putInt(KEY_CONSECUTIVE_SUCCESSES, n).apply()
    }

    fun resetConsecutiveSuccesses() {
        prefs.edit().putInt(KEY_CONSECUTIVE_SUCCESSES, 0).apply()
    }

    fun getConsecutiveSuccesses(): Int = prefs.getInt(KEY_CONSECUTIVE_SUCCESSES, 0)

    // ── Remaining fast responses display ─────────────────────────────────────

    fun getRemainingFastResponses(): Int {
        if (isPremium()) return Int.MAX_VALUE
        resetIfNewDay()
        val used = prefs.getInt(KEY_MESSAGES, 0)
        return (PricingConfig.FREE_DAILY_MESSAGES - used).coerceAtLeast(0)
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    fun getUsageSummary(): UsageSummary {
        resetIfNewDay()
        val premium = isPremium()
        return UsageSummary(
            tier          = getCurrentTier(),
            messagesUsed  = prefs.getInt(KEY_MESSAGES, 0),
            messagesLimit = if (premium) PricingConfig.PREMIUM_DAILY_MESSAGES else PricingConfig.FREE_DAILY_MESSAGES,
            agentsUsed    = prefs.getInt(KEY_AGENTS,   0),
            agentsLimit   = if (premium) PricingConfig.PREMIUM_DAILY_AGENT_EXECUTIONS else PricingConfig.FREE_DAILY_AGENT_EXECUTIONS,
            skillsUsed    = prefs.getInt(KEY_SKILLS,   0),
            skillsLimit   = if (premium) PricingConfig.PREMIUM_DAILY_SKILL_USES else PricingConfig.FREE_DAILY_SKILL_USES
        )
    }

    data class UsageSummary(
        val tier: SubscriptionTier,
        val messagesUsed: Int,
        val messagesLimit: Int,
        val agentsUsed: Int,
        val agentsLimit: Int,
        val skillsUsed: Int,
        val skillsLimit: Int
    )
}
