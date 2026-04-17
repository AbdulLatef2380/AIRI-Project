package com.airi.assistant.domain.growth

import android.content.Context
import com.airi.assistant.analytics.AnalyticsService

object OnboardingManager {
    private const val PREFS_NAME = "airi_onboarding"
    private const val KEY_COMPLETED = "completed"
    private const val KEY_STARTED = "started"

    private var context: Context? = null

    fun init(appContext: Context) {
        context = appContext.applicationContext
    }

    fun isCompleted(): Boolean {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return false
        return prefs.getBoolean(KEY_COMPLETED, false)
    }

    fun start() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        if (!prefs.getBoolean(KEY_STARTED, false)) {
            prefs.edit().putBoolean(KEY_STARTED, true).apply()
            AnalyticsService.onboardingStarted()
        }
    }

    fun complete() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        AnalyticsService.onboardingCompleted()
    }

    fun skip() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        AnalyticsService.onboardingSkipped()
    }
}