package com.airi.assistant.agent

import android.util.Log
import com.airi.assistant.agent.execution.command.CommandResult
import com.airi.assistant.agent.planning.RecoveryBranch

/**
 * ObservationEngine — autonomous agent observation layer.
 *
 * Sits between EXECUTING and the next step decision in the Manus/ReAct loop:
 *
 *   EXECUTING
 *      │
 *      ▼
 *   ObservationEngine.observe()
 *      │
 *      ├─ SUCCESS   → COMPLETED or next step EXECUTING
 *      ├─ SOFT_FAIL → FIXING (retryable, RecoveryBranch.Retry)
 *      ├─ HARD_FAIL → FAILED  (RecoveryBranch.Abort)
 *      └─ FALLBACK  → FIXING  (RecoveryBranch.Fallback)
 *
 * ## What it validates:
 *  1. Structural success — did CommandRouter / ConnectorActionBridge return success=true?
 *  2. Output quality — is the output non-empty when the step required content?
 *  3. Error classification — transient (network timeout, I/O) vs permanent (not-found, permission)
 *  4. Retry budget — has this step exceeded [maxRetries]?
 *
 * ## Integration points:
 *  - [com.airi.assistant.core.UnifiedCognitiveLoop.executeGraph] calls [observe] after
 *    every CommandRouter dispatch to determine whether to advance the DAG or invoke recovery.
 *  - [com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator.executeSingle]
 *    calls [observe] after each OrchestratorTask completes.
 *  - [com.airi.assistant.agent.planning.ReActPlanner] injects tool results as OBSERVATION
 *    tokens via [summariseForChain] before the next THINK step.
 *
 * ## AIRI_PROOF events emitted:
 *  - `OBSERVE_OK`        — step succeeded, proceeding
 *  - `OBSERVE_SOFT_FAIL` — transient failure, will retry
 *  - `OBSERVE_HARD_FAIL` — permanent failure, aborting step
 *  - `OBSERVE_EMPTY`     — output empty where content required
 *  - `OBSERVE_FALLBACK`  — redirecting to fallback action
 */
object ObservationEngine {

    private const val TAG = "ObservationEngine"

    /** Maximum consecutive retries for a single step before hard-failing. */
    const val DEFAULT_MAX_RETRIES = 3

    /**
     * Observation verdict returned to the execution loop.
     *
     * @param proceed       true → advance DAG to next step; false → apply recovery
     * @param recovery      which [RecoveryBranch] to apply when proceed=false
     * @param summary       human-readable 1-line summary for the UI / AIRI_PROOF
     * @param lifecycleState next [AgentLifecycleState] the loop should transition to
     */
    data class Verdict(
        val proceed:        Boolean,
        val recovery:       RecoveryBranch = RecoveryBranch.Skip,
        val summary:        String         = "",
        val lifecycleState: AgentLifecycleState = AgentLifecycleState.EXECUTING,
    )

    // ── Transient error keywords (network / I-O; worth retrying) ───────────────
    private val TRANSIENT_PATTERNS = listOf(
        "timeout", "timed out", "connection refused", "host unreachable",
        "network error", "econnreset", "socket", "temporarily unavailable",
        "rate limit", "429", "503", "502", "504",
        "io_error", "stream reset", "broken pipe",
    )

    // ── Permanent error keywords (logic / permission; do not retry) ────────────
    private val PERMANENT_PATTERNS = listOf(
        "not found", "no such file", "permission denied", "security_violation",
        "forbidden", "401", "403", "404", "not installed", "not supported",
        "illegal argument", "npe", "nullpointerexception",
    )

