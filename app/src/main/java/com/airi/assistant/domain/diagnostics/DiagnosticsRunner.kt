package com.airi.assistant.domain.diagnostics

import android.util.Log
import com.airi.assistant.ai.QueryClassifier
import com.airi.assistant.ai.QueryType
import com.airi.assistant.ai.ResponseOptimizer
import com.airi.assistant.domain.logging.ProofLogger
import com.airi.assistant.domain.monetization.PaywallTriggerEngine
import com.airi.assistant.domain.monetization.PricingConfig
import com.airi.assistant.domain.verification.VerificationEvent
import com.airi.assistant.domain.verification.VerificationTracker

object DiagnosticsRunner {

    private const val TAG = "AIRI"

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
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "DIAGNOSTICS_START running 4 test scenarios")
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

        VerificationTracker.clear()
        listOf(80L, 120L, 3000L, 6200L, 9000L).forEachIndexed { index, latency ->
            VerificationTracker.record(
                VerificationEvent(
                    type = if (index == 0) "FAST" else "LLM",
                    latencyMs = latency,
                    tokens = 24 + index,
                    wasCut = index == 3,
                    queryType = if (index == 0) QueryType.SIMPLE.name else QueryType.ANALYTICAL.name
                )
            )
        }
        val p50 = VerificationTracker.p50LatencyMs()
        val p90 = VerificationTracker.p90LatencyMs()
        val longPartial = "AIRI starts with a clear answer. It keeps the important facts together. It avoids cutting inside a sentence while preserving meaning for the user. Extra trailing text is still generating"
        val shouldCut = ResponseOptimizer.shouldSemanticCut(
            partialText = longPartial,
            elapsedMs = 5_000L,
            tokensStreamed = 88,
            queryType = QueryType.ANALYTICAL,
            isPremium = false
        )
        val cut = ResponseOptimizer.semanticCut(longPartial)
        val tuned = ResponseOptimizer.adaptiveGeneration(
            queryType = QueryType.ANALYTICAL,
            ramCappedMaxTokens = 512,
            recentP90Ms = p90,
            isPremium = false
        )
        val upsell = PaywallTriggerEngine.evaluateDataDrivenUpsell(
            wasCut = true,
            latencyMs = PricingConfig.SPEED_UPSELL_THRESHOLD_MS + 1,
            totalMessages = 9,
            isPremium = false
        )
        val test4Pass = p50 == 3000L && p90 == 9000L && shouldCut && cut.wasCut &&
            tuned.maxTokens < 512 && upsell == PaywallTriggerEngine.TriggerReason.ResponseCut
        results += TestResult(
            name = "Optimization + Monetization Loop",
            passed = test4Pass,
            detail = "p50=${p50}ms p90=${p90}ms cut=${cut.wasCut} tunedTokens=${tuned.maxTokens} upsell=${upsell?.source}"
        )
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_OPTIMIZE", "VERIFY semanticCut=${cut.wasCut} p50=${p50}ms p90=${p90}ms tunedTokens=${tuned.maxTokens}")
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_MONET", "VERIFY upsell=${upsell?.source} slowThreshold=${PricingConfig.SPEED_UPSELL_THRESHOLD_MS}")
        ProofLogger.diagnosticsResult("Optimization + Monetization Loop", test4Pass, "p50=$p50 p90=$p90 cut=${cut.wasCut} tuned=${tuned.maxTokens} upsell=${upsell?.source}")

        val allPassed = results.all { it.passed }
        val summary   = results.joinToString(" | ") { "${it.name}:${if (it.passed) "PASS" else "FAIL"}" }
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "DIAGNOSTICS_COMPLETE allPassed=$allPassed [$summary]")

        return DiagnosticsReport(results = results, allPassed = allPassed)
    }

    fun runRuntimeVerification(
        modelLoaded: Boolean,
        firstTokenEmitted: Boolean,
        completionProduced: Boolean,
        exportSucceeded: Boolean,
        downloadSucceeded: Boolean,
        memoryStable: Boolean,
        detail: String
    ): DiagnosticsReport {
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "RUNTIME_DIAGNOSTICS_START")
        val results = listOf(
            TestResult("MODEL_LOAD", modelLoaded, detail),
            TestResult("FIRST_TOKEN", firstTokenEmitted, detail),
            TestResult("GENERATION", completionProduced, detail),
            TestResult("EXPORT", exportSucceeded, detail),
            TestResult("DOWNLOAD", downloadSucceeded, detail),
            TestResult("MEMORY", memoryStable, detail)
        )
        results.forEach {
            if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_VERIFY", "${it.name} ${if (it.passed) "PASS" else "FAIL"} detail=${it.detail}")
            ProofLogger.diagnosticsResult(it.name, it.passed, it.detail)
        }
        val allPassed = results.all { it.passed }
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "RUNTIME_DIAGNOSTICS_COMPLETE allPassed=$allPassed")
        if (allPassed && com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "SYSTEM FULLY VERIFIED")
        return DiagnosticsReport(results, allPassed)
    }
}
