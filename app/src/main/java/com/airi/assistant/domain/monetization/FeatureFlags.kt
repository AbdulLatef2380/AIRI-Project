package com.airi.assistant.domain.monetization

import android.content.Context

data class FeatureFlag(
    val key: String,
    val defaultEnabled: Boolean = true,
    val requiresPremium: Boolean = false,
    val displayName: String = key
)

object FeatureFlags {

    val BACKGROUND_AGENT      = FeatureFlag("background_agent",      defaultEnabled = true,  requiresPremium = true,  displayName = "Background Agent")
    val SKILL_AUTOMATION      = FeatureFlag("skill_automation",       defaultEnabled = true,  requiresPremium = false, displayName = "Skill Automation")
    val UNLIMITED_CHAT        = FeatureFlag("unlimited_chat",         defaultEnabled = false, requiresPremium = true,  displayName = "Unlimited Chat")
    val MULTI_MODEL_SUPPORT   = FeatureFlag("multi_model_support",    defaultEnabled = true,  requiresPremium = false, displayName = "Multi-Model Support")
    val OBSERVABILITY_DASH    = FeatureFlag("observability_dashboard", defaultEnabled = true, requiresPremium = false, displayName = "Observability Dashboard")
    val PRIORITY_MODEL_ACCESS = FeatureFlag("priority_model_access",  defaultEnabled = true,  requiresPremium = true,  displayName = "Priority Model Access")

    private val allFlags = listOf(
        BACKGROUND_AGENT, SKILL_AUTOMATION, UNLIMITED_CHAT,
        MULTI_MODEL_SUPPORT, OBSERVABILITY_DASH, PRIORITY_MODEL_ACCESS
    )

    fun isEnabled(flag: FeatureFlag, context: Context, subscriptionManager: SubscriptionManager): Boolean {
        val localEnabled = context.getSharedPreferences("airi_feature_flags", Context.MODE_PRIVATE)
            .getBoolean(flag.key, flag.defaultEnabled)
        if (!localEnabled) return false
        if (flag.requiresPremium && !subscriptionManager.isPremium()) return false
        return true
    }

    fun setEnabled(flag: FeatureFlag, enabled: Boolean, context: Context) {
        context.getSharedPreferences("airi_feature_flags", Context.MODE_PRIVATE)
            .edit().putBoolean(flag.key, enabled).apply()
    }

    fun getAll(): List<FeatureFlag> = allFlags
}