    /**
     * Evaluate a [CommandResult] produced by CommandRouter and decide what happens next.
     *
     * @param stepId      Identifier of the plan step (for logging).
     * @param action      Action name that was executed (e.g. "read_file").
     * @param result      The [CommandResult] from CommandRouter / ConnectorActionBridge.
     * @param retryCount  How many times this step has already been retried.
     * @param maxRetries  Budget before escalating to hard-fail.
     * @param requiresContent  If true, an empty output is treated as OBSERVE_EMPTY (soft fail).
     */
    fun observe(
        stepId:          String,
        action:          String,
        result:          CommandResult,
        retryCount:      Int     = 0,
        maxRetries:      Int     = DEFAULT_MAX_RETRIES,
        requiresContent: Boolean = false,
    ): Verdict {
        val prefix = "step=$stepId action=$action retry=$retryCount"

        // ── Case 1: explicit success ──────────────────────────────────────────
        if (result.success) {
            if (requiresContent && result.output.isBlank()) {
                Log.w("AIRI_PROOF", "OBSERVE_EMPTY $prefix output is blank despite success=true")
                return if (retryCount < maxRetries) {
                    Verdict(
                        proceed        = false,
                        recovery       = RecoveryBranch.Retry(maxRetries),
                        summary        = "Empty output from $action — retrying",
                        lifecycleState = AgentLifecycleState.FIXING,
                    )
                } else {
                    Verdict(
                        proceed        = false,
                        recovery       = RecoveryBranch.Skip,
                        summary        = "Empty output from $action after $retryCount retries — skipping",
                        lifecycleState = AgentLifecycleState.FIXING,
                    )
                }
            }
            Log.i("AIRI_PROOF", "OBSERVE_OK $prefix outputLen=${result.output.length}")
            return Verdict(
                proceed        = true,
                summary        = "OK: $action",
                lifecycleState = AgentLifecycleState.EXECUTING,
            )
        }

        // ── Case 2: failure — classify ────────────────────────────────────────
        val errLower = result.output.lowercase()

        val isPermanent = PERMANENT_PATTERNS.any { errLower.contains(it) }
        val isTransient = !isPermanent && TRANSIENT_PATTERNS.any { errLower.contains(it) }

        return when {
            isPermanent -> {
                Log.w("AIRI_PROOF", "OBSERVE_HARD_FAIL $prefix permanent err='${result.output.take(120)}'")
                Verdict(
                    proceed        = false,
                    recovery       = RecoveryBranch.Abort,
                    summary        = "Permanent failure in $action: ${result.output.take(80)}",
                    lifecycleState = AgentLifecycleState.FAILED,
                )
            }

            isTransient && retryCount < maxRetries -> {
                Log.w("AIRI_PROOF", "OBSERVE_SOFT_FAIL $prefix transient retrying err='${result.output.take(80)}'")
                Verdict(
                    proceed        = false,
                    recovery       = RecoveryBranch.Retry(maxRetries),
                    summary        = "Transient error in $action — retry ${retryCount + 1}/$maxRetries",
                    lifecycleState = AgentLifecycleState.FIXING,
                )
            }

            retryCount < maxRetries -> {
                // Unknown error type but we still have budget — try fallback first
                Log.w("AIRI_PROOF", "OBSERVE_FALLBACK $prefix unknown err='${result.output.take(80)}'")
                Verdict(
                    proceed        = false,
                    recovery       = RecoveryBranch.Retry(maxRetries),
                    summary        = "Error in $action — retry ${retryCount + 1}/$maxRetries",
                    lifecycleState = AgentLifecycleState.FIXING,
                )
            }

            else -> {
                Log.w("AIRI_PROOF", "OBSERVE_HARD_FAIL $prefix retries_exhausted err='${result.output.take(80)}'")
                Verdict(
                    proceed        = false,
                    recovery       = RecoveryBranch.Skip,
                    summary        = "Giving up on $action after $retryCount retries",
                    lifecycleState = AgentLifecycleState.FIXING,
                )
            }
        }
    }

    /**
     * Produce a summarised observation token for injection into the ReAct
     * chain-of-thought. Keeps output compact so it does not blow the context window.
     *
     * @param action  The action that ran.
     * @param output  Raw output from the tool / connector.
     * @param success Whether the step succeeded.
     * @param maxLen  Maximum characters to include from the raw output.
     */
    fun summariseForChain(
        action:  String,
        output:  String,
        success: Boolean,
        maxLen:  Int = 400,
    ): String {
        val status  = if (success) "SUCCESS" else "FAILURE"
        val trimmed = output.trim().let { if (it.length > maxLen) it.take(maxLen) + "…" else it }
        return "OBSERVATION[$action/$status]: $trimmed"
    }

    /**
     * Generate a user-facing summarised thought string like Manus does —
     * short, present-tense, never raw model output.
     *
     * Examples:
     *   "read_file" + path=/project/build.gradle.kts → "Reading build.gradle.kts..."
     *   "exec"      + command=./gradlew build         → "Running: ./gradlew build..."
     *   "http_get"  + url=https://api.github.com/...  → "Fetching from api.github.com..."
     */
    fun thinkingLabel(action: String, params: Map<String, String>): String = when (action) {
        "read_file", "file_read", "read_text" -> {
            val file = params["path"]?.substringAfterLast("/") ?: "file"
            "Reading $file..."
        }
        "write_file", "append_file" -> {
            val file = params["path"]?.substringAfterLast("/") ?: "file"
            "Writing $file..."
        }
        "list_dir" -> "Listing directory..."
        "exec", "run_shell", "shell" -> {
            val cmd = params["command"]?.take(40) ?: "command"
            "Running: $cmd..."
        }
        "http_get", "get_url", "fetch_url" -> {
            val host = runCatching {
                java.net.URI(params["url"] ?: "").host ?: "endpoint"
            }.getOrDefault("endpoint")
            "Fetching from $host..."
        }
        "http_post" -> {
            val host = runCatching {
                java.net.URI(params["url"] ?: "").host ?: "endpoint"
            }.getOrDefault("endpoint")
            "Posting to $host..."
        }
        "git_status" -> "Checking git status..."
        "git_log"    -> "Reading git history..."
        "git_diff"   -> "Analyzing git diff..."
        "git_commit" -> "Committing changes..."
        "git_pull"   -> "Pulling latest changes..."
        "logcat_read", "read_logs", "logcat_errors" -> "Reading system logs..."
        "battery_status", "battery" -> "Checking battery..."
        "get_device_info", "device_info" -> "Reading device info..."
        "network_status", "get_wifi" -> "Checking network..."
        "get_clipboard", "set_clipboard" -> "Accessing clipboard..."
        "open_app"   -> "Opening ${params["package"] ?: params["app"] ?: "app"}..."
        "click"      -> "Clicking ${params["text"] ?: params["id"] ?: "element"}..."
        "type"       -> "Typing text..."
        "search"     -> "Searching for ${params["query"]?.take(30) ?: "..."}..."
        "navigate"   -> "Navigating to ${params["url"] ?: params["screen"] ?: "screen"}..."
        "retrieve", "build_context" -> "Searching memory..."
        "speak", "tts" -> "Speaking response..."
        else -> "Executing $action..."
    }
}
