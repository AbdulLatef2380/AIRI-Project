package com.airi.assistant.ai.agent.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.airi.assistant.ai.intent.ToolCall
import com.airi.assistant.ai.tools.ToolExecutor
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.core.AiriLogger
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.concurrent.TimeUnit

class AgentWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val WORK_NAME             = "airi_background_agent"
        private const val REPEAT_INTERVAL_HOURS = 2L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AgentWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val prefs         = appContext.getSharedPreferences("airi_ui_state", Context.MODE_PRIVATE)
        val secureStorage = SecureStorage(appContext)
        val toolExecutor  = ToolExecutor(appContext)
        val findings      = mutableListOf<String>()

        AiriLogger.agent("BackgroundAgent", "Worker started")

        return try {
            if (!secureStorage.isGithubConnected() && !secureStorage.isGoogleConnected()) {
                saveSummary(prefs, "No integrations connected.")
                AiriLogger.agent("BackgroundAgent", "No integrations — skipping")
                return Result.success()
            }

            if (secureStorage.isGithubConnected()) {
                runCatching {
                    AiriLogger.agent("BackgroundAgent", "Checking GitHub")
                    val result = toolExecutor.execute(ToolCall("github_get_user", emptyMap()))
                    if (result.success && result.data.isNotBlank()) {
                        findings.add("GitHub: ${result.data.lines().firstOrNull() ?: "Active"}")
                    } else if (!result.success) {
                        AiriLogger.apiFail("github_get_user", result.error ?: "unknown")
                    }
                }.onFailure { e ->
                    AiriLogger.e("BackgroundAgent GitHub check failed: ${e.message}", e)
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }

            if (secureStorage.isGoogleConnected()) {
                runCatching {
                    AiriLogger.agent("BackgroundAgent", "Checking Gmail")
                    val result = toolExecutor.execute(
                        ToolCall("gmail_list_emails", mapOf("max" to "3"))
                    )
                    if (result.success && result.data.isNotBlank()) {
                        findings.add("Gmail: ${result.data.take(100)}")
                    } else if (!result.success) {
                        AiriLogger.apiFail("gmail_list_emails", result.error ?: "unknown")
                    }
                }.onFailure { e ->
                    AiriLogger.e("BackgroundAgent Gmail check failed: ${e.message}", e)
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }

            val summary = if (findings.isEmpty()) "Checked — no updates found."
                          else findings.joinToString(" | ")
            saveSummary(prefs, summary)
            AiriLogger.agent("BackgroundAgent", "Completed: $summary")
            Result.success()

        } catch (e: Exception) {
            AiriLogger.e("BackgroundAgent doWork failed: ${e.message}", e)
            FirebaseCrashlytics.getInstance().recordException(e)
            saveSummary(prefs, "Error: ${e.message?.take(100) ?: "Unknown error"}")
            Result.failure()
        }
    }

    private fun saveSummary(
        prefs: android.content.SharedPreferences,
        summary: String
    ) {
        prefs.edit()
            .putLong("bg_agent_last_run", System.currentTimeMillis())
            .putString("bg_agent_last_result", summary.take(200))
            .apply()
    }
}
