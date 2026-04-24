package com.airi.assistant.core.debug

import android.util.Log
import com.airi.assistant.core.analytics.ProofLogger
import com.airi.assistant.domain.diagnostics.DiagnosticsRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Diagnostics {

    private const val TAG = "AIRI_PROOF"

    private val _systemHealthy = MutableStateFlow(true)
    val systemHealthy: StateFlow<Boolean> = _systemHealthy

    fun runDiagnostics(): DiagnosticsRunner.DiagnosticsReport {
        val report = DiagnosticsRunner.runDiagnostics()
        report.results.forEach { result ->
            ProofLogger.log(
                "TEST_RESULT",
                "case=${result.name} ${if (result.passed) "PASS" else "FAIL"} detail=${result.detail}"
            )
        }
        val healthy = report.allPassed
        _systemHealthy.value = healthy
        Log.d(TAG, "SYSTEM_HEALTH healthy=$healthy")
        return report
    }
}
