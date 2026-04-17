package com.airi.assistant.analytics

import android.content.Context
import android.os.Bundle
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AnalyticsService {

    private const val TAG = "Analytics"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var firebaseAnalytics: Any? = null

    fun init(context: Context) {
        runCatching {
            val cls = Class.forName("com.google.firebase.analytics.FirebaseAnalytics")
            firebaseAnalytics = cls
                .getMethod("getInstance", Context::class.java)
                .invoke(null, context.applicationContext)
            LoggingService.info(TAG, "Firebase Analytics linked")
        }
    }

    // ── User Lifecycle ────────────────────────────────────────────────────────

    fun appOpen() = track("app_open")

    fun installOpen() = track("install_open")

    fun signup(method: String) = track("signup", "method" to method)

    fun login(method: String) = track("login", "method" to method)

    fun sessionStart() = track("session_start")

    fun sessionEnd(durationMs: Long) = track("session_end", "duration_ms" to durationMs.toString())

    // ── Monetization ──────────────────────────────────────────────────────────

    fun paywallView(source: String) = track("paywall_view", "source" to source)

    fun upgradeClick() = track("upgrade_click")

    fun purchaseSuccess(productId: String) = track("purchase_success", "product_id" to productId)

    fun purchaseFailed(reason: String) = track("purchase_failed", "reason" to reason)

    fun restorePurchase(success: Boolean) = track("restore_purchase", "success" to success.toString())

    fun onboardingStarted() = track("onboarding_started")

    fun onboardingCompleted() = track("onboarding_completed")

    fun onboardingSkipped() = track("onboarding_skipped")

    fun referralSent(channel: String, code: String) = track(
        "referral_sent",
        "channel" to channel,
        "code" to code
    )

    fun referralJoined(code: String) = track("referral_joined", "code" to code)

    fun shareableOutputShared(channel: String) = track("shareable_output_shared", "channel" to channel)

    fun firstMessageSent() = track("first_message_sent")

    fun funnelStep(step: String) = track("funnel_step", "step" to step)

    // ── Usage ─────────────────────────────────────────────────────────────────

    fun messageSent() = track("message_sent")

    fun agentExecuted(agentTag: String) = track("agent_executed", "agent" to agentTag)

    fun skillUsed(skillName: String) = track("skill_used", "skill" to skillName)

    fun skillCreated(skillName: String) = track("skill_created", "skill" to skillName)

    fun skillExecuted(skillName: String) = track("skill_executed", "skill" to skillName)

    fun skillFailed(skillName: String, reason: String) = track(
        "skill_failed",
        "skill" to skillName,
        "reason" to reason
    )

    // ── Limits ────────────────────────────────────────────────────────────────

    fun limitReached(limitType: String, used: Int, max: Int) = track(
        "limit_reached",
        "type" to limitType,
        "used" to used.toString(),
        "max" to max.toString()
    )

    fun paywallTriggered(source: String) = track("paywall_triggered", "source" to source)

    // ── Feature exposure ──────────────────────────────────────────────────────

    fun premiumFeatureAttempted(feature: String) = track("premium_feature_attempted", "feature" to feature)

    fun featureDiscovered(feature: String) = track("feature_discovered", "feature" to feature)

    // ── Core tracking ─────────────────────────────────────────────────────────

    private fun track(event: String, vararg params: Pair<String, String>) {
        scope.launch {
            val paramStr = if (params.isEmpty()) ""
            else " | ${params.joinToString(" | ") { "${it.first}=${it.second}" }}"
            LoggingService.info(TAG, "[EVENT] $event$paramStr")

            runCatching {
                val analytics = firebaseAnalytics ?: return@runCatching
                val bundle = Bundle()
                params.forEach { (k, v) -> bundle.putString(k.take(40), v.take(100)) }
                analytics.javaClass
                    .getMethod("logEvent", String::class.java, Bundle::class.java)
                    .invoke(analytics, event.take(40), bundle)
            }
        }
    }
}
