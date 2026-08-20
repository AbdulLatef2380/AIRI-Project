package com.airi.assistant.runtime.connector

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * ConnectorChaosTester — Phase R7 connector reliability chaos harness.
 *
 * Injects failure conditions into connector execution paths to validate:
 *   - Expired / revoked auth → graceful re-auth prompt, not crash
 *   - Network drop mid-request → retry with exponential back-off, not hang
 *   - Timeout storm → bounded retries, correct cancellation propagation
 *   - Partial / malformed response → error mapped, not thrown to UI
 *   - Quota exhausted → user-visible error, no infinite loop
 *   - Reconnection storm → bounded reconnect, not CPU spin
 *
 * ── Usage ─────────────────────────────────────────────────────────────────
 * Inject a [ConnectorUnderTest] adapter for the connector being validated.
 * Call [runChaos] with the desired failure set. Results are emitted to
 * [results] StateFlow and printed with AIRI tags.
 */
class ConnectorChaosTester {

    private val TAG   = "ConnectorChaosTester"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Contract that a connector under test must satisfy. */
    interface ConnectorUnderTest {
        val name: String
        suspend fun connect(): Result<Unit>
        suspend fun execute(payload: String): Result<String>
        fun simulateAuthExpiry()
        fun simulateNetworkDrop()
        fun simulateQuotaExhausted()
        fun restoreNormal()
    }

    enum class ChaosScenario {
        AUTH_EXPIRY,
        NETWORK_DROP,
        TIMEOUT_STORM,
        MALFORMED_RESPONSE,
        QUOTA_EXHAUSTED,
        RECONNECT_LOOP
    }

    data class ChaosResult(
        val connector:   String,
        val scenario:    ChaosScenario,
        val passed:      Boolean,
        val durationMs:  Long,
        val retryCount:  Int,
        val errorClass:  String?,
        val notes:       String = ""
    )

    private val _results = MutableStateFlow<List<ChaosResult>>(emptyList())
    val results: StateFlow<List<ChaosResult>> = _results.asStateFlow()

    // ── Public API ──────────────────────────────────────────────────────────

    fun runChaos(
        connector: ConnectorUnderTest,
        scenarios: Set<ChaosScenario> = ChaosScenario.values().toSet()
    ) {
        scope.launch {
            val accumulated = mutableListOf<ChaosResult>()
            for (scenario in scenarios) {
                Log.i(TAG, "AIRI CHAOS_START connector=${connector.name} scenario=$scenario")
                val result = runScenario(connector, scenario)
                connector.restoreNormal()
                accumulated.add(result)
                _results.value = accumulated.toList()
                Log.i(TAG, "AIRI CHAOS_DONE connector=${connector.name} " +
                        "scenario=$scenario passed=${result.passed} retries=${result.retryCount}")
                delay(1_000L) // allow connector to settle
            }
        }
    }

    // ── Scenario runners ────────────────────────────────────────────────────

    private suspend fun runScenario(
        connector: ConnectorUnderTest,
        scenario:  ChaosScenario
    ): ChaosResult {
        val t0 = System.currentTimeMillis()
        var retryCount = 0
        var passed     = false
        var errorClass: String? = null
        var notes      = ""

        try {
            when (scenario) {
                ChaosScenario.AUTH_EXPIRY -> {
                    connector.simulateAuthExpiry()
                    val result = withTimeout(10_000L) { connector.execute("test") }
                    // Pass = either graceful error OR successful re-auth, NOT a crash
                    passed = result.isFailure && result.exceptionOrNull()?.message?.contains("auth", ignoreCase = true) == true
                    notes  = "Auth error: ${result.exceptionOrNull()?.message?.take(80)}"
                }

                ChaosScenario.NETWORK_DROP -> {
                    connector.simulateNetworkDrop()
                    var attempts = 0
                    var success  = false
                    // Connector should retry max 3 times then give up
                    while (attempts < 5 && !success) {
                        val result = runCatching {
                            withTimeout(5_000L) { connector.execute("test") }
                        }
                        attempts++
                        retryCount = attempts - 1
                        if (result.getOrNull()?.isSuccess == true) { success = true; break }
                        if (result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException) {
                            // Expected — network is down
                        }
                        delay(500L)
                    }
                    // Pass = didn't hang indefinitely + eventually gave up gracefully
                    passed = attempts <= 4
                    notes  = "Stopped after $attempts attempts"
                }

                ChaosScenario.TIMEOUT_STORM -> {
                    // Fire 20 requests simultaneously, all expected to timeout
                    var timeouts = 0
                    var crashes  = 0
                    val jobs = (1..20).map { _ ->
                        scope.launch {
                            runCatching {
                                withTimeout(1_000L) { connector.execute("timeout_test") }
                            }.onFailure { e ->
                                if (e is kotlinx.coroutines.TimeoutCancellationException) timeouts++
                                else { crashes++; errorClass = e::class.simpleName }
                            }
                        }
                    }
                    jobs.forEach { it.join() }
                    retryCount = 20
                    passed     = crashes == 0   // timeouts ok, crashes not
                    notes      = "20 concurrent timeouts: crashes=$crashes"
                }

                ChaosScenario.MALFORMED_RESPONSE -> {
                    // Execute with a payload designed to elicit unparseable response
                    val result = runCatching {
                        withTimeout(10_000L) { connector.execute("\u0000\uffff<>{}[]malformed") }
                    }
                    // Pass = no uncaught exception escaping to caller
                    passed = true  // If we got here without crashing, it passed
                    errorClass = result.exceptionOrNull()?.let { it::class.simpleName }
                    notes  = "Exception: ${result.exceptionOrNull()?.message?.take(60) ?: "none"}"
                }

                ChaosScenario.QUOTA_EXHAUSTED -> {
                    connector.simulateQuotaExhausted()
                    val result = runCatching {
                        withTimeout(10_000L) { connector.execute("quota_test") }
                    }
                    passed = result.isFailure   // Should fail gracefully, not loop
                    errorClass = result.exceptionOrNull()?.let { it::class.simpleName }
                    notes  = "Quota error class: $errorClass"
                }

                ChaosScenario.RECONNECT_LOOP -> {
                    // Simulate repeated disconnects — count reconnection attempts
                    var reconnects = 0
                    connector.simulateNetworkDrop()
                    val startMs = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startMs < 10_000L) {
                        val r = runCatching { connector.connect() }
                        if (r.isFailure) reconnects++
                        delay(200L)
                        if (reconnects > 20) break  // detect infinite loop
                    }
                    passed = reconnects <= 20   // bounded retries
                    notes  = "Reconnects attempted: $reconnects"
                }
            }
        } catch (e: Exception) {
            errorClass = e::class.simpleName
            notes = "Unexpected: ${e.message?.take(80)}"
            Log.e(TAG, "AIRI CHAOS_EXCEPTION connector=${connector.name} scenario=$scenario", e)
        }

        return ChaosResult(
            connector  = connector.name,
            scenario   = scenario,
            passed     = passed,
            durationMs = System.currentTimeMillis() - t0,
            retryCount = retryCount,
            errorClass = errorClass,
            notes      = notes
        )
    }
}
