package com.airi.assistant.domain.monetization

import android.content.Context
import android.content.SharedPreferences
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.domain.logging.ProofLogger
import java.util.concurrent.atomic.AtomicLong

object PaywallTriggerEngine {

    private const val TAG               = "PaywallTriggerEngine"
    private const val COOLDOWN_MS       = 5 * 60 * 1000L
    private const val MSG_TRIGGER_COUNT = 10
    private const val PREFS_NAME        = "airi_paywall_engine"
    private const val KEY_TOTAL_MSGS    = "total_messages"
    private const val KEY_AGENT_DONE    = "agent_trigger_fired"
    private const val KEY_LAST_SHOWN    = "last_paywall_ms"
    private const val KEY_NUDGE_COUNT   = "nudge_count"
    private const val KEY_SUCCESS_SHOWN = "success_trigger_fired"
    private const val KEY_SPEED_SHOWN   = "speed_trigger_fired"
    private const val KEY_CUT_SHOWN     = "cut_trigger_fired"
    private const val KEY_POWER_SHOWN   = "power_user_trigger_fired"

    // ── Upsell level — controls how intrusive the nudge is ────────────────────

    enum class UpsellLevel { NONE, HINT, BANNER, FULL }

    // ── Trigger reasons ───────────────────────────────────────────────────────

    sealed class TriggerReason(val source: String) {
        object LimitReached          : TriggerReason("limit_reached")
        object MessageThreshold      : TriggerReason("message_threshold")
        object FirstAgentExecution   : TriggerReason("first_agent")
        object PremiumFeatureAttempt : TriggerReason("premium_feature")
        object SuccessMoment         : TriggerReason("success_moment")
        object SpeedUpsell           : TriggerReason("speed_upsell")
        object ResponseCut           : TriggerReason("response_cut")
        object PowerUser             : TriggerReason("power_user")
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

    // ── Progressive upsell level ──────────────────────────────────────────────

    fun getUpsellLevel(): UpsellLevel {
        val nudges = prefs?.getInt(KEY_NUDGE_COUNT, 0) ?: 0
        return when {
            nudges <= 0 -> UpsellLevel.HINT
            nudges == 1 -> UpsellLevel.BANNER
            else        -> UpsellLevel.FULL
        }
    }

    private fun recordNudge() {
        val p = prefs ?: return
        val n = p.getInt(KEY_NUDGE_COUNT, 0) + 1
        p.edit().putInt(KEY_NUDGE_COUNT, n).apply()
    }

    // ── Core gate ─────────────────────────────────────────────────────────────

    /**
     * Returns the UpsellLevel if the paywall should fire, NONE if suppressed.
     * Enforces the 5-minute cooldown. Logs AIRI_PAYWALL_SHOWN on every fire.
     */
    fun shouldTrigger(reason: TriggerReason): UpsellLevel {
        val now     = System.currentTimeMillis()
        val elapsed = now - lastShownMs.get()
        if (elapsed < COOLDOWN_MS) {
            LoggingService.debug(TAG, "Paywall suppressed (cooldown ${elapsed / 1000}s)")
            return UpsellLevel.NONE
        }
        val level = getUpsellLevel()
        lastTriggerReason = reason
        lastShownMs.set(now)
        prefs?.edit()?.putLong(KEY_LAST_SHOWN, now)?.apply()
        recordNudge()
        AnalyticsService.paywallTriggered(reason.source)
        AnalyticsService.paywallShown(reason.source, level.name.lowercase())
        LoggingService.info(TAG, "AIRI_PAYWALL_SHOWN: reason=${reason.source} level=${level.name}")
        LogMonetization("trigger reason=${reason.source} level=${level.name}")
        ProofLogger.paywallTriggered(reason.source, level.name)
        return level
    }

    /** Backwards-compat Boolean variant — returns true for BANNER or FULL. */
    fun shouldTriggerBool(reason: TriggerReason): Boolean =
        shouldTrigger(reason) != UpsellLevel.NONE

    // ── Event hooks ───────────────────────────────────────────────────────────

    /** Call on every message sent. Returns UpsellLevel if paywall should appear. */
    fun onMessageSent(isPremium: Boolean): UpsellLevel {
        if (isPremium) return UpsellLevel.NONE
        val p = prefs ?: return UpsellLevel.NONE
        val total = p.getInt(KEY_TOTAL_MSGS, 0) + 1
        p.edit().putInt(KEY_TOTAL_MSGS, total).apply()
        if (total == MSG_TRIGGER_COUNT) {
            return shouldTrigger(TriggerReason.MessageThreshold)
        }
        return UpsellLevel.NONE
    }

    /** Call after a successful agent execution. Returns UpsellLevel. */
    fun onAgentExecuted(isPremium: Boolean): UpsellLevel {
        if (isPremium) return UpsellLevel.NONE
        val p = prefs ?: return UpsellLevel.NONE
        if (p.getBoolean(KEY_AGENT_DONE, false)) return UpsellLevel.NONE
        p.edit().putBoolean(KEY_AGENT_DONE, true).apply()
        return shouldTrigger(TriggerReason.FirstAgentExecution)
    }

    /** Call when user taps a locked premium feature. Returns UpsellLevel. */
    fun onPremiumFeatureAttempt(): UpsellLevel {
        AnalyticsService.premiumFeatureAttempted("locked_feature")
        return shouldTrigger(TriggerReason.PremiumFeatureAttempt)
    }

