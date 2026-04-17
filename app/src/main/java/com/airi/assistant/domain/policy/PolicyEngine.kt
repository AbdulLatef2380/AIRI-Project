package com.airi.assistant.domain.policy

import com.airi.assistant.domain.auth.AuthService
import com.airi.assistant.domain.error.AppError
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import com.airi.assistant.domain.monetization.SubscriptionManager
import com.airi.assistant.domain.network.NetworkService
import java.util.concurrent.ConcurrentHashMap

object PolicyEngine {

    private val rateLimiter = ConcurrentHashMap<String, MutableList<Long>>()
    private const val RATE_LIMIT_WINDOW_MS    = 60_000L
    private const val RATE_LIMIT_MAX_REQUESTS = 20

    sealed class PolicyResult {
        object Allowed : PolicyResult()
        data class Denied(val error: AppError) : PolicyResult()
    }

    // ── Network ───────────────────────────────────────────────────────────────

    fun checkNetwork(networkService: NetworkService): PolicyResult {
        val allowed = networkService.isOnline()
        EventBus.emitSync(AppEvent.PolicyChecked("network", allowed,
            if (!allowed) "No internet connection" else null))
        return if (allowed) PolicyResult.Allowed
        else PolicyResult.Denied(AppError.NetworkUnavailable())
    }

    // ── Authentication ────────────────────────────────────────────────────────

    fun checkAuthentication(authService: AuthService): PolicyResult {
        val allowed = authService.isSignedIn()
        EventBus.emitSync(AppEvent.PolicyChecked("authentication", allowed,
            if (!allowed) "User not signed in" else null))
        return if (allowed) PolicyResult.Allowed
        else PolicyResult.Denied(AppError.AuthenticationFailed("Authentication required. Please sign in."))
    }

    fun checkEmailVerification(authService: AuthService): PolicyResult {
        if (!authService.isSignedIn()) {
            EventBus.emitSync(AppEvent.PolicyChecked("email_verification", false, "Not signed in"))
            return PolicyResult.Denied(AppError.AuthenticationFailed("Authentication required."))
        }
        EventBus.emitSync(AppEvent.PolicyChecked("email_verification", true))
        return PolicyResult.Allowed
    }

    // ── Rate limit ────────────────────────────────────────────────────────────

    fun checkRateLimit(key: String): PolicyResult {
        val now = System.currentTimeMillis()
        val windowStart = now - RATE_LIMIT_WINDOW_MS
        val timestamps = rateLimiter.getOrPut(key) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeAll { it < windowStart }
            if (timestamps.size >= RATE_LIMIT_MAX_REQUESTS) {
                EventBus.emitSync(AppEvent.PolicyChecked("rate_limit", false, "Exceeded for key=$key"))
                return PolicyResult.Denied(AppError.RateLimitExceeded())
            }
            timestamps.add(now)
        }
        EventBus.emitSync(AppEvent.PolicyChecked("rate_limit", true))
        return PolicyResult.Allowed
    }

    // ── Input validation ──────────────────────────────────────────────────────

    fun checkAgentExecution(input: String): PolicyResult {
        if (input.isBlank()) {
            EventBus.emitSync(AppEvent.PolicyChecked("input_validation", false, "Empty input"))
            return PolicyResult.Denied(AppError.PolicyViolation("INPUT_EMPTY", "Input cannot be empty."))
        }
        if (input.length > 4096) {
            EventBus.emitSync(AppEvent.PolicyChecked("input_validation", false, "Input too long: ${input.length}"))
            return PolicyResult.Denied(
                AppError.PolicyViolation("INPUT_TOO_LONG", "Input exceeds maximum length of 4096 characters.")
            )
        }
        EventBus.emitSync(AppEvent.PolicyChecked("input_validation", true))
        return PolicyResult.Allowed
    }

    // ── Subscription limits ───────────────────────────────────────────────────

    fun checkSubscriptionMessage(subscriptionManager: SubscriptionManager): PolicyResult {
        val allowed = subscriptionManager.canSendMessage()
        return if (allowed) PolicyResult.Allowed
        else PolicyResult.Denied(AppError.PolicyViolation(
            "DAILY_LIMIT",
            "Daily message limit reached. Upgrade to Premium for unlimited messaging."
        ))
    }

    fun checkSubscriptionAgent(subscriptionManager: SubscriptionManager): PolicyResult {
        val allowed = subscriptionManager.canExecuteAgent()
        return if (allowed) PolicyResult.Allowed
        else PolicyResult.Denied(AppError.PolicyViolation(
            "DAILY_LIMIT",
            "Daily agent execution limit reached. Upgrade to Premium for unlimited use."
        ))
    }

    fun checkSubscriptionSkill(subscriptionManager: SubscriptionManager): PolicyResult {
        val allowed = subscriptionManager.canUseSkill()
        return if (allowed) PolicyResult.Allowed
        else PolicyResult.Denied(AppError.PolicyViolation(
            "DAILY_LIMIT",
            "Daily skill limit reached. Upgrade to Premium for unlimited skill use."
        ))
    }

    // ── Premium feature gate ──────────────────────────────────────────────────

    fun checkPremiumFeature(subscriptionManager: SubscriptionManager, featureName: String): PolicyResult {
        val allowed = subscriptionManager.canAccessFeature(featureName)
        EventBus.emitSync(AppEvent.PolicyChecked("premium[$featureName]", allowed,
            if (!allowed) "Premium required" else null))
        return if (allowed) PolicyResult.Allowed
        else PolicyResult.Denied(AppError.PolicyViolation(
            "PREMIUM_REQUIRED",
            "'$featureName' requires a Premium subscription."
        ))
    }

    fun checkCustomSkillsPremium(subscriptionManager: SubscriptionManager): PolicyResult =
        checkPremiumFeature(subscriptionManager, "custom_skills")

    // ── Composite helper ──────────────────────────────────────────────────────

    fun checkAll(vararg checks: () -> PolicyResult): PolicyResult {
        for (check in checks) {
            val result = check()
            if (result is PolicyResult.Denied) return result
        }
        return PolicyResult.Allowed
    }
}
