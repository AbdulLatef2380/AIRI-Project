package com.airi.assistant.domain.retention

import android.content.Context
import android.content.SharedPreferences
import com.airi.assistant.domain.logging.LoggingService

object RetentionManager {

    private const val TAG                    = "RetentionManager"
    private const val PREFS_NAME             = "airi_retention"
    private const val KEY_LAST_ACTIVE        = "last_active_ms"
    private const val KEY_SESSION_COUNT      = "session_count"
    private const val KEY_TOTAL_MESSAGES     = "total_lifetime_messages"
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
}
