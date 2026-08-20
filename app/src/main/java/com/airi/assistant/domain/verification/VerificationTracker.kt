package com.airi.assistant.domain.verification

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object VerificationTracker {

    private const val TAG = "AIRI_VERIFY"
    private const val MAX_EVENTS = 20
    private val proofChecks = mutableMapOf<String, Boolean>()
    private var proofEmitted = false
    private val requiredProofChecks = setOf("MODEL_LOAD", "FIRST_TOKEN", "GENERATION", "EXPORT", "DOWNLOAD", "MEMORY")

    private val _events = MutableStateFlow<List<VerificationEvent>>(emptyList())
    val events: StateFlow<List<VerificationEvent>> = _events

    fun record(event: VerificationEvent) {
        _events.value = (_events.value + event).takeLast(MAX_EVENTS)
        val p50 = latencyPercentile(50)
        val p90 = latencyPercentile(90)
        Log.d(TAG,
            "type=${event.type} queryType=${event.queryType} " +
            "latency=${event.latencyMs}ms tokens=${event.tokens} cut=${event.wasCut} p50=${p50}ms p90=${p90}ms"
        )
    }

    fun recordCheck(name: String, passed: Boolean, detail: String) {
        val normalized = name.uppercase()
        Log.d(TAG, "$normalized ${if (passed) "PASS" else "FAIL"} detail=$detail")
        proofChecks[normalized] = passed
        if (!proofEmitted && requiredProofChecks.all { proofChecks[it] == true }) {
            proofEmitted = true
            Log.d("AIRI", "SYSTEM FULLY VERIFIED")
        }
    }

    fun lastEvent(): VerificationEvent? = _events.value.lastOrNull()

    fun latencyPercentile(percentile: Int): Long {
        val samples = _events.value.map { it.latencyMs }.filter { it >= 0L }.sorted()
        if (samples.isEmpty()) return -1L
        val clamped = percentile.coerceIn(0, 100)
        val index = kotlin.math.ceil((clamped / 100.0) * samples.size).toInt().coerceIn(1, samples.size) - 1
        return samples[index]
    }

    fun p50LatencyMs(): Long = latencyPercentile(50)

    fun p90LatencyMs(): Long = latencyPercentile(90)

    fun clear() {
        _events.value = emptyList()
        proofChecks.clear()
        proofEmitted = false
    }
}
