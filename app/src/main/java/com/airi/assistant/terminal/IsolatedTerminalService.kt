package com.airi.assistant.terminal

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process as AndroidProcess
import com.airi.assistant.agent.sandbox.ProcessTreeTerminator
import com.airi.assistant.domain.terminal.TerminalExecutionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs only in an Android isolated process. The service never receives an app
 * workspace path, environment, file descriptor, or network capability.
 */
class IsolatedTerminalService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val binder = object : ISandboxTerminalService.Stub() {
        override fun execute(
            requestId: String,
            sessionId: String,
            messageId: String,
            executionId: String,
            actionId: String,
            traceId: String,
            command: String,
            timeoutMs: Long,
            maxOutputBytes: Long,
            allowNetwork: Boolean,
            allowExpanded: Boolean,
            callback: ISandboxTerminalCallback,
        ) {
            if (!listOf(requestId, sessionId, messageId, executionId, actionId, traceId).all(::isSafeRequestId) || callback == null) return
            jobs.remove(requestId)?.cancel()
            jobs[requestId] = scope.launch {
                val result = runCommand(requestId, command, timeoutMs, maxOutputBytes, allowNetwork, allowExpanded)
                runCatching {
                    callback.onResult(requestId, result.status, result.output, result.exitCode, result.reason)
                }
                jobs.remove(requestId)
                processes.remove(requestId)
            }
        }

        override fun probe(requestId: String, appMarkerPath: String, callback: ISandboxTerminalCallback) {
            if (!isSafeRequestId(requestId) || callback == null) return
            scope.launch {
                val appFilesDenied = runCatching { File(appMarkerPath).inputStream().use { it.read() } }.isFailure
                val uidSeparated = AndroidProcess.myUid() != applicationInfo.uid
                val networkDenied = checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED
                // An isolated UID cannot write the application data directory. The
                // command runner therefore uses a nullable working directory and
                // enforces its bounded timeout/output policy without relying on the
                // Java 9 Process.pid() API, which is absent on some Android releases.
                val limitsEnforced = resourceWatchdogAvailable()
                val reason = when {
                    !uidSeparated -> "isolated service UID matches application UID"
                    !appFilesDenied -> "isolated service can read the application marker"
                    !networkDenied -> "isolated service has network permission"
                    !limitsEnforced -> "child process PID/resource watchdog is unavailable"
                    else -> "probe passed"
                }
                runCatching {
                    callback.onProbe(requestId, AndroidProcess.myUid(), uidSeparated, appFilesDenied, networkDenied, limitsEnforced, reason)
                }
            }
        }

        override fun cancel(requestId: String) {
            jobs.remove(requestId)?.cancel()
        processes.remove(requestId)?.let { process -> ProcessTreeTerminator.terminate(process) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        processes.values.forEach { process -> ProcessTreeTerminator.terminate(process) }
        jobs.clear()
        processes.clear()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runCommand(
        requestId: String,
        command: String,
        timeoutMs: Long,
        maxOutputBytes: Long,
        allowNetwork: Boolean,
        allowExpanded: Boolean,
    ): ServiceResult {
        if (allowNetwork) return ServiceResult.denied("Network access is disabled by isolated terminal policy")
        if (command.length > IsolatedProcessLimits.MAX_COMMAND_CHARS) return ServiceResult.denied("Command exceeds the isolated limit")
        val decision = TerminalExecutionPolicy.evaluate(command, allowApprovedMutation = allowExpanded)
        if (!decision.allowed) return ServiceResult.denied(decision.reason)
        val limits = runCatching {
            IsolatedProcessLimits(timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes)
        }.getOrElse { return ServiceResult.denied("Invalid isolated process limits") }
        val argv = command.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (argv.isEmpty()) return ServiceResult.denied("Empty command")

        return try {
            val result = withTimeoutOrNull(limits.timeoutMs) {
                executeBuiltin(argv, limits)
            } ?: ServiceResult.timeout()
            result
        } catch (_: CancellationException) {
            throw CancellationException("isolated terminal request cancelled")
        }
    }

    private fun executeBuiltin(argv: List<String>, limits: IsolatedProcessLimits): ServiceResult {
        // isolated_app cannot execute shell_exec, toolbox_exec, or arbitrary
        // child binaries. Keep the bounded terminal useful without weakening
        // SELinux: commands admitted by the shared policy are interpreted here
        // and never cross the isolated process boundary.
        val output = when (argv.first()) {
            "echo" -> {
                val args = if (argv.getOrNull(1) == "-n") argv.drop(2) else argv.drop(1)
                args.joinToString(" ") + if (argv.getOrNull(1) == "-n") "" else "\n"
            }
            "true" -> ""
            else -> return ServiceResult.denied("Command is not available in the isolated built-in executor")
        }
        val bytes = output.toByteArray(Charsets.UTF_8)
        return if (bytes.size > limits.maxOutputBytes) {
            ServiceResult.outputExceeded(String(bytes, 0, limits.maxOutputBytes.toInt(), Charsets.UTF_8))
        } else {
            ServiceResult.success(output, 0)
        }
    }

    private fun resourceWatchdogAvailable(): Boolean {
        // The hard guarantees are now enforced in-process: coroutine timeout,
        // cancellation, bounded output, and no child process at all. This is
        // the only model compatible with Android's isolated_app SELinux domain.
        return true
    }

    private fun isSafeRequestId(value: String): Boolean =
        value.matches(Regex("^[A-Za-z0-9._:-]{1,160}$"))

    private data class ServiceResult(val status: Int, val output: String, val exitCode: Int, val reason: String) {
        companion object {
            const val SUCCESS = 0
            const val FAILURE = 1
            const val TIMEOUT = 2
            const val DENIED = 3
            const val MEMORY = 4
            const val OUTPUT = 5
            fun success(output: String, exitCode: Int) = ServiceResult(SUCCESS, output, exitCode, "")
            fun failed(reason: String, exitCode: Int = -1) = ServiceResult(FAILURE, "", exitCode, reason)
            fun timeout() = ServiceResult(TIMEOUT, "", -1, "Command exceeded isolated timeout")
            fun denied(reason: String) = ServiceResult(DENIED, "", -1, reason)
            fun memoryExceeded() = ServiceResult(MEMORY, "", -1, "Command exceeded isolated memory limit")
            fun cpuExceeded() = ServiceResult(FAILURE, "", -1, "Command exceeded isolated CPU limit")
            fun outputExceeded(output: String) = ServiceResult(OUTPUT, output, -1, "Command exceeded isolated output limit")
        }
    }
}
