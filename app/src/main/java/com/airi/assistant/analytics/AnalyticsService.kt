package com.airi.assistant.analytics

import android.content.Context
import android.os.Bundle
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.telemetry.TelemetryConsentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application-level analytics bridge to Firebase Analytics.
 *
 * ── CONSENT GATE ─────────────────────────────────────────────────────────────
 * Every Firebase event is guarded by [TelemetryConsentStore.current.analyticsEnabled].
 * Firebase Analytics is a live build dependency (firebase-analytics:32.8.0).
 * Without this gate, events would fire from the moment the SDK is initialised,
 * regardless of the user's opt-in choice in PrivacyDataSettingsScreen — a GDPR
 * / CCPA violation.
 *
 * A previous implementation had NO consent check in [track]: it called the
 * Firebase logEvent reflection path unconditionally whenever [firebaseAnalytics]
 * was non-null (i.e. always after [init]). The fix gates [track] on
 * [TelemetryConsentStore.current.analyticsEnabled], which defaults to FALSE
 * (opt-out by default, per [TelemetryConsentStore] design).
 *
 * LoggingService events still fire regardless (local-only, no network).
 *
 * ── USAGE ────────────────────────────────────────────────────────────────────
 * Call [init] from Application.onCreate AFTER [TelemetryConsentStore] is
 * constructed in ServiceLocator so the consent store is available immediately.
 */
object AnalyticsService {

    private const val TAG = "Analytics"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var firebaseAnalytics: Any? = null
    @Volatile private var consentStore: TelemetryConsentStore? = null

    fun init(context: Context, consent: TelemetryConsentStore) {
        consentStore = consent
        runCatching {
            val cls = Class.forName("com.google.firebase.analytics.FirebaseAnalytics")
            firebaseAnalytics = cls
                .getMethod("getInstance", Context::class.java)
                .invoke(null, context.applicationContext)
            LoggingService.info(TAG, "Firebase Analytics linked")
            setCollectionEnabled(consent.current.analyticsEnabled)
        }
    }

    /** Mirrors the user's persisted choice into Firebase's collection state. */
    fun setCollectionEnabled(enabled: Boolean) {
        val analytics = firebaseAnalytics ?: return
        runCatching {
            analytics.javaClass
                .getMethod("setAnalyticsCollectionEnabled", Boolean::class.javaPrimitiveType)
                .invoke(analytics, enabled)
            LoggingService.info(TAG, "Firebase Analytics collection enabled=$enabled")
        }.onFailure { error ->
            LoggingService.warn(TAG, "Firebase Analytics collection update failed type=${error.javaClass.simpleName}")
        }
    }

    // ── User Lifecycle ────────────────────────────────────────────────────────

    fun appOpen()                           = track("app_open")
    fun installOpen()                       = track("install_open")
    fun signup(method: String)              = track("signup", "method" to method)
    fun login(method: String)               = track("login", "method" to method)
    fun sessionStart()                      = track("session_start")
    fun sessionEnd(durationMs: Long)        = track("session_end", "duration_ms" to durationMs.toString())

    // ── Monetization ──────────────────────────────────────────────────────────

    fun paywallView(source: String)         = track("paywall_view", "source" to source)
    fun upgradeClick()                      = track("upgrade_click")
    fun purchaseSuccess(productId: String)  = track("purchase_success", "product_id" to productId)
    fun purchaseFailed(reason: String)      = track("purchase_failed", "reason" to reason)
    fun restorePurchase(success: Boolean)   = track("restore_purchase", "success" to success.toString())
    fun onboardingStarted()                 = track("onboarding_started")
    fun onboardingCompleted()               = track("onboarding_completed")
    fun onboardingSkipped()                 = track("onboarding_skipped")

    fun referralSent(channel: String, code: String) = track(
        "referral_sent", "channel" to channel, "code" to code
    )
    fun referralJoined(code: String)        = track("referral_joined", "code" to code)
    fun shareableOutputShared(channel: String) = track("shareable_output_shared", "channel" to channel)
    fun firstMessageSent()                  = track("first_message_sent")
    fun funnelStep(step: String)            = track("funnel_step", "step" to step)

