package com.airi.assistant.voice

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory, aggregate-only voice diagnostics. No transcripts, prompts, or identifiers are stored. */
object VoiceHealthTracker {
    data class Snapshot(
        val sessions: Long = 0,
        val completedSessions: Long = 0,
        val errorCount: Long = 0,
        val wakeDetections: Long = 0,
        val averageFirstAudioLatencyMs: Long? = null,
        val lastFirstAudioLatencyMs: Long? = null,
        val lastSessionDurationMs: Long? = null,
        val lastErrorCode: String? = null,
        val lastUpdatedElapsedMs: Long = 0,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()
    private var latencySamples = 0L
    private var latencyTotalMs = 0L
    private var sessionStartedAt = 0L

    @Synchronized
    fun sessionStarted() {
        sessionStartedAt = SystemClock.elapsedRealtime()
        _snapshot.value = _snapshot.value.copy(
            sessions = _snapshot.value.sessions + 1,
            lastUpdatedElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    @Synchronized
    fun wakeDetected() {
        _snapshot.value = _snapshot.value.copy(
            wakeDetections = _snapshot.value.wakeDetections + 1,
            lastUpdatedElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    @Synchronized
    fun firstAudioLatency(latencyMs: Long) {
        val safe = latencyMs.coerceAtLeast(0L)
        latencySamples += 1
        latencyTotalMs += safe
        _snapshot.value = _snapshot.value.copy(
            averageFirstAudioLatencyMs = latencyTotalMs / latencySamples,
            lastFirstAudioLatencyMs = safe,
            lastUpdatedElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    @Synchronized
    fun sessionCompleted() {
        val duration = sessionStartedAt.takeIf { it > 0 }?.let {
            (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
        }
        sessionStartedAt = 0L
        _snapshot.value = _snapshot.value.copy(
            completedSessions = _snapshot.value.completedSessions + 1,
            lastSessionDurationMs = duration,
            lastUpdatedElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    @Synchronized
    fun error(code: String) {
        val safeCode = code.trim().take(48).ifBlank { "unknown" }
        _snapshot.value = _snapshot.value.copy(
            errorCount = _snapshot.value.errorCount + 1,
            lastErrorCode = safeCode,
            lastUpdatedElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    @Synchronized
    fun reset() {
        latencySamples = 0L
        latencyTotalMs = 0L
        sessionStartedAt = 0L
        _snapshot.value = Snapshot()
    }
}
