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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

        val workspace = runCatching {
            File(cacheDir, "terminal-$requestId").apply {
                deleteRecursively()
                check(mkdirs()) { "isolated workspace unavailable" }
            }
        }.getOrElse { return ServiceResult.failed("Could not allocate isolated workspace") }
        val process = try {
            ProcessBuilder(argv)
                .directory(workspace)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["PATH"] = "/system/bin:/system/xbin"
                    environment()["LANG"] = "C"
                }
                .start()
        } catch (e: Exception) {
            workspace.deleteRecursively()
            return ServiceResult.failed("Process start failed: ${e.javaClass.simpleName}")
        }
        processes[requestId] = process
        return try {
            val result = withTimeoutOrNull(limits.timeoutMs) {
                collectOutput(process, limits)
            } ?: ServiceResult.timeout()
            result
        } catch (_: CancellationException) {
            throw CancellationException("isolated terminal request cancelled")
        } finally {
            ProcessTreeTerminator.terminate(process)
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            processes.remove(requestId)
            workspace.deleteRecursively()
        }
    }

    private suspend fun collectOutput(process: Process, limits: IsolatedProcessLimits): ServiceResult {
        runCatching { process.outputStream.close() }
        val output = ByteArrayOutputStream()
        process.inputStream.use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                currentCoroutineContext().ensureActive()
                val pid = childPid(process)
                val childMemory = pid?.let(::processResidentBytes)
                if (childMemory != null && childMemory > limits.memoryBytes) {
                    ProcessTreeTerminator.terminate(process)
                    return ServiceResult.memoryExceeded()
                }
                if (pid != null && childCpuTimeMs(pid) > limits.cpuTimeMs) {
                    ProcessTreeTerminator.terminate(process)
                    return ServiceResult.cpuExceeded()
                }
                if (!process.isAlive) break
                val available = input.available()
                if (available == 0) {
                    delay(10L)
                    continue
                }
                val read = input.read(buffer, 0, available.coerceAtMost(buffer.size))
                if (read <= 0) continue
                val remaining = limits.maxOutputBytes - output.size()
                if (read > remaining) {
                    if (remaining > 0) output.write(buffer, 0, remaining.toInt())
                    ProcessTreeTerminator.terminate(process)
                    return ServiceResult.outputExceeded(String(output.toByteArray(), Charsets.UTF_8))
                }
                output.write(buffer, 0, read)
            }
        }
        process.waitFor(1, TimeUnit.SECONDS)
        val text = String(output.toByteArray(), Charsets.UTF_8)
        val exit = if (process.isAlive) -1 else process.exitValue()
        return if (exit == 0) ServiceResult.success(text, exit) else ServiceResult.failed(text, exit)
    }

    private fun childPid(process: Process): Int? = runCatching {
        Process::class.java.getMethod("pid").invoke(process) as Long
    }.getOrNull()?.toInt()?.takeIf { it > 0 }

    /** Linux proc reports utime/stime in ticks; Android devices conventionally use 100Hz. */
    private fun childCpuTimeMs(pid: Int): Long = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val afterComm = stat.substringAfterLast(") ").trim()
        val fields = afterComm.split(Regex("\\s+"))
        val userTicks = fields.getOrNull(11)?.toLongOrNull() ?: return@runCatching 0L
        val systemTicks = fields.getOrNull(12)?.toLongOrNull() ?: return@runCatching 0L
        ((userTicks + systemTicks) * 1_000L) / 100L
    }.getOrDefault(0L)

    private fun processResidentBytes(pid: Int): Long? = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.times(1024L)
        }
    }.getOrNull()

    private fun childPidSupportAvailable(): Boolean = runCatching {
        Process::class.java.getMethod("pid")
        true
    }.getOrDefault(false)

    private fun resourceWatchdogAvailable(): Boolean {
        if (!childPidSupportAvailable()) return false
        val probe = runCatching {
            ProcessBuilder("echo", "watchdog-probe")
                .directory(cacheDir)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["PATH"] = "/system/bin:/system/xbin"
                }
                .start()
        }.getOrNull() ?: return false
        return try {
            childPid(probe) != null
        } finally {
            ProcessTreeTerminator.terminate(probe)
        }
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
