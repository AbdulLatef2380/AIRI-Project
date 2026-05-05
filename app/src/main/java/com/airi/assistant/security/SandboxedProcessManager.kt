package com.airi.assistant.security

import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * SandboxedProcessManager — manages the lifecycle of sandboxed [Process] instances.
 *
 * ── FEATURES ─────────────────────────────────────────────────────────────
 *
 *   PARALLELISM LIMIT  — at most [maxParallel] processes run concurrently;
 *                        excess calls block on a [Semaphore].
 *   FORCED KILL        — all processes are force-killed on timeout or cancellation.
 *   OUTPUT TRUNCATION  — stdout/stderr is capped at [maxOutputBytes].
 *   RESOURCE TRACKING  — active process count exposed for observability.
 *   AUDIT LOG          — every launch/kill emits AIRI_PROOF logcat tags.
 *
 * ── SECURITY ─────────────────────────────────────────────────────────────
 *
 *   SandboxedProcessManager does NOT enforce [CommandAllowlist] — that is
 *   [SecureExecutionPolicy]'s responsibility. This class manages process
 *   lifecycle only; callers must check policy before calling [exec].
 *
 * ── ENVIRONMENT ──────────────────────────────────────────────────────────
 *
 *   By default, the subprocess inherits NO environment variables.
 *   Callers may pass [extraEnv] for controlled injection.
 *
 *   Working directory is set to the application's [workingDir] if provided,
 *   otherwise the process inherits the JVM working directory.
 */
class SandboxedProcessManager(
    private val maxParallel:    Int    = MAX_PARALLEL_DEFAULT,
    private val defaultTimeout: Long   = DEFAULT_TIMEOUT_MS,
    private val maxOutputBytes: Int    = MAX_OUTPUT_BYTES
) {

    private val TAG    = "SandboxedProcessManager"
    private val sem    = Semaphore(maxParallel, true)
    private val active = ConcurrentHashMap<Long, Process>()
    private val pidSeq = AtomicInteger(0)

    val activeCount: Int get() = active.size

    /**
     * Execute [args] as a sandboxed subprocess.
     *
     * Blocks until:
     *   - the process exits normally, OR
     *   - [timeoutMs] elapses (process is force-killed), OR
     *   - the coroutine is cancelled (process is force-killed).
     *
     * @param args        Full command token list. First element is the binary.
     * @param timeoutMs   Per-process timeout (defaults to [defaultTimeout]).
     * @param extraEnv    Additional environment variables (added to clean env).
     * @param workingDir  Optional working directory for the subprocess.
     *
     * @return [ExecResult] containing stdout/stderr, exit code, and timing.
     */
    suspend fun exec(
        args:       List<String>,
        timeoutMs:  Long                = defaultTimeout,
        extraEnv:   Map<String, String> = emptyMap(),
        workingDir: java.io.File?       = null
    ): ExecResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val pid     = pidSeq.incrementAndGet().toLong()
        val cmdTag  = args.joinToString(" ").take(80)

        LoggingService.info(TAG, "AIRI_PROOF SANDBOXED_EXEC pid=$pid cmd='$cmdTag'")

        sem.acquire()
        var process: Process? = null

        try {
            val result = withTimeoutOrNull(timeoutMs) {
                runCatching {
                    val builder = ProcessBuilder(args)
                        .redirectErrorStream(true)
                        .apply {
                            environment().clear()
                            environment().putAll(extraEnv)
                            workingDir?.let { directory(it) }
                        }

                    process = builder.start().also { active[pid] = it }

                    val output = StringBuilder()
                    var truncated = false
                    BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (output.length >= maxOutputBytes) {
                                truncated = true
                                break
                            }
                            output.append(line).append('\n')
                        }
                    }

                    val exitCode = process!!.waitFor()
                    val elapsed  = System.currentTimeMillis() - startMs
                    val text     = output.toString().trimEnd()

                    LoggingService.info(TAG, "AIRI_PROOF SANDBOXED_EXEC_DONE pid=$pid exit=$exitCode elapsed=${elapsed}ms bytes=${text.length} truncated=$truncated")

                    ExecResult(
                        exitCode    = exitCode,
                        output      = if (truncated) "$text\n[output truncated at $maxOutputBytes bytes]" else text,
                        elapsedMs   = elapsed,
                        timedOut    = false,
                        truncated   = truncated,
                        pid         = pid
                    )
                }.getOrElse { e ->
                    val elapsed = System.currentTimeMillis() - startMs
                    Log.e(TAG, "SANDBOXED_EXEC_ERROR pid=$pid msg=${e.message}", e)
                    ExecResult(exitCode = -1, output = "${e.javaClass.simpleName}: ${e.message}",
                        elapsedMs = elapsed, timedOut = false, pid = pid, error = e.message)
                }
            } ?: run {
                // Timeout path
                runCatching { process?.destroyForcibly() }
                val elapsed = System.currentTimeMillis() - startMs
                Log.w(TAG, "AIRI_PROOF SANDBOXED_EXEC_TIMEOUT pid=$pid elapsed=${elapsed}ms limit=${timeoutMs}ms")
                ExecResult(exitCode = -1, output = "Process timed out after ${timeoutMs}ms",
                    elapsedMs = elapsed, timedOut = true, pid = pid)
            }

            return@withContext result
        } finally {
            runCatching { process?.destroyForcibly() }
            active.remove(pid)
            sem.release()
        }
    }

    /** Kill all active subprocesses. Call from onDestroy() / service stop. */
    fun killAll() {
        Log.w(TAG, "AIRI_PROOF SANDBOXED_KILL_ALL count=${active.size}")
        active.values.forEach { runCatching { it.destroyForcibly() } }
        active.clear()
    }

    /** Result of a single sandboxed process execution. */
    data class ExecResult(
        val exitCode:  Int,
        val output:    String,
        val elapsedMs: Long,
        val timedOut:  Boolean  = false,
        val truncated: Boolean  = false,
        val pid:       Long     = -1,
        val error:     String?  = null
    ) {
        val success: Boolean get() = exitCode == 0 && !timedOut && error == null
    }

    companion object {
        private const val MAX_PARALLEL_DEFAULT = 4
        private const val DEFAULT_TIMEOUT_MS   = 15_000L
        private const val MAX_OUTPUT_BYTES     = 64 * 1024
    }
}
