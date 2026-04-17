package com.airi.assistant.domain.monetization

import android.content.Context
import android.content.SharedPreferences
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.domain.logging.LoggingService
import java.util.concurrent.atomic.AtomicLong

object PaywallTriggerEngine {

    private const val TAG             = "PaywallTriggerEngine"
    private const val COOLDOWN_MS     = 5 * 60 * 1000L   // 5 minutes
    private const val MSG_TRIGGER_COUNT = 10              // trigger after 10 messages
    private const val PREFS_NAME      = "airi_paywall_engine"
    private const val KEY_TOTAL_MSGS  = "total_messages"
    private const val KEY_AGENT_DONE  = "agent_trigger_fired"
    private const val KEY_LAST_SHOWN  = "last_paywall_ms"

    // ── Trigger reasons ───────────────────────────────────────────────────────

    sealed class TriggerReason(val source: String) {
        object LimitReached          : TriggerReason("limit_reached")
        object MessageThreshold      : TriggerReason("message_threshold")
        object FirstAgentExecution   : TriggerReason("first_agent")
        object PremiumFeatureAttempt : TriggerReason("premium_feature")
        object Manual                : TriggerReason("manual")
    }

    @Volatile var lastTriggerReason: TriggerReason = TriggerReason.Manual
        private set

    private val lastShownMs = AtomicLong(0L)
    private var prefs: SharedPreferences? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        lastShownMs.set(prefs!!.getLong(KEY_LAST_SHOWN, 0L))
        LoggingService.debug(TAG, "Initialized — total messages: ${getTotalMessages()}")
    }

    // ── Core gate ─────────────────────────────────────────────────────────────

    /**
     * Returns true if the paywall should be shown right now.
     * Enforces the 5-minute cooldown to prevent spammy UX.
     */
    fun shouldTrigger(reason: TriggerReason): Boolean {
        val now = System.currentTimeMillis()
        val elapsed = now - lastShownMs.get()
        if (elapsed < COOLDOWN_MS) {
            LoggingService.debug(TAG, "Paywall suppressed (cooldown ${elapsed / 1000}s < ${COOLDOWN_MS / 1000}s)")
            return false
        }
        lastTriggerReason = reason
        lastShownMs.set(now)
        prefs?.edit()?.putLong(KEY_LAST_SHOWN, now)?.apply()
        AnalyticsService.paywallTriggered(reason.source)
        LoggingService.info(TAG, "Paywall triggered: ${reason.source}")
        return true
    }

    // ── Event hooks ───────────────────────────────────────────────────────────

    /** Call on every message sent. Returns true → navigate to paywall. */
    fun onMessageSent(isPremium: Boolean): Boolean {
        if (isPremium) return false
        val p = prefs ?: return false
        val total = p.getInt(KEY_TOTAL_MSGS, 0) + 1
        p.edit().putInt(KEY_TOTAL_MSGS, total).apply()
        if (total == MSG_TRIGGER_COUNT) {
            return shouldTrigger(TriggerReason.MessageThreshold)
        }
        return false
    }

    /** Call after a successful agent execution. Returns true → navigate to paywall. */
    fun onAgentExecuted(isPremium: Boolean): Boolean {
        if (isPremium) return false
        val p = prefs ?: return false
        if (p.getBoolean(KEY_AGENT_DONE, false)) return false
        p.edit().putBoolean(KEY_AGENT_DONE, true).apply()
        return shouldTrigger(TriggerReason.FirstAgentExecution)
    }

    /** Call when user taps a locked premium feature. Returns true → navigate to paywall. */
    fun onPremiumFeatureAttempt(): Boolean {
        AnalyticsService.premiumFeatureAttempted("locked_feature")
        return shouldTrigger(TriggerReason.PremiumFeatureAttempt)
    }

    /** Call when daily limit is hit. Returns true → navigate to paywall. */
    fun onLimitReached(): Boolean = shouldTrigger(TriggerReason.LimitReached)

    // ── Usage stats ───────────────────────────────────────────────────────────

    fun getTotalMessages(): Int = prefs?.getInt(KEY_TOTAL_MSGS, 0) ?: 0

    fun getUsagePercent(subscriptionManager: SubscriptionManager): Int {
        val summary = subscriptionManager.getUsageSummary()
        if (summary.isPremiumEffective()) return 0
        val limit = PricingConfig.FREE_DAILY_MESSAGES
        if (limit == 0) return 100
        return ((summary.messagesUsed.toFloat() / limit) * 100).toInt().coerceIn(0, 100)
    }

    private fun SubscriptionManager.UsageSummary.isPremiumEffective(): Boolean =
        messagesLimit == PricingConfig.PREMIUM_DAILY_MESSAGES
}
