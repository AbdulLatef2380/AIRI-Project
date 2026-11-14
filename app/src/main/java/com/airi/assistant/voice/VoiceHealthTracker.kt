package com.airi.assistant.voice

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Monotonic elapsed time boundary, kept independent from Android in JVM tests. */
internal fun interface MonotonicClock {
    fun elapsedRealtimeMs(): Long
}

private object AndroidMonotonicClock : MonotonicClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

/** Mutable aggregate implementation with an injectable monotonic clock. */
internal class VoiceHealthTrackerState(
    private val clock: MonotonicClock,
) {
    private val _snapshot = MutableStateFlow(VoiceHealthTracker.Snapshot())
    val snapshot: StateFlow<VoiceHealthTracker.Snapshot> = _snapshot.asStateFlow()
    private var latencySamples = 0L
    private var latencyTotalMs = 0L
    private var sessionStartedAt = 0L

    @Synchronized
    fun sessionStarted() {
        val now = clock.elapsedRealtimeMs()
        sessionStartedAt = now
        _snapshot.value = _snapshot.value.copy(
            sessions = _snapshot.value.sessions + 1,
            lastUpdatedElapsedMs = now,
        )
    }

    @Synchronized
    fun wakeDetected() {
        val now = clock.elapsedRealtimeMs()
        _snapshot.value = _snapshot.value.copy(
            wakeDetections = _snapshot.value.wakeDetections + 1,
            lastUpdatedElapsedMs = now,
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
            lastUpdatedElapsedMs = clock.elapsedRealtimeMs(),
        )
    }

    @Synchronized
    fun sessionCompleted() {
        val now = clock.elapsedRealtimeMs()
        val duration = sessionStartedAt.takeIf { it > 0L }
            ?.let { (now - it).coerceAtLeast(0L) }
        sessionStartedAt = 0L
        _snapshot.value = _snapshot.value.copy(
            completedSessions = _snapshot.value.completedSessions + 1,
            lastSessionDurationMs = duration,
            lastUpdatedElapsedMs = now,
        )
    }

    @Synchronized
    fun error(code: String) {
        val safeCode = code.trim().take(48).ifBlank { "unknown" }
        _snapshot.value = _snapshot.value.copy(
            errorCount = _snapshot.value.errorCount + 1,
            lastErrorCode = safeCode,
            lastUpdatedElapsedMs = clock.elapsedRealtimeMs(),
        )
    }

    @Synchronized
    fun reset() {
        latencySamples = 0L
        latencyTotalMs = 0L
        sessionStartedAt = 0L
        _snapshot.value = VoiceHealthTracker.Snapshot()
    }
}

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

    private val state = VoiceHealthTrackerState(AndroidMonotonicClock)
    val snapshot: StateFlow<Snapshot> = state.snapshot

    fun sessionStarted() = state.sessionStarted()
    fun wakeDetected() = state.wakeDetected()
    fun firstAudioLatency(latencyMs: Long) = state.firstAudioLatency(latencyMs)
    fun sessionCompleted() = state.sessionCompleted()
    fun error(code: String) = state.error(code)
    fun reset() = state.reset()
}
