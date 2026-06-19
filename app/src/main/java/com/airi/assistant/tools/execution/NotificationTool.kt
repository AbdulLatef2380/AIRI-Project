package com.airi.assistant.tools.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.airi.assistant.R

/**
 * NotificationTool — real notification creation and management.
 *
 * Provides:
 *   - [post]     — push a notification to the status bar
 *   - [postProgress] — ongoing progress notification (auto-updated)
 *   - [cancel]   — dismiss a notification by ID
 *   - [getActive] — list active (posted) notifications via NotificationManager
 *
 * Permissions:
 *   POST_NOTIFICATIONS is required on Android 13+ (API 33+).
 *   The manifest already declares it; runtime grant must be requested by the UI.
 */
class NotificationTool(private val context: Context) {

    private val TAG = "NotificationTool"

    private val nm: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_AIRI_GENERAL   = "airi_general"
        const val CHANNEL_AIRI_TASKS     = "airi_tasks"
        const val CHANNEL_AIRI_REMINDERS = "airi_reminders"
    }

    init {
        ensureChannels()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Post API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Post a simple notification.
     *
     * @param id        Unique notification ID (use to update or cancel later)
     * @param title     Notification title
     * @param body      Notification body text
     * @param channel   Channel ID (use CHANNEL_* constants)
     * @param tapIntent Optional intent to launch on tap
     * @param priority  NotificationCompat.PRIORITY_* constant
     */
    fun post(
        id:         Int,
        title:      String,
        body:       String,
        channel:    String    = CHANNEL_AIRI_GENERAL,
        tapIntent:  Intent?   = null,
        priority:   Int       = NotificationCompat.PRIORITY_DEFAULT
    ): Boolean {
        return try {
            val builder = NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(priority)
                .setAutoCancel(true)

            tapIntent?.let {
                val pi = PendingIntent.getActivity(
                    context, id, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(pi)
            }

            nm.notify(id, builder.build())
            Log.i(TAG, "Notification posted id=$id title='$title'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "post failed: ${e.message}")
            false
        }
    }

    /**
     * Post or update a progress notification.
     *
     * @param id       Notification ID
     * @param title    Title
     * @param body     Progress description
     * @param progress Current progress (0–100). Pass -1 for indeterminate.
     * @param done     True when complete (removes progress bar)
     */
    fun postProgress(
        id:       Int,
        title:    String,
        body:     String,
        progress: Int     = -1,
        done:     Boolean = false
    ): Boolean {
        return try {
            val builder = NotificationCompat.Builder(context, CHANNEL_AIRI_TASKS)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(!done)
                .setAutoCancel(done)

            if (!done) {
                if (progress < 0) {
                    builder.setProgress(0, 0, true)
                } else {
                    builder.setProgress(100, progress.coerceIn(0, 100), false)
                }
            }

            nm.notify(id, builder.build())
            true
        } catch (e: Exception) {
            Log.e(TAG, "postProgress failed: ${e.message}")
            false
        }
    }

    /**
     * Post a reminder notification with snooze-style action.
     */
    fun postReminder(
        id:      Int,
        title:   String,
        body:    String,
        channel: String = CHANNEL_AIRI_REMINDERS
    ): Boolean = post(id, title, body, channel, priority = NotificationCompat.PRIORITY_HIGH)

    // ─────────────────────────────────────────────────────────────────────────
    // Cancel API
    // ─────────────────────────────────────────────────────────────────────────

    /** Dismiss a notification by ID. */
    fun cancel(id: Int) {
        nm.cancel(id)
        Log.i(TAG, "Notification cancelled id=$id")
    }

    /** Dismiss all AIRI-posted notifications. */
    fun cancelAll() {
        nm.cancelAll()
        Log.i(TAG, "All notifications cancelled")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * List currently active (posted) notifications visible to AIRI.
     *
     * Note: This only returns notifications posted by AIRI itself.
     * Reading other apps' notifications requires NotificationListenerService.
     */
    fun getPostedNotifications(): List<NotificationInfo> {
        return try {
            // getActiveNotifications() returns notifications posted by this package
            val active: Array<StatusBarNotification> = nm.activeNotifications
            active.map { sbn ->
                NotificationInfo(
                    id          = sbn.id,
                    packageName = sbn.packageName,
                    title       = sbn.notification.extras
                        .getString(Notification.EXTRA_TITLE) ?: "",
                    body        = sbn.notification.extras
                        .getString(Notification.EXTRA_TEXT) ?: "",
                    postedAtMs  = sbn.postTime
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPostedNotifications failed: ${e.message}")
            emptyList()
        }
    }

    /** True if POST_NOTIFICATIONS permission is granted (Android 13+). */
    fun canPost(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channels = listOf(
            NotificationChannel(CHANNEL_AIRI_GENERAL,   "AIRI",           NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "General AIRI notifications"
            },
            NotificationChannel(CHANNEL_AIRI_TASKS,     "AIRI Tasks",     NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background task progress"
                setShowBadge(false)
            },
            NotificationChannel(CHANNEL_AIRI_REMINDERS, "AIRI Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders and alerts"
            }
        )
        channels.forEach { nm.createNotificationChannel(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data types
    // ─────────────────────────────────────────────────────────────────────────

    data class NotificationInfo(
        val id:          Int,
        val packageName: String,
        val title:       String,
        val body:        String,
        val postedAtMs:  Long
    )
}
