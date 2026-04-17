package com.airi.assistant.reporting

import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class CollectedLogs(
    val logcatSnapshot: String,
    val errorsAndExceptions: String
)

class LogCollector {

    suspend fun collect(): CollectedLogs = withContext(Dispatchers.IO) {
        val snapshot = runCatching { readLogcat() }.getOrElse { error ->
            "Logcat collection failed: ${error.localizedMessage ?: error.javaClass.simpleName}"
        }.trim().ifBlank {
            "No app logcat entries returned by system logcat."
        }

        val errors = snapshot
            .lineSequence()
            .filter { line ->
                val lower = line.lowercase()
                lower.contains("exception") ||
                    lower.contains("error") ||
                    lower.contains("fatal") ||
                    lower.contains("androidruntime") ||
                    line.contains(" E ") ||
                    line.contains(" E/")
            }
            .toList()
            .takeLast(80)
            .joinToString("\n")
            .ifBlank { "No errors/exceptions found in app logcat snapshot." }

        CollectedLogs(
            logcatSnapshot = snapshot.lineSequence().toList().takeLast(220).joinToString("\n"),
            errorsAndExceptions = errors
        )
    }

    private fun readLogcat(): String {
        val process = Runtime.getRuntime().exec(
            arrayOf(
                "logcat",
                "-d",
                "--pid",
                Process.myPid().toString(),
                "-t",
                "300",
                "*:V"
            )
        )

        val completed = process.waitFor(3, TimeUnit.SECONDS)
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val error = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }

        if (!completed) {
            process.destroyForcibly()
        }

        return buildString {
            if (output.isNotBlank()) append(output.trim())
            if (error.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append("logcat stderr: ${error.trim()}")
            }
        }
    }
}