    /** Call when daily limit is hit. Logs AIRI_LIMIT_HIT + returns UpsellLevel. */
    fun onLimitReached(type: String = "messages", used: Int = 0, max: Int = 0): UpsellLevel {
        AnalyticsService.limitHit(type, used, max)
        return shouldTrigger(TriggerReason.LimitReached)
    }

    /**
     * Phase 2 — success_moment trigger.
     * Call after each successful AI response for free users.
     * Fires once after [PricingConfig.SUCCESS_TRIGGER_COUNT] consecutive successes.
     */
    fun onSuccessfulResponse(consecutiveCount: Int, isPremium: Boolean): UpsellLevel {
        if (isPremium) return UpsellLevel.NONE
        val p = prefs ?: return UpsellLevel.NONE
        if (p.getBoolean(KEY_SUCCESS_SHOWN, false)) return UpsellLevel.NONE
        if (consecutiveCount >= PricingConfig.SUCCESS_TRIGGER_COUNT) {
            p.edit().putBoolean(KEY_SUCCESS_SHOWN, true).apply()
            LoggingService.info(TAG, "AIRI_PAYWALL_SHOWN: reason=success_moment count=$consecutiveCount")
            return shouldTrigger(TriggerReason.SuccessMoment)
        }
        return UpsellLevel.NONE
    }

    /**
     * Phase 5 — speed_upsell trigger.
     * Call after a slow response is detected for a free user.
     */
    fun onSlowResponse(latencyMs: Long, isPremium: Boolean): UpsellLevel {
        if (isPremium) return UpsellLevel.NONE
        if (latencyMs < PricingConfig.SPEED_UPSELL_THRESHOLD_MS) return UpsellLevel.NONE
        val p = prefs ?: return UpsellLevel.NONE
        if (p.getBoolean(KEY_SPEED_SHOWN, false)) return UpsellLevel.NONE
        p.edit().putBoolean(KEY_SPEED_SHOWN, true).apply()
        LoggingService.info(TAG, "AIRI_PAYWALL_SHOWN: reason=speed_upsell latency_ms=$latencyMs")
        return shouldTrigger(TriggerReason.SpeedUpsell)
    }

    fun onResponseCut(isPremium: Boolean): UpsellLevel {
        if (isPremium) return UpsellLevel.NONE
        val p = prefs ?: return UpsellLevel.NONE
        if (p.getBoolean(KEY_CUT_SHOWN, false)) return UpsellLevel.NONE
        p.edit().putBoolean(KEY_CUT_SHOWN, true).apply()
        LoggingService.info(TAG, "AIRI_PAYWALL_SHOWN: reason=response_cut")
        return shouldTrigger(TriggerReason.ResponseCut)
    }

    fun onPowerUser(totalMessages: Int, isPremium: Boolean): UpsellLevel {
        if (isPremium || totalMessages < 7) return UpsellLevel.NONE
        val p = prefs ?: return UpsellLevel.NONE
        if (p.getBoolean(KEY_POWER_SHOWN, false)) return UpsellLevel.NONE
        p.edit().putBoolean(KEY_POWER_SHOWN, true).apply()
        LoggingService.info(TAG, "AIRI_PAYWALL_SHOWN: reason=power_user total_messages=$totalMessages")
        return shouldTrigger(TriggerReason.PowerUser)
    }

    fun evaluateDataDrivenUpsell(
        wasCut: Boolean,
        latencyMs: Long,
        totalMessages: Int,
        isPremium: Boolean
    ): TriggerReason? {
        if (isPremium) return null
        return when {
            wasCut -> TriggerReason.ResponseCut
            latencyMs >= PricingConfig.SPEED_UPSELL_THRESHOLD_MS -> TriggerReason.SpeedUpsell
            totalMessages >= 7 -> TriggerReason.PowerUser
            else -> null
        }
    }

    // ── Phase 4 — Value-based dynamic messaging ────────────────────────────────

    fun getPaywallMessage(reason: String): String = when (reason) {
        TriggerReason.LimitReached.source ->
            "You've used all your free messages today. Upgrade for unlimited AI — no waiting, no limits."
        TriggerReason.MessageThreshold.source ->
            "You're getting value from AIRI. Unlock unlimited responses and keep the momentum going."
        TriggerReason.FirstAgentExecution.source ->
            "Your AI agent just ran successfully. Upgrade to run unlimited agents — anytime, any task."
        TriggerReason.PremiumFeatureAttempt.source ->
            "This feature is available to Premium members. One upgrade, full power."
        TriggerReason.SuccessMoment.source ->
            "You've had ${PricingConfig.SUCCESS_TRIGGER_COUNT} great responses. Unlock the full experience."
        TriggerReason.SpeedUpsell.source ->
            "Get faster responses with Premium. Priority processing, zero throttling."
        TriggerReason.ResponseCut.source ->
            "AIRI shortened this response to stay fast. Upgrade for longer answers without speed limits."
        TriggerReason.PowerUser.source ->
            "You're using AIRI like a power user. Upgrade for unlimited conversations and stronger responses."
        else ->
            "Unlock unlimited AI power. Faster responses, advanced features, no daily limits."
    }

    fun getPaywallMessage(reason: TriggerReason): String = getPaywallMessage(reason.source)

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

    private fun LogMonetization(message: String) {
        android.util.Log.d("AIRI_MONET", message)
    }
}
