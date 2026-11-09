package com.airi.assistant.ui.run

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.airi.assistant.MainActivity
import com.airi.assistant.R
import com.airi.assistant.ai.agent.trace.AgentTrace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Part 5 §1: the long-task notification. It is posted when a run that took a while ends while the
 * app is in the background, and it opens the run screen through `airi://agent/run/{id}`.
 *
 * The decision is a pure function ([LongRunNotificationPolicy]) so it can be unit-tested; the rest
 * is Android plumbing that is not exercised by a unit test.
 */
object LongRunNotificationPolicy {
    /** Shorter runs finish while the person is still looking at the chat; no need to interrupt. */
    const val MIN_DURATION_MS = 20_000L

    /** Wall-clock length of a finished run: start to its last recorded step. */
    fun durationMs(trace: AgentTrace): Long {
        val last = trace.steps.maxOfOrNull { it.timestampMs } ?: return 0L
        return (last - trace.timestamp).coerceAtLeast(0L)
    }

    /**
     * Notify only for a finished run that has tool steps (a plain chat reply is not an agent run),
     * ran at least [MIN_DURATION_MS], ended in the background, and was not stopped by the person
     * themselves (they already know).
     */
    fun shouldNotify(trace: AgentTrace, appInForeground: Boolean): Boolean =
        !trace.inProgress &&
            !appInForeground &&
            !trace.looksCancelled() &&
            buildRunCardModel(trace) != null &&
            durationMs(trace) >= MIN_DURATION_MS
}

/**
 * Part 5 §1: the "waiting for your approval" notification. The approval window is short
 * ([APPROVAL_TIMEOUT_MS]), so it is posted at the moment the gate opens, and only
 * when the person cannot see the in-app card.
 */
object ApprovalNotificationPolicy {
    fun shouldNotify(appInForeground: Boolean, executionId: String): Boolean =
        !appInForeground && executionId.isNotBlank()
}

/** Counts started activities so "is the app visible" needs no extra dependency. */
object AppForegroundTracker : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger(0)
    @Volatile private var registered = false

    val isForeground: Boolean get() = started.get() > 0

    fun register(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) { started.incrementAndGet() }
    override fun onActivityStopped(activity: Activity) {
        started.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

object AgentRunNotifier {
    const val CHANNEL_ID = "agent_runs"
    const val APPROVAL_CHANNEL_ID = "agent_approvals"

    /** Separate from the completion id space so finishing a run never replaces a live approval. */
    internal fun approvalNotificationId(executionId: String): Int =
        0x4150_0000 or (executionId.hashCode() and 0xFFFF)

    /** Stable per run, so a re-recorded run replaces its notification instead of stacking. */
    internal fun notificationId(runId: String): Int = 0x4152_0000 or (runId.hashCode() and 0xFFFF)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.run_notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.run_notif_channel_desc) },
        )
    }

    private fun ensureApprovalChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(APPROVAL_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                APPROVAL_CHANNEL_ID,
                context.getString(R.string.run_approval_notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.run_approval_notif_channel_desc) },
        )
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Posts the notification when the policy allows it; every failure is swallowed. */
    fun notifyIfNeeded(context: Context, trace: AgentTrace, appInForeground: Boolean) {
        if (!LongRunNotificationPolicy.shouldNotify(trace, appInForeground)) return
        runCatching { post(context, trace) }
    }

    // The permission is checked in canPost(); the lint check cannot see through the helper.
    @android.annotation.SuppressLint("MissingPermission")
    private fun post(context: Context, trace: AgentTrace) {
        if (!canPost(context)) return
        ensureChannel(context)
        val card = buildRunCardModel(trace) ?: return
        // Only a well-formed id is put into the link; otherwise the notification just opens the app.
        val link = AgentRunDeepLink.build(trace.id).takeIf { AgentRunDeepLink.parse(it) == trace.id }
        val intent = Intent(Intent.ACTION_VIEW, link?.let(Uri::parse), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val id = notificationId(trace.id)
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val headline = context.getString(if (trace.success) R.string.run_notif_done_title else R.string.run_notif_failed_title)
        // The task title can be private: the lock screen shows only the generic public version.
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(headline)
            .setContentText(context.getString(R.string.run_notif_public_text))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(headline)
            .setContentText(card.title.take(80))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /**
     * Posts "waiting for your approval" for the execution whose gate just opened. The lock screen
     * shows only generic text; the action name appears once the device is unlocked. Every failure
     * is swallowed: a missing notification must never affect the run itself.
     */
    fun notifyApprovalNeeded(
        context: Context,
        executionId: String,
        actionDisplayName: String,
        appInForeground: Boolean,
    ) {
        if (!ApprovalNotificationPolicy.shouldNotify(appInForeground, executionId)) return
        runCatching { postApproval(context, executionId, actionDisplayName) }
    }

    /** Removes the approval notification once the gate closes (answered, denied or timed out). */
    fun cancelApproval(context: Context, executionId: String) {
        if (executionId.isBlank()) return
        runCatching { NotificationManagerCompat.from(context).cancel(approvalNotificationId(executionId)) }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun postApproval(context: Context, executionId: String, actionDisplayName: String) {
        if (!canPost(context)) return
        ensureApprovalChannel(context)
        // The live run is published under this id; a malformed id only opens the app.
        val runId = com.airi.assistant.ai.agent.trace.LiveTraceProjector.traceIdFor(executionId)
        val link = AgentRunDeepLink.build(runId).takeIf { AgentRunDeepLink.parse(it) == runId }
        val intent = Intent(Intent.ACTION_VIEW, link?.let(Uri::parse), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val id = approvalNotificationId(executionId)
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(R.string.run_approval_notif_title)
        val publicVersion = NotificationCompat.Builder(context, APPROVAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.run_notif_public_text))
            .build()
        val notification = NotificationCompat.Builder(context, APPROVAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(actionDisplayName.take(80))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            // The gate times out on its own; the notification should not outlive it.
            .setTimeoutAfter(APPROVAL_TIMEOUT_MS)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
