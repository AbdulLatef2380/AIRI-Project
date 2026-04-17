package com.airi.assistant.reporting

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

data class BugReportRequest(
    val description: String,
    val stepsToReproduce: String?,
    val currentScreenName: String,
    val lastUserAction: String?
)

class BugReportManager(
    private val context: Context,
    private val deviceInfoProvider: DeviceInfoProvider = DeviceInfoProvider(context),
    private val logCollector: LogCollector = LogCollector()
) {

    suspend fun createEmailIntent(request: BugReportRequest): Result<Intent> {
        val description = request.description.trim()
        if (description.isBlank()) {
            return Result.failure(IllegalArgumentException("Bug description is required"))
        }

        val logs = logCollector.collect()
        if (logs.logcatSnapshot.isBlank() || logs.errorsAndExceptions.isBlank()) {
            return Result.failure(IllegalStateException("Diagnostics logs could not be collected"))
        }

        val body = buildEmailBody(request, logs)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${TARGET_EMAILS.joinToString(",")}")
            putExtra(Intent.EXTRA_EMAIL, TARGET_EMAILS)
            putExtra(Intent.EXTRA_SUBJECT, "[AIRI BUG REPORT] - ${Build.MODEL}")
            putExtra(Intent.EXTRA_TEXT, body)
        }

        return Result.success(intent)
    }

    private fun buildEmailBody(request: BugReportRequest, logs: CollectedLogs): String {
        return buildString {
            appendLine("---------------------------------------")
            appendLine()
            appendLine("User Description:")
            appendLine(request.description.trim())
            appendLine()
            appendLine("Steps to Reproduce:")
            appendLine(request.stepsToReproduce?.trim()?.takeIf { it.isNotBlank() } ?: "Not provided")
            appendLine()
            appendLine("---------------------------------------")
            appendLine()
            appendLine("Device Info:")
            appendLine(deviceInfoProvider.deviceInfo())
            appendLine("---------------------------------------")
            appendLine()
            appendLine("App Info:")
            appendLine(deviceInfoProvider.appInfo(request.currentScreenName, request.lastUserAction))
            appendLine("---------------------------------------")
            appendLine()
            appendLine("Logs:")
            appendLine("Last errors/exceptions:")
            appendLine(logs.errorsAndExceptions)
            appendLine()
            appendLine("Logcat snapshot:")
            appendLine(logs.logcatSnapshot)
            appendLine()
            appendLine("---------------------------------------")
        }
    }

    companion object {
        private val TARGET_EMAILS = arrayOf(
            "xwenbrr@gmail.com",
            "xwenbrr847@gmail.com"
        )
    }
}