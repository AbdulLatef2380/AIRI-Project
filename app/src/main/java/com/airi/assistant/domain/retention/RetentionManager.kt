package com.airi.assistant.domain.retention

import android.content.Context
import android.content.SharedPreferences
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.airi.assistant.domain.logging.LoggingService
import java.util.concurrent.TimeUnit

object RetentionManager {

    private const val TAG                    = "RetentionManager"
    private const val PREFS_NAME             = "airi_retention"
    private const val KEY_LAST_ACTIVE        = "last_active_ms"
    private const val KEY_SESSION_COUNT      = "session_count"
    private const val KEY_TOTAL_MESSAGES     = "total_lifetime_messages"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val INACTIVITY_THRESHOLD_MS = 4 * 60 * 60 * 1000L   // 4 hours

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        LoggingService.debug(TAG, "Initialized — sessions: ${getSessionCount()}")
    }

    // ── Activity tracking ─────────────────────────────────────────────────────

    fun recordActivity() {
        prefs?.edit()?.putLong(KEY_LAST_ACTIVE, System.currentTimeMillis())?.apply()
    }

    fun incrementSession() {
        val count = prefs?.getInt(KEY_SESSION_COUNT, 0) ?: 0
        prefs?.edit()?.putInt(KEY_SESSION_COUNT, count + 1)?.apply()
        recordActivity()
        LoggingService.debug(TAG, "Session ${count + 1} started")
    }

    fun incrementMessageCount() {
        val count = prefs?.getInt(KEY_TOTAL_MESSAGES, 0) ?: 0
        prefs?.edit()?.putInt(KEY_TOTAL_MESSAGES, count + 1)?.apply()
        recordActivity()
    }

    // ── Inactivity detection ──────────────────────────────────────────────────

    fun isInactive(): Boolean {
        val lastMs = getLastActiveMs()
        if (lastMs == 0L) return false
        return System.currentTimeMillis() - lastMs > INACTIVITY_THRESHOLD_MS
    }

    fun getLastActiveMs(): Long = prefs?.getLong(KEY_LAST_ACTIVE, 0L) ?: 0L

    fun getSessionCount(): Int = prefs?.getInt(KEY_SESSION_COUNT, 0) ?: 0

    fun getTotalMessages(): Int = prefs?.getInt(KEY_TOTAL_MESSAGES, 0) ?: 0

    // ── Re-engagement messaging ───────────────────────────────────────────────

    fun getReEngagementMessage(): String {
        val count = getSessionCount()
        return when {
            count <= 1  -> "AIRI can automate tasks for you — try it now"
            count <= 5  -> "You've been away! Your AI assistant is ready to help"
            count <= 20 -> "AIRI missed you. Let's automate your workflow"
            else        -> "Your on-device AI is ready — ask it anything"
        }
    }

    fun shouldShowReEngagement(): Boolean = isInactive() && getSessionCount() > 0

    fun areNotificationsEnabled(): Boolean = prefs?.getBoolean(KEY_NOTIFICATIONS_ENABLED, true) ?: true

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled)?.apply()
    }

    fun scheduleReEngagementReminder(context: Context) {
        if (!areNotificationsEnabled()) return
        val request = OneTimeWorkRequestBuilder<ReEngagementNotificationWorker>()
            .setInitialDelay(8, TimeUnit.HOURS)
            .build()
        // B-01: Use KEEP so a second call within the same 8-hour window does not
        // create a duplicate worker (was REPLACE, causing multiple notifications).
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "airi_reengagement_reminder",
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
