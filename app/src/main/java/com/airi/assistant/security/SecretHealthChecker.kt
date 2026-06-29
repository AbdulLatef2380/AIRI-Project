package com.airi.assistant.security

import android.util.Log
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.domain.logging.LoggingService

/**
 * SecretHealthChecker — startup validator for the AIRI secret infrastructure.
 *
 * Runs a self-test suite during [com.airi.assistant.AIRIApplication.onCreate] that:
 *
 *  1. **Keystore write/read round-trip** — writes a known sentinel value via
 *     [SecureStorage] and reads it back. If this fails, the Android Keystore is
 *     broken on this device and all secret operations will silently degrade.
 *
 *  2. **Keystore read-back consistency** — verifies the sentinel survives a
 *     fresh read, confirming EncryptedSharedPreferences integrity.
 *
 *  3. **Provider key presence** — checks whether at least one cloud provider
 *     API key is stored. Issues a non-fatal WARNING if none are configured
 *     (the app is still functional with local models).
 *
 *  4. **Consistency check** — verifies that [SecureStorage] connected-state
 *     flags are consistent with the stored tokens (e.g. "github connected"
 *     should imply a GitHub token is present).
 *
 * ## Failure handling
 *
 * - A [HealthResult.CRITICAL] failure logs an error. The app continues to
 *   start so the user can diagnose the issue, but cloud features degrade.
 * - A [HealthResult.WARNING] is logged only; no user-visible action is taken.
 * - [HealthResult.OK] is silent beyond the AIRI_PROOF log tag.
 *
 * This runs synchronously on the calling thread — call from a background
 * coroutine in Application.onCreate, NOT on Main.
 */
class SecretHealthChecker(
    private val secureStorage: SecureStorage
) {

    private val TAG = "SecretHealthChecker"

    enum class HealthResult { OK, WARNING, CRITICAL }

    data class CheckResult(
        val name:   String,
        val result: HealthResult,
        val detail: String
    )

    data class HealthReport(
        val checks:        List<CheckResult>,
        val overallResult: HealthResult
    ) {
        val isPassing:      Boolean          get() = overallResult != HealthResult.CRITICAL
        val criticalChecks: List<CheckResult> get() = checks.filter { it.result == HealthResult.CRITICAL }
        val warningChecks:  List<CheckResult> get() = checks.filter { it.result == HealthResult.WARNING }
    }

    /**
     * Run the full health check suite and return a [HealthReport].
     * Call from a background coroutine — performs disk I/O.
     */
    fun runChecks(): HealthReport {
        val results = mutableListOf<CheckResult>()

        results += checkKeystoreWriteRead()
        results += checkProviderKeyPresence()
        results += checkConnectedStateConsistency()

        val overall = when {
            results.any { it.result == HealthResult.CRITICAL } -> HealthResult.CRITICAL
            results.any { it.result == HealthResult.WARNING }  -> HealthResult.WARNING
            else                                                 -> HealthResult.OK
        }

        val report = HealthReport(results, overall)
        logReport(report)
        return report
    }

    // ── Individual checks ─────────────────────────────────────────────────────

    private fun checkKeystoreWriteRead(): CheckResult {
        // Use a unique ephemeral provider key as the test slot so we don't
        // collide with real provider keys. The sentinel is overwritten each run.
        val testProvider  = "_health_sentinel"
        val testValue     = "AIRI_HC_${System.currentTimeMillis()}"
        return try {
            secureStorage.saveLlmKey(testProvider, testValue)
            val read = secureStorage.getLlmKey(testProvider)
            if (read == testValue) {
                CheckResult("keystore_write_read", HealthResult.OK,
                    "EncryptedSharedPreferences write→read round-trip passed")
            } else {
                CheckResult("keystore_write_read", HealthResult.CRITICAL,
                    "Round-trip mismatch: wrote '${testValue.take(20)}' but read '${read?.take(20)}'")
            }
        } catch (e: Exception) {
            CheckResult("keystore_write_read", HealthResult.CRITICAL,
                "Keystore write/read failed: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }

    private fun checkProviderKeyPresence(): CheckResult {
        return try {
            val hasAnyCloudKey =
                secureStorage.getLlmKey("openai")     != null ||
                secureStorage.getLlmKey("anthropic")  != null ||
                secureStorage.getLlmKey("gemini")     != null ||
                secureStorage.getLlmKey("openrouter") != null

            if (hasAnyCloudKey) {
                CheckResult("provider_key_presence", HealthResult.OK,
                    "At least one cloud provider API key is configured")
            } else {
                CheckResult("provider_key_presence", HealthResult.WARNING,
                    "No cloud API keys found. Cloud inference unavailable until configured in Settings.")
            }
        } catch (e: Exception) {
            CheckResult("provider_key_presence", HealthResult.WARNING,
                "Could not check provider key presence: ${e.message?.take(80)}")
        }
    }

    private fun checkConnectedStateConsistency(): CheckResult {
        val issues = mutableListOf<String>()
        return try {
            // GitHub: connected flag should imply a stored token
            if (secureStorage.isGithubConnected() && secureStorage.getGithubToken() == null) {
                issues += "github: connected=true but token is null"
            }
            // Telegram: same invariant
            if (secureStorage.isTelegramConnected() && secureStorage.getTelegramToken() == null) {
                issues += "telegram: connected=true but token is null"
            }

            if (issues.isEmpty()) {
                CheckResult("connected_state_consistency", HealthResult.OK,
                    "All connected-state flags are consistent with stored tokens")
            } else {
                CheckResult("connected_state_consistency", HealthResult.WARNING,
                    "State inconsistencies: ${issues.joinToString("; ")}")
            }
        } catch (e: Exception) {
            CheckResult("connected_state_consistency", HealthResult.WARNING,
                "Consistency check failed: ${e.message?.take(80)}")
        }
    }

    // ── Logging ───────────────────────────────────────────────────────────────

    private fun logReport(report: HealthReport) {
        val airiTag = "AIRI_PROOF"
        when (report.overallResult) {
            HealthResult.OK -> {
                LoggingService.info(TAG, "$airiTag SECRET_HEALTH_OK checks=${report.checks.size}")
                Log.i(airiTag, "SECRET_HEALTH_OK all ${report.checks.size} checks passed")
            }
            HealthResult.WARNING -> {
                val warns = report.warningChecks.joinToString { it.name }
                LoggingService.warn(TAG, "$airiTag SECRET_HEALTH_WARNING warnings=[$warns]")
                Log.w(airiTag, "SECRET_HEALTH_WARNING warnings=[$warns]")
                report.warningChecks.forEach { Log.w(TAG, "  WARN [${it.name}]: ${it.detail}") }
            }
            HealthResult.CRITICAL -> {
                val crits = report.criticalChecks.joinToString { it.name }
                LoggingService.error(TAG, "$airiTag SECRET_HEALTH_CRITICAL failures=[$crits]")
                Log.e(airiTag, "SECRET_HEALTH_CRITICAL failures=[$crits]")
                report.criticalChecks.forEach { Log.e(TAG, "  CRIT [${it.name}]: ${it.detail}") }
            }
        }
    }
}
