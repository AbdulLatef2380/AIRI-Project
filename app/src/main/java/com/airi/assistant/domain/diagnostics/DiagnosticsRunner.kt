package com.airi.assistant.domain.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.airi.assistant.ai.ModelManager
import com.airi.assistant.ai.QueryClassifier
import com.airi.assistant.ai.QueryType
import com.airi.assistant.ai.ResponseOptimizer
import com.airi.assistant.domain.logging.ProofLogger
import com.airi.assistant.domain.monetization.PaywallTriggerEngine
import com.airi.assistant.domain.monetization.PricingConfig
import com.airi.assistant.domain.verification.VerificationEvent
import com.airi.assistant.domain.verification.VerificationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object DiagnosticsRunner {

    private const val TAG = "AIRI_PROOF"
    private const val MB  = 1_048_576L
    private const val GB  = 1_073_741_824L

    // ── System health check types ─────────────────────────────────────────────

    enum class Severity { OK, WARN, CRITICAL }

    data class HealthCheck(
        val name:     String,
        val value:    String,
        val severity: Severity,
        val detail:   String = ""
    )

    data class SystemHealthReport(
        val checks:      List<HealthCheck>,
        val timestampMs: Long    = System.currentTimeMillis(),
        val overallOk:   Boolean,
        val summary:     String,
        val jsonReport:  String
    ) {
        val criticalCount: Int get() = checks.count { it.severity == Severity.CRITICAL }
        val warnCount:     Int get() = checks.count { it.severity == Severity.WARN }
    }

    // ── Full system health snapshot (Phase 9) ─────────────────────────────────

    /**
     * Runs a comprehensive on-demand system health check covering RAM,
     * storage, network, model state, JVM heap, and runtime info.
     * Returns a [SystemHealthReport] with per-check severity ratings.
     *
     * Safe to call from any coroutine — all work dispatches to [Dispatchers.IO].
     */
    suspend fun runSystemHealthCheck(context: Context): SystemHealthReport =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "HEALTH_CHECK_START")
            val checks = mutableListOf<HealthCheck>()

            checks += ramHealthChecks(context)
            checks += storageHealthChecks(context)
            checks += networkHealthChecks(context)
            checks += modelHealthChecks()
            checks += jvmHealthChecks()
            checks += runtimeHealthChecks()

            val overallOk = checks.none { it.severity == Severity.CRITICAL }
            val summary   = buildHealthSummary(checks)
            val json      = buildHealthJson(checks)

            Log.i(TAG, "HEALTH_CHECK_COMPLETE ok=$overallOk " +
                "critical=${checks.count { it.severity == Severity.CRITICAL }} " +
                "warn=${checks.count { it.severity == Severity.WARN }}")

            SystemHealthReport(
                checks     = checks,
                overallOk  = overallOk,
                summary    = summary,
                jsonReport = json
            )
        }

    private fun ramHealthChecks(context: Context): List<HealthCheck> {
        val am  = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return listOf(HealthCheck("RAM", "unavailable", Severity.WARN))
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val totalMb  = mem.totalMem / MB
        val availMb  = mem.availMem / MB
        val usedPct  = if (totalMb > 0) ((totalMb - availMb) * 100 / totalMb).toInt() else 0

        return listOf(
            HealthCheck("RAM — Total", "${totalMb}MB", Severity.OK),
            HealthCheck(
                name     = "RAM — Available",
                value    = "${availMb}MB (${100 - usedPct}% free)",
                severity = when {
                    availMb < 256 || usedPct > 90 -> Severity.CRITICAL
                    usedPct > 80                   -> Severity.WARN
                    else                            -> Severity.OK
                },
                detail = if (usedPct > 80) "High memory pressure — LLM may degrade" else ""
            ),
            HealthCheck(
                name     = "RAM — Low-RAM Device",
                value    = if (am.isLowRamDevice) "YES ⚠" else "No",
                severity = if (am.isLowRamDevice) Severity.WARN else Severity.OK,
                detail   = if (am.isLowRamDevice) "Only models ≤1.5B recommended" else ""
            )
        )
    }

    private fun storageHealthChecks(context: Context): List<HealthCheck> {
        val stat    = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val freeGb  = stat?.freeBytes?.toFloat()?.div(GB) ?: 0f
        val totalGb = stat?.totalBytes?.toFloat()?.div(GB) ?: 0f
        val freePct = if (totalGb > 0f) ((freeGb / totalGb) * 100).toInt() else 100
        val modelDir  = context.getExternalFilesDir("models")
            ?: context.filesDir.resolve("models")
        val dirOk = modelDir.exists() || modelDir.mkdirs()
        return listOf(
            HealthCheck(
                name     = "Storage — Free",
                value    = "${String.format("%.1f", freeGb)}GB / ${String.format("%.1f", totalGb)}GB",
                severity = when {
                    freeGb < 0.5f || freePct < 5  -> Severity.CRITICAL
                    freeGb < 2.0f || freePct < 15 -> Severity.WARN
                    else                            -> Severity.OK
                },
                detail = if (freeGb < 0.5f) "Critically low — downloads will fail" else ""
            ),
            HealthCheck(
                name     = "Storage — Model Dir",
                value    = if (dirOk) "✓ accessible" else "INACCESSIBLE",
                severity = if (dirOk) Severity.OK else Severity.CRITICAL,
                detail   = if (!dirOk) "Cannot create ${modelDir.absolutePath}" else modelDir.absolutePath
            )
        )
    }

    private fun networkHealthChecks(context: Context): List<HealthCheck> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val nc = cm?.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            nc == null -> "None"
            nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "Wi-Fi"
            nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Unknown"
        }
        val online = nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return listOf(
            HealthCheck("Network — Type", type, Severity.OK),
            HealthCheck(
                name     = "Network — Internet",
                value    = if (online) "Connected ✓" else "Offline",
                severity = Severity.OK,
                detail   = if (!online) "Cloud features unavailable" else ""
            )
        )
    }

    private fun modelHealthChecks(): List<HealthCheck> {
        val state     = ModelManager.state.value
        val allModels = ModelManager.getAllModels()
        val checks    = mutableListOf<HealthCheck>()
        checks += HealthCheck(
            name     = "Model — State",
            value    = when {
                state.isLoading -> "Loading (${state.loadProgress}%)"
                state.isReady   -> "Ready — ${state.currentModel?.name ?: "?"}"
                else            -> "Idle"
            },
            severity = if (state.errorMessage != null) Severity.WARN else Severity.OK,
            detail   = state.errorMessage ?: ""
        )
        checks += HealthCheck(
            name     = "Model — Registry",
            value    = "${allModels.size} model(s)",
            severity = if (allModels.isEmpty()) Severity.WARN else Severity.OK,
            detail   = allModels.take(3).joinToString(", ") { it.name }
        )
        return checks
    }

    private fun jvmHealthChecks(): List<HealthCheck> {
        val rt     = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val maxMb  = rt.maxMemory() / MB
        val pct    = if (maxMb > 0) (usedMb * 100 / maxMb).toInt() else 0
        return listOf(HealthCheck(
            name     = "JVM Heap",
            value    = "${usedMb}MB / ${maxMb}MB ($pct%)",
            severity = when {
                pct > 90 -> Severity.CRITICAL
                pct > 75 -> Severity.WARN
                else      -> Severity.OK
            },
            detail = if (pct > 75) "GC pressure may cause UI jank" else ""
        ))
    }

    private fun runtimeHealthChecks(): List<HealthCheck> {
        val abi   = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val api   = android.os.Build.VERSION.SDK_INT
        val is64  = abi.contains("arm64") || abi.contains("x86_64")
        return listOf(
            HealthCheck(
                name     = "Runtime — CPU ABI",
                value    = abi,
                severity = if (is64) Severity.OK else Severity.WARN,
                detail   = if (!is64) "32-bit: NDK performance reduced" else ""
            ),
            HealthCheck(
                name     = "Runtime — Android API",
                value    = "API $api (${android.os.Build.VERSION.RELEASE})",
                severity = if (api >= 26) Severity.OK else Severity.CRITICAL,
                detail   = if (api < 26) "AIRI requires API 26+" else ""
            ),
            HealthCheck(
                name     = "Runtime — Threads",
                value    = "${Thread.activeCount()} active",
                severity = if (Thread.activeCount() > 250) Severity.WARN else Severity.OK
            )
        )
    }

    private fun buildHealthSummary(checks: List<HealthCheck>): String {
        val c = checks.filter { it.severity == Severity.CRITICAL }
        val w = checks.filter { it.severity == Severity.WARN }
        return when {
            c.isNotEmpty() -> "CRITICAL: ${c.joinToString("; ") { "${it.name}=${it.value}" }}"
            w.isNotEmpty() -> "WARN: ${w.joinToString("; ") { "${it.name}=${it.value}" }}"
            else           -> "All ${checks.size} health checks passed ✓"
        }
    }

    private fun buildHealthJson(checks: List<HealthCheck>): String {
        val arr = JSONArray()
        for (c in checks) arr.put(JSONObject()
            .put("name", c.name).put("value", c.value)
            .put("severity", c.severity.name).put("detail", c.detail))
        return JSONObject()
            .put("timestamp_ms", System.currentTimeMillis())
            .put("checks", arr)
            .put("overall_ok", checks.none { it.severity == Severity.CRITICAL })
            .toString(2)
    }

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
