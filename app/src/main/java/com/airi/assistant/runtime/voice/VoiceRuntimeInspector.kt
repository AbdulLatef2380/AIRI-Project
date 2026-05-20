package com.airi.assistant.runtime.voice

import android.content.Context
import android.media.AudioManager
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * VoiceRuntimeInspector — Phase R5 voice subsystem validator.
 *
 * Monitors the full voice pipeline lifecycle:
 *   - Wake-word (Porcupine) engine liveness
 *   - Full-duplex state machine correctness (DuplexConversationRuntime)
 *   - AudioFocus ownership (no stuck focus, no phantom focus)
 *   - VAD stability (FullDuplexVadEngine heartbeat)
 *   - Microphone ownership (AudioRecord open/close parity)
 *   - TTS interruption correctness (no dangling AudioTrack)
 *
 * ── Integration ──────────────────────────────────────────────────────────
 * Call the record* methods from the voice subsystem lifecycle hooks:
 *
 *   LiveVoiceSession.onStart()   → VoiceRuntimeInspector.recordSessionStart()
 *   LiveVoiceSession.onStop()    → VoiceRuntimeInspector.recordSessionStop()
 *   AudioFocus granted callback  → VoiceRuntimeInspector.recordFocusGained()
 *   AudioFocus loss callback     → VoiceRuntimeInspector.recordFocusLost()
 *   AudioRecord.startRecording() → VoiceRuntimeInspector.recordMicOpen()
 *   AudioRecord.stop()           → VoiceRuntimeInspector.recordMicClose()
 *   DuplexConversationRuntime state changes → recordDuplexState()
 */
class VoiceRuntimeInspector(private val context: Context) {

    private val TAG                   = "VoiceRuntimeInspector"
    private val AUDIT_INTERVAL_MS     = 15_000L
    private val STUCK_FOCUS_WARN_MS   = 30_000L    // focus held > 30s with no voice = suspect
    private val MIC_LEAK_WARN_COUNT   = 2          // >2 open AudioRecords = leak

    data class VoiceHealthSnapshot(
        val sessionActive:        Boolean,
        val micOpenCount:         Int,
        val focusHeld:            Boolean,
        val focusAgeMs:           Long,
        val stuckFocusWarning:    Boolean,
        val micLeakWarning:       Boolean,
        val duplexState:          String,
        val bargeInCount:         Int,
        val vadFrameRate:         Float,   // frames/sec — drops signal silent VAD
        val interruptionErrors:   Int,
        val ttsHangWarning:       Boolean,
        val healthy:              Boolean
    )

    private val _health = MutableStateFlow<VoiceHealthSnapshot?>(null)
    val health: StateFlow<VoiceHealthSnapshot?> = _health.asStateFlow()

    // ── State atoms ────────────────────────────────────────────────────────
    private val sessionActive    = AtomicBoolean(false)
    private val micOpenCount     = AtomicInteger(0)
    private val focusHeld        = AtomicBoolean(false)
    private val focusGainedAtMs  = AtomicLong(0)
    private val bargeInCount     = AtomicInteger(0)
    private val vadFrameCount    = AtomicLong(0)
    private val vadWindowStartMs = AtomicLong(System.currentTimeMillis())
    private val interruptErrors  = AtomicInteger(0)
    private val ttsHangCount     = AtomicInteger(0)