    // ── Usage ─────────────────────────────────────────────────────────────────

    fun modelLoaded(modelName: String, loadMs: Long) = track(
        "model_loaded", "model" to modelName, "load_ms" to loadMs.toString()
    )
    fun remoteModelUsed(url: String)        = track("remote_model_used", "url" to url.take(60))
    fun messageSent()                       = track("message_sent")
    fun agentExecuted(agentTag: String)     = track("agent_executed", "agent" to agentTag)
    fun skillUsed(skillName: String)        = track("skill_used", "skill" to skillName)
    fun skillCreated(skillName: String)     = track("skill_created", "skill" to skillName)
    fun skillExecuted(skillName: String)    = track("skill_executed", "skill" to skillName)
    fun skillFailed(skillName: String, reason: String) = track(
        "skill_failed", "skill" to skillName, "reason" to reason
    )
    fun responseGenerated(latencyMs: Long, tokensPerSec: Float, modelName: String, isFallback: Boolean) = track(
        "response_generated",
        "latency_ms"     to latencyMs.toString(),
        "tokens_per_sec" to "%.1f".format(tokensPerSec),
        "model"          to modelName.take(60),
        "fallback"       to isFallback.toString()
    )

    // ── Limits ────────────────────────────────────────────────────────────────

    fun limitReached(limitType: String, used: Int, max: Int) = track(
        "limit_reached", "type" to limitType, "used" to used.toString(), "max" to max.toString()
    )
    fun paywallTriggered(source: String)    = track("paywall_triggered", "source" to source)

    // ── Required AIRI monetization tags ──────────────────────────────────────

    fun paywallShown(reason: String, level: String = "full") {
        LoggingService.info(TAG, "AIRI_PAYWALL_SHOWN: reason=$reason level=$level")
        track("paywall_shown", "reason" to reason, "level" to level)
    }

    fun paywallClicked(reason: String = "") {
        LoggingService.info(TAG, "AIRI_PAYWALL_CLICKED: reason=$reason")
        track("paywall_clicked", "reason" to reason)
    }

    fun subscribed(productId: String) {
        LoggingService.info(TAG, "AIRI_SUBSCRIBED: product=$productId")
        track("airi_subscribed", "product_id" to productId)
    }

    fun limitHit(type: String, used: Int, max: Int) {
        LoggingService.info(TAG, "AIRI_LIMIT_HIT: type=$type used=$used max=$max")
        track("airi_limit_hit", "type" to type, "used" to used.toString(), "max" to max.toString())
    }

    fun softLimitApplied(phase: Int, tokenFactor: Float) {
        LoggingService.debug(TAG, "AIRI_SOFT_LIMIT: phase=$phase token_factor=$tokenFactor")
        track("soft_limit_applied", "phase" to phase.toString(), "token_factor" to "%.2f".format(tokenFactor))
    }

    fun powerLevelChanged(level: Float) {
        LoggingService.debug(TAG, "AIRI_POWER: level=%.2f".format(level))
    }

    // ── Feature exposure ──────────────────────────────────────────────────────

    fun premiumFeatureAttempted(feature: String) = track("premium_feature_attempted", "feature" to feature)
    fun featureDiscovered(feature: String)       = track("feature_discovered", "feature" to feature)

    // ── Core tracking ─────────────────────────────────────────────────────────

    /**
     * Dispatch one analytics event.
     *
     * LoggingService (local logcat only) always fires — it carries no PII to
     * any external server. Firebase is only invoked when the user has explicitly
     * granted analytics consent ([TelemetryConsentStore.ConsentState.analyticsEnabled]).
     */
    private fun track(event: String, vararg params: Pair<String, String>) {
        scope.launch {
            LoggingService.info(TAG, "[EVENT] $event")

            // ── Consent gate — Firebase only fires when user has opted in ──────
            val store = consentStore
            if (store == null || !store.current.analyticsEnabled) return@launch

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
