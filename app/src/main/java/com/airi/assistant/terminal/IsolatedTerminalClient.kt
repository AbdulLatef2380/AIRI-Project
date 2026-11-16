package com.airi.assistant.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.airi.assistant.agent.sandbox.OsSandboxCapability
import com.airi.assistant.agent.sandbox.SandboxExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Process-boundary client. It never falls back to the in-process executor. */
class IsolatedTerminalClient(context: Context) {
    private val appContext = context.applicationContext

    suspend fun probe(): OsSandboxCapability = withContext(Dispatchers.Main) {
        val marker = File(appContext.filesDir, ".isolated-terminal-probe-${UUID.randomUUID()}")
        marker.writeText("private-marker")
        try {
            val requestId = safeRequestId("probe-${UUID.randomUUID()}")
            val deferred = CompletableDeferred<OsSandboxCapability>()
            withService { service, unbind ->
                val callback = callbackFor(
                    onProbe = { _, uid, separated, filesDenied, networkDenied, limits, reason ->
                        deferred.complete(
                            OsSandboxCapability(
                                isolatedProcess = separated,
                                uidSeparated = separated && uid != android.os.Process.myUid(),
                                appFilesDenied = filesDenied,
                                networkDenied = networkDenied,
                                limitsEnforced = limits,
                                reason = reason,
                            )
                        )
                    },
                    onResult = { _, _, _, _, _ -> },
                )
                service.probe(requestId, marker.absolutePath, callback)
                val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) { deferred.await() }
                    ?: OsSandboxCapability.unavailable("Isolated terminal probe timed out")
                unbind()
                result
            } ?: OsSandboxCapability.unavailable("Isolated terminal service is unavailable")
        } catch (e: Exception) {
            OsSandboxCapability.unavailable("Isolated terminal probe failed: ${e.javaClass.simpleName}")
        } finally {
            marker.delete()
        }
    }

    suspend fun execute(request: TerminalExecutionRequest, limits: IsolatedProcessLimits = IsolatedProcessLimits()): SandboxExecutor.ExecutionResult =
        withContext(Dispatchers.Main) {
            val requestId = safeRequestId(request.executionId)
            val deferred = CompletableDeferred<IsolatedResult>()
            val serviceResult = withService { service, unbind ->
                val callback = callbackFor(
                    onProbe = { _, _, _, _, _, _, _ -> },
                    onResult = { id, status, output, exitCode, reason ->
                        if (id == requestId) deferred.complete(IsolatedResult(status, output, exitCode, reason))
                    },
                )
                service.execute(
                    requestId,
                    request.context.sourceSessionId,
                    request.messageId,
                    request.executionId,
                    request.actionId,
                    request.traceId,
                    request.command,
                    limits.timeoutMs,
                    limits.maxOutputBytes,
                    false,
                    callback,
                )
                val result = withTimeoutOrNull(limits.timeoutMs + IPC_GRACE_MS) { deferred.await() }
                    ?: IsolatedResult(IsolatedTerminalServiceStatus.TIMEOUT, "", -1, "Isolated terminal IPC timed out")
                if (result.status == IsolatedTerminalServiceStatus.TIMEOUT) service.cancel(requestId)
                unbind()
                result
            } ?: IsolatedResult(IsolatedTerminalServiceStatus.FAILURE, "", -1, "Isolated terminal service is unavailable")
            serviceResult.toExecutionResult()
        }

    private fun callbackFor(
        onProbe: (String, Int, Boolean, Boolean, Boolean, Boolean, String) -> Unit,
        onResult: (String, Int, String, Int, String) -> Unit,
    ): ISandboxTerminalCallback = object : ISandboxTerminalCallback.Stub() {
        override fun onResult(requestId: String, status: Int, output: String?, exitCode: Int, reason: String?) {
            onResult(requestId, status, output.orEmpty(), exitCode, reason.orEmpty())
        }

        override fun onProbe(requestId: String, serviceUid: Int, uidSeparated: Boolean, appFilesDenied: Boolean, networkDenied: Boolean, limitsEnforced: Boolean, reason: String?) {
            onProbe(requestId, serviceUid, uidSeparated, appFilesDenied, networkDenied, limitsEnforced, reason.orEmpty())
        }
    }

    private suspend fun <T> withService(block: suspend (ISandboxTerminalService, () -> Unit) -> T): T? =
        suspendCancellableCoroutine { continuation ->
            var connection: ServiceConnection? = null
            val intent = Intent(appContext, IsolatedTerminalService::class.java)
            connection = object : ServiceConnection {
                private var unbound = false
                private fun unbindOnce() {
                    if (unbound) return
                    unbound = true
                    runCatching { appContext.unbindService(this) }
                }

                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val service = ISandboxTerminalService.Stub.asInterface(binder)
                    val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                        runCatching { block(service, ::unbindOnce) }
                            .onSuccess { value -> if (continuation.isActive) continuation.resume(value) }
                            .onFailure { error -> if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
                    }
                    continuation.invokeOnCancellation { job.cancel(); unbindOnce() }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    unbindOnce()
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onBindingDied(name: ComponentName) {
                    unbindOnce()
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onNullBinding(name: ComponentName) {
                    unbindOnce()
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { connection?.let { runCatching { appContext.unbindService(it) } } }
        }

    private fun safeRequestId(value: String): String =
        value.takeIf { it.matches(Regex("^[A-Za-z0-9._:-]{1,160}$")) } ?: "request-${UUID.randomUUID()}"

    private data class IsolatedResult(val status: Int, val output: String, val exitCode: Int, val reason: String) {
        fun toExecutionResult(): SandboxExecutor.ExecutionResult = when (status) {
            IsolatedTerminalServiceStatus.SUCCESS -> SandboxExecutor.ExecutionResult.Success(output, exitCode)
            IsolatedTerminalServiceStatus.TIMEOUT -> SandboxExecutor.ExecutionResult.Timeout
            IsolatedTerminalServiceStatus.DENIED -> SandboxExecutor.ExecutionResult.SecurityViolation(reason)
            IsolatedTerminalServiceStatus.MEMORY, IsolatedTerminalServiceStatus.OUTPUT -> SandboxExecutor.ExecutionResult.SecurityViolation(reason)
            else -> SandboxExecutor.ExecutionResult.Failure(reason, exitCode)
        }
    }

    private object IsolatedTerminalServiceStatus {
        const val SUCCESS = 0
        const val FAILURE = 1
        const val TIMEOUT = 2
        const val DENIED = 3
        const val MEMORY = 4
        const val OUTPUT = 5
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
        const val IPC_GRACE_MS = 2_000L
    }
}