    @Volatile private var duplexState = "IDLE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            Log.i(TAG, "AIRI_PROOF VOICE_INSPECTOR_STARTED")
            while (isActive) {
                delay(AUDIT_INTERVAL_MS)
                audit()
            }
        }
    }

    // ── Record hooks (call from voice subsystem) ───────────────────────────

    fun recordSessionStart() {
        sessionActive.set(true)
        Log.i(TAG, "AIRI_PROOF VOICE_SESSION_START")
    }

    fun recordSessionStop() {
        sessionActive.set(false)
        Log.i(TAG, "AIRI_PROOF VOICE_SESSION_STOP micOpen=${micOpenCount.get()} focusHeld=${focusHeld.get()}")
        if (micOpenCount.get() > 0) {
            Log.e(TAG, "AIRI_PROOF MIC_NOT_RELEASED after session stop. count=${micOpenCount.get()}")
        }
        if (focusHeld.get()) {
            Log.e(TAG, "AIRI_PROOF AUDIO_FOCUS_NOT_RELEASED after session stop")
        }
    }

    fun recordFocusGained() {
        focusHeld.set(true)
        focusGainedAtMs.set(System.currentTimeMillis())
        Log.d(TAG, "AIRI_PROOF AUDIO_FOCUS_GAINED")
    }

    fun recordFocusLost(transient: Boolean) {
        focusHeld.set(false)
        Log.d(TAG, "AIRI_PROOF AUDIO_FOCUS_LOST transient=$transient")
    }

    fun recordMicOpen() {
        val count = micOpenCount.incrementAndGet()
        Log.d(TAG, "AIRI_PROOF MIC_OPEN total=$count")
        if (count > MIC_LEAK_WARN_COUNT) {
            Log.e(TAG, "AIRI_PROOF MIC_LEAK count=$count — AudioRecord not released")
        }
    }

    fun recordMicClose() {
        val count = micOpenCount.decrementAndGet()
        Log.d(TAG, "AIRI_PROOF MIC_CLOSE remaining=$count")
        if (count < 0) {
            Log.e(TAG, "AIRI_PROOF MIC_CLOSE_MISMATCH count=$count — over-released")
        }
    }

    fun recordDuplexState(state: String) {
        duplexState = state
    }

    fun recordBargeIn() {
        bargeInCount.incrementAndGet()
        Log.d(TAG, "AIRI_PROOF BARGE_IN total=${bargeInCount.get()}")
    }

    fun recordVadFrame() {
        vadFrameCount.incrementAndGet()
    }

    fun recordInterruptionError() {
        interruptErrors.incrementAndGet()
        Log.w(TAG, "AIRI_PROOF INTERRUPTION_ERROR total=${interruptErrors.get()}")
    }

    fun recordTtsHang() {
        ttsHangCount.incrementAndGet()
        Log.e(TAG, "AIRI_PROOF TTS_HANG total=${ttsHangCount.get()}")
    }

    // ── Audit ──────────────────────────────────────────────────────────────

    private fun audit() {
        val now        = System.currentTimeMillis()
        val focusAge   = if (focusHeld.get()) now - focusGainedAtMs.get() else 0L
        val stuckFocus = focusHeld.get() && focusAge > STUCK_FOCUS_WARN_MS && !sessionActive.get()

        val windowMs   = (now - vadWindowStartMs.get()).coerceAtLeast(1)
        val vadRate    = vadFrameCount.getAndSet(0).toFloat() / (windowMs / 1000f)
        vadWindowStartMs.set(now)

        val micLeak    = micOpenCount.get() > MIC_LEAK_WARN_COUNT
        val ttsHang    = ttsHangCount.get() > 0

        val healthy = !stuckFocus && !micLeak && interruptErrors.get() < 3 && !ttsHang

        val snap = VoiceHealthSnapshot(
            sessionActive      = sessionActive.get(),
            micOpenCount       = micOpenCount.get(),
            focusHeld          = focusHeld.get(),
            focusAgeMs         = focusAge,
            stuckFocusWarning  = stuckFocus,
            micLeakWarning     = micLeak,
            duplexState        = duplexState,
            bargeInCount       = bargeInCount.get(),
            vadFrameRate       = vadRate,
            interruptionErrors = interruptErrors.get(),
            ttsHangWarning     = ttsHang,
            healthy            = healthy
        )
        _health.value = snap

        if (stuckFocus) Log.e(TAG, "AIRI_PROOF STUCK_AUDIO_FOCUS ageMs=$focusAge")
        if (micLeak)    Log.e(TAG, "AIRI_PROOF MIC_LEAK_CONFIRMED count=${micOpenCount.get()}")
        if (vadRate < 5f && sessionActive.get()) {
            Log.w(TAG, "AIRI_PROOF VAD_LOW_FRAMERATE rate=$vadRate fps — VAD may be stalled")
        }

        Log.i(TAG, "AIRI_PROOF VOICE_AUDIT healthy=$healthy duplexState=$duplexState " +
                "mic=${micOpenCount.get()} focus=${focusHeld.get()} vadRate=$vadRate")
    }
}
