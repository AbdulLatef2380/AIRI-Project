package com.airi.assistant.runtime.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ThermalProfiler — Phase R6 thermal and battery watchdog.
 *
 * ── Monitors ──────────────────────────────────────────────────────────────
 *   THERMAL   — PowerManager.getCurrentThermalStatus() (API 29+)
 *               Falls back to battery temperature on older APIs.
 *   BATTERY   — charge level, current draw, charging state
 *   INFERENCE — per-inference energy estimate (token count × cost model)
 *
 * ── Throttle policy ───────────────────────────────────────────────────────
 *   SEVERE thermal → emit [ThrottleLevel.EMERGENCY] — stop inference
 *   MODERATE       → emit [ThrottleLevel.REDUCE]    — switch to smaller model
 *   COOL           → emit [ThrottleLevel.NONE]       — full performance
 *
 * ── Integration ──────────────────────────────────────────────────────────
 *   Collect [throttleLevel] in LlamaManager / RuntimeSupervisor and apply
 *   the appropriate PerformanceMode. RuntimeSupervisor already does thermal
 *   polling — wire this as the canonical source of truth instead.
 */
class ThermalProfiler(private val context: Context) {

    private val TAG                 = "ThermalProfiler"
    private val POLL_INTERVAL_MS    = 10_000L
    private val BATTERY_TEMP_HOT_C  = 42.0f   // °C — throttle at 42°C
    private val BATTERY_TEMP_CRIT_C = 48.0f   // °C — emergency at 48°C
    private val BATTERY_LOW_PCT     = 15       // warn below 15%

    enum class ThrottleLevel { NONE, REDUCE, EMERGENCY }

    data class ThermalSnapshot(
        val thermalStatus:        Int,      // PowerManager.THERMAL_STATUS_* or -1
        val batteryTempC:         Float,
        val batteryPct:           Int,
        val batteryCharging:      Boolean,
        val estimatedInferenceW:  Float,    // rough watt estimate
        val throttleLevel:        ThrottleLevel,
        val timestampMs:          Long      = System.currentTimeMillis()
    )

    private val _snapshot      = MutableStateFlow<ThermalSnapshot?>(null)
    val snapshot: StateFlow<ThermalSnapshot?> = _snapshot.asStateFlow()

    private val _throttleLevel = MutableStateFlow(ThrottleLevel.NONE)
    val throttleLevel: StateFlow<ThrottleLevel> = _throttleLevel.asStateFlow()

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val scope        = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Inference energy estimation ────────────────────────────────────────
    // Rough model: each token on ARM CPU = ~0.5mJ. At 20 tok/s = 10mW inference share.
    private var totalTokensGenerated = 0L

    fun recordTokensGenerated(count: Int) {
        totalTokensGenerated += count
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            Log.i(TAG, "AIRI_PROOF THERMAL_PROFILER_STARTED")
            while (isActive) {
                poll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private fun poll() {
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            -1
        }

        val batteryIntent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val tempRaw  = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC    = tempRaw / 10f
        val level    = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale    = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct      = if (scale > 0) (level * 100 / scale) else -1
        val status   = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        // Rough energy estimate (tokens * 0.5mJ / poll interval)
        val inferenceW = totalTokensGenerated * 0.0005f / (POLL_INTERVAL_MS / 1000f)
        totalTokensGenerated = 0

        val throttle = when {
            thermalStatus >= 4 /* SEVERE */ -> ThrottleLevel.EMERGENCY  // API 29+
            thermalStatus >= 3 /* MODERATE */-> ThrottleLevel.REDUCE
            tempC >= BATTERY_TEMP_CRIT_C    -> ThrottleLevel.EMERGENCY
            tempC >= BATTERY_TEMP_HOT_C     -> ThrottleLevel.REDUCE
            else                             -> ThrottleLevel.NONE
        }

        if (throttle != _throttleLevel.value) {
            Log.w(TAG, "AIRI_PROOF THROTTLE_CHANGE old=${_throttleLevel.value} " +
                    "new=$throttle thermalStatus=$thermalStatus tempC=$tempC")
            _throttleLevel.value = throttle
        }

        if (pct in 1..BATTERY_LOW_PCT && !charging) {
            Log.w(TAG, "AIRI_PROOF LOW_BATTERY pct=$pct")
        }

        _snapshot.value = ThermalSnapshot(
            thermalStatus       = thermalStatus,
            batteryTempC        = tempC,
            batteryPct          = pct,
            batteryCharging     = charging,
            estimatedInferenceW = inferenceW,
            throttleLevel       = throttle
        )

        Log.d(TAG, "AIRI_PROOF THERMAL_POLL tempC=$tempC pct=$pct " +
                "thermalStatus=$thermalStatus throttle=$throttle inferenceW=$inferenceW")
    }
}
