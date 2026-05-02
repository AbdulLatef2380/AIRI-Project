package com.airi.assistant.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * RuntimeSupervisor — thermal and memory-pressure watchdog.
 *
 * Polls Android's thermal status (API 29+) and available RAM every
 * [POLL_INTERVAL_MS] milliseconds. When sustained device pressure is
 * detected it automatically downgrades the active [PerformanceMode] to
 * protect stability. It NEVER upgrades autonomously — an auto-upgrade would
 * flush the KV cache mid-conversation without user knowledge.
 *
 * Pressure must be confirmed across [CONFIRM_CYCLES] consecutive polls before
 * any mode change is applied. This prevents single-spike false downgrades
 * caused by transient GC or OS scheduling bursts.
 *
 * All decisions emit AIRI_PROOF log tags so they are visible in the standard
 * `adb logcat | grep AIRI_PROOF` audit stream used throughout the project.
 *
 * ── Serialization contract ──────────────────────────────────────────────────
 * [applyRuntimeMode] is called on [LlamaManager.scope] (single-threaded
 * llamaDispatcher). The supervisor runs on [Dispatchers.Default] — a separate
 * pool — so there is NEVER lock contention between the polling loop and any
 * in-flight llama_decode. The llamaDispatcher will simply queue the mode
 * change and apply it between turns.
 *
 * ── Integration ─────────────────────────────────────────────────────────────
 * 1. Construct once in the ViewModel alongside [LlamaManager].
 * 2. Call [start] after a model finishes loading successfully.
 * 3. Call [stop] in ViewModel.onCleared() — idempotent, safe before [start].
 *
 * @param context       Application context for system-service access.
 * @param llamaManager  The active inference manager (receives mode changes).
 * @param modeProvider  Returns the user's explicitly chosen [PerformanceMode].
 *                      Invoked on Dispatchers.Default; must be thread-safe
 *                      (StateFlow.value is — use that).
 * @param modeConsumer  Called after each confirmed supervisor override so the
 *                      ViewModel can update UI state. Receives the new mode and
 *                      a human-readable reason string. Invoked on
 *                      Dispatchers.Default; dispatch to Main as needed.
 */
