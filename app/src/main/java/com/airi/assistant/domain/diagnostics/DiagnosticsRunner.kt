package com.airi.assistant.domain.diagnostics

import android.util.Log
import com.airi.assistant.ai.QueryClassifier
import com.airi.assistant.ai.QueryType
import com.airi.assistant.ai.ResponseOptimizer
import com.airi.assistant.domain.logging.ProofLogger

object DiagnosticsRunner {

    private const val TAG = "AIRI_PROOF"

    data class TestResult(
        val name: String,
        val passed: Boolean,
        val detail: String
    )

    data class DiagnosticsReport(
        val results: List<TestResult>,
        val allPassed: Boolean
    )

    fun runDiagnostics(): DiagnosticsReport {
        Log.d(TAG, "DIAGNOSTICS_START running 3 test scenarios")
        val results = mutableListOf<TestResult>()

        // ── Test 1: "hi" → must hit FAST_PATH ─────────────────────────────
        val hiInput = "hi"
        val hiType  = QueryClassifier.classifyQuery(hiInput)
        val hiFast  = ResponseOptimizer.tryFastResponse(hiInput) != null
        val test1Pass = hiType == QueryType.SIMPLE && hiFast
        results += TestResult(
            name   = "\"hi\" → FAST_PATH",
            passed = test1Pass,
            detail = "queryType=$hiType isFast=$hiFast expected=(SIMPLE+fast)"
        )
        ProofLogger.diagnosticsResult("hi → FAST_PATH", test1Pass, "type=$hiType fast=$hiFast")

        // ── Test 2: "Explain TCP handshake" → must route to STREAM (ANALYTICAL) ─
        val tcpInput = "Explain TCP handshake"
        val tcpType  = QueryClassifier.classifyQuery(tcpInput)
        val tcpFast  = ResponseOptimizer.tryFastResponse(tcpInput) != null
        val test2Pass = tcpType == QueryType.ANALYTICAL && !tcpFast
        results += TestResult(
            name   = "\"Explain TCP handshake\" → STREAM",
            passed = test2Pass,
            detail = "queryType=$tcpType isFast=$tcpFast expected=(ANALYTICAL+stream)"
        )
        ProofLogger.diagnosticsResult("Explain TCP → STREAM", test2Pass, "type=$tcpType fast=$tcpFast")

        // ── Test 3: "write a sci-fi story" → must classify CREATIVE ─────────
        val creativeInput = "write a sci-fi story"
        val creativeType  = QueryClassifier.classifyQuery(creativeInput)
        val test3Pass     = creativeType == QueryType.CREATIVE
        results += TestResult(
            name   = "\"write a sci-fi story\" → CREATIVE",
            passed = test3Pass,
            detail = "queryType=$creativeType expected=CREATIVE"
        )
        ProofLogger.diagnosticsResult("write a sci-fi story → CREATIVE", test3Pass, "type=$creativeType")

        val allPassed = results.all { it.passed }
        val summary   = results.joinToString(" | ") { "${it.name}:${if (it.passed) "PASS" else "FAIL"}" }
        Log.d(TAG, "DIAGNOSTICS_COMPLETE allPassed=$allPassed [$summary]")

        return DiagnosticsReport(results = results, allPassed = allPassed)
    }
}