class RuntimeSupervisor(
    private val context: Context,
    private val llamaManager: LlamaManager,
    private val modeProvider: () -> PerformanceMode,
    private val modeConsumer: (PerformanceMode, String) -> Unit
) {

    private val supervisorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var pollJob: Job? = null

    // The last mode that this supervisor applied (null = not yet applied any).
    // Used to detect changes and to make [stop] idempotent.
    @Volatile private var lastAppliedMode: PerformanceMode? = null

    companion object {
        private const val TAG = "AIRI_SUPERVISOR"

        // How often to sample device state.
        private const val POLL_INTERVAL_MS = 15_000L

        // Number of consecutive polls that must agree before applying a change.
        // At 15s intervals this means ~30s of sustained pressure → action.
        private const val CONFIRM_CYCLES = 2

        // Available-RAM thresholds (MB).
        private const val MEM_CRITICAL_MB = 300  // → force FAST
        private const val MEM_LOW_MB      = 600  // → cap at BALANCED

        // PowerManager.THERMAL_STATUS_* integer values (API 29+).
        // Kept as literals so the file compiles on all SDK versions.
        private const val THERMAL_MODERATE = 2
        private const val THERMAL_SEVERE   = 3

        // Ordinal rank used by worstOf(): lower rank = more restrictive.
        private val MODE_RANK = mapOf(
            PerformanceMode.FAST     to 0,
            PerformanceMode.BALANCED to 1,
            PerformanceMode.QUALITY  to 2
        )
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start the polling loop. Idempotent — calling while already running is a
     * no-op. Safe to call from any thread.
     */
    fun start() {
        if (pollJob?.isActive == true) {
            Log.d(TAG, "SUPERVISOR_START_SKIPPED reason=already_running")
            return
        }
        Log.i("AIRI_PROOF",
            "SUPERVISOR_START poll_interval_ms=$POLL_INTERVAL_MS " +
            "confirm_cycles=$CONFIRM_CYCLES " +
            "mem_critical_mb=$MEM_CRITICAL_MB mem_low_mb=$MEM_LOW_MB")

        var pressureCycles = 0
        var pendingMode: PerformanceMode? = null

        pollJob = supervisorScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                try {
                    val recommended = computeRecommendedMode()
                    val userMode    = modeProvider()

                    // The effective target is the MORE restrictive of what the
                    // supervisor recommends and what the user chose. We never
                    // give the device MORE resources than the user asked for.
                    val target = worstOf(recommended, userMode)

                    if (target == lastAppliedMode) {
                        // No change needed. If we had been building confirmation,
                        // the pressure has resolved — reset the counter.
                        if (pendingMode != null) {
                            Log.i("AIRI_PROOF",
                                "SUPERVISOR_PRESSURE_RESOLVED " +
                                "was_pending=${pendingMode?.name} " +
                                "current_effective=${lastAppliedMode?.name}")
                            pendingMode = null
                            pressureCycles = 0
                        }
                        continue
                    }

                    // A different (more restrictive) mode is warranted.
                    if (target == pendingMode) {
                        // Same pressure level as last poll — accumulate.
                        pressureCycles++
                        Log.i("AIRI_PROOF",
                            "SUPERVISOR_PRESSURE_CONFIRM " +
                            "cycle=$pressureCycles/$CONFIRM_CYCLES " +
                            "pending=${target.name} " +
                            "user=${userMode.name} " +
                            "effective=${lastAppliedMode?.name ?: "none"}")
                    } else {
                        // New pressure level observed (or first observation).
                        pressureCycles = 1
                        pendingMode = target
                        Log.i("AIRI_PROOF",
                            "SUPERVISOR_PRESSURE_DETECTED " +
                            "mode=${target.name} " +
                            "user=${userMode.name} " +
                            "effective=${lastAppliedMode?.name ?: "none"}")
                    }

                    if (pressureCycles >= CONFIRM_CYCLES) {
                        val reason = buildReason()
                        Log.i("AIRI_PROOF",
                            "SUPERVISOR_MODE_CHANGE " +
                            "from=${lastAppliedMode?.name ?: "none"} " +
                            "to=${target.name} " +
                            "reason=$reason " +
                            "confirmed_cycles=$pressureCycles")

                        lastAppliedMode = target
                        pressureCycles  = 0
                        pendingMode     = null

                        // applyRuntimeMode is queued on the single-threaded
                        // llamaDispatcher — it will execute between turns,
                        // never racing an in-flight llama_decode.
                        llamaManager.applyRuntimeMode(target)
                        modeConsumer(target, reason)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "SUPERVISOR_POLL_ERROR " +
                        "${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    /**
     * Stop the polling loop and reset state. Idempotent — safe to call before
     * [start] or multiple times. Safe to call from any thread.
     */
    fun stop() {
        val wasActive = pollJob?.isActive == true
        pollJob?.cancel()
        pollJob = null
        lastAppliedMode = null
        Log.i("AIRI_PROOF", "SUPERVISOR_STOP was_active=$wasActive")
    }

    // ── Sampling helpers ──────────────────────────────────────────────────────

    /**
     * Snapshot current thermal and memory state and return the most
     * conservative [PerformanceMode] that keeps the device stable.
     */
    private fun computeRecommendedMode(): PerformanceMode {
        val thermalMode = readThermalMode()
        val memMode     = readMemoryMode()
        val result      = worstOf(thermalMode, memMode)
        Log.d(TAG,
            "SUPERVISOR_SAMPLE " +
            "thermal=${thermalMode.name} " +
            "mem=${memMode.name} " +
            "result=${result.name}")
        return result
    }

    /**
     * Read Android thermal status (API 29+). Returns [PerformanceMode.QUALITY]
     * (no restriction) on older API levels or on any read error so the
     * supervisor never penalises devices it cannot measure.
     */
    private fun readThermalMode(): PerformanceMode {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return PerformanceMode.QUALITY
        }
        return try {
            val pm     = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val status = pm.currentThermalStatus
            Log.i("AIRI_PROOF", "SUPERVISOR_THERMAL status=$status")
            when {
                status >= THERMAL_SEVERE   -> PerformanceMode.FAST
                status >= THERMAL_MODERATE -> PerformanceMode.BALANCED
                else                       -> PerformanceMode.QUALITY
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SUPERVISOR_THERMAL_READ_ERROR: ${t.message}")
            PerformanceMode.QUALITY
        }
    }

    /**
     * Read available RAM via [ActivityManager.MemoryInfo]. Returns
     * [PerformanceMode.QUALITY] on any read error so the supervisor never
     * penalises devices it cannot measure.
     */
    private fun readMemoryMode(): PerformanceMode {
        return try {
            val am   = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val availMb = (info.availMem / (1024L * 1024L)).toInt()
            val lowMem  = info.lowMemory
            Log.i("AIRI_PROOF",
                "SUPERVISOR_MEMORY avail_mb=$availMb low_mem=$lowMem")
            when {
                lowMem || availMb < MEM_CRITICAL_MB -> PerformanceMode.FAST
                availMb < MEM_LOW_MB                -> PerformanceMode.BALANCED
                else                                -> PerformanceMode.QUALITY
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SUPERVISOR_MEMORY_READ_ERROR: ${t.message}")
            PerformanceMode.QUALITY
        }
    }

    /**
     * Build a concise reason string for the current poll cycle that surfaces
     * in AIRI_PROOF logs and in the consumer callback. Re-samples the
     * subsystems so the reason reflects the confirmed state, not the initial
     * detection state.
     */
    private fun buildReason(): String {
        val thermalMode = readThermalMode()
        val memMode     = readMemoryMode()
        return buildString {
            if (thermalMode != PerformanceMode.QUALITY) append("thermal=${thermalMode.name} ")
            if (memMode     != PerformanceMode.QUALITY) append("memory=${memMode.name} ")
        }.trim().ifEmpty { "ok" }
    }

    /**
     * Returns the more restrictive of two [PerformanceMode]s.
     * Ranking (ascending restriction): QUALITY > BALANCED > FAST.
     */
    private fun worstOf(a: PerformanceMode, b: PerformanceMode): PerformanceMode =
        if ((MODE_RANK[a] ?: 1) <= (MODE_RANK[b] ?: 1)) a else b
}
