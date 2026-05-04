package com.airi.assistant.telemetry

import android.content.Context
import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TelemetryConsentStore — persists and exposes the user's opt-in decisions
 * for each telemetry category.
 *
 * ── CATEGORIES ────────────────────────────────────────────────────────────
 *
 *   ANALYTICS      — Firebase Analytics session/funnel events
 *   CRASH_REPORTS  — Orchestration crash reports (no PII)
 *   AGENT_TELEMETRY— Agent execution metrics (latency, error tags, counts)
 *
 * ── DEFAULT ───────────────────────────────────────────────────────────────
 *
 *   All categories default to FALSE (opt-out by default). The user must
 *   explicitly enable each one in PrivacyDataSettingsScreen.
 *
 * ── PERSISTENCE ───────────────────────────────────────────────────────────
 *
 *   Stored in regular SharedPreferences (not encrypted — consent state is
 *   not sensitive). Changing a flag emits on [consentState] immediately.
 */
class TelemetryConsentStore(context: Context) {

    private val TAG   = "TelemetryConsentStore"
    private val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    data class ConsentState(
        val analyticsEnabled:     Boolean = false,
        val crashReportingEnabled: Boolean = false,
        val agentTelemetryEnabled: Boolean = false
    ) {
        val anyEnabled: Boolean get() = analyticsEnabled || crashReportingEnabled || agentTelemetryEnabled
    }

    private val _consentState = MutableStateFlow(load())
    val consentState: StateFlow<ConsentState> = _consentState.asStateFlow()

    val current: ConsentState get() = _consentState.value

    fun setAnalyticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ANALYTICS, enabled).apply()
        _consentState.value = _consentState.value.copy(analyticsEnabled = enabled)
        log("analytics", enabled)
    }

    fun setCrashReportingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CRASH, enabled).apply()
        _consentState.value = _consentState.value.copy(crashReportingEnabled = enabled)
        log("crash_reporting", enabled)
    }

    fun setAgentTelemetryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AGENT_TEL, enabled).apply()
        _consentState.value = _consentState.value.copy(agentTelemetryEnabled = enabled)
        log("agent_telemetry", enabled)
    }

    fun revokeAll() {
        prefs.edit()
            .putBoolean(KEY_ANALYTICS, false)
            .putBoolean(KEY_CRASH, false)
            .putBoolean(KEY_AGENT_TEL, false)
            .apply()
        _consentState.value = ConsentState()
        LoggingService.info(TAG, "AIRI_PROOF TELEMETRY_ALL_REVOKED")
    }

    private fun load(): ConsentState = ConsentState(
        analyticsEnabled      = prefs.getBoolean(KEY_ANALYTICS, false),
        crashReportingEnabled = prefs.getBoolean(KEY_CRASH, false),
        agentTelemetryEnabled = prefs.getBoolean(KEY_AGENT_TEL, false)
    )

    private fun log(category: String, enabled: Boolean) {
        LoggingService.info(TAG, "AIRI_PROOF TELEMETRY_CONSENT category=$category enabled=$enabled")
    }

    companion object {
        private const val PREF_FILE    = "airi_telemetry_consent"
        private const val KEY_ANALYTICS  = "analytics_enabled"
        private const val KEY_CRASH      = "crash_reporting_enabled"
        private const val KEY_AGENT_TEL  = "agent_telemetry_enabled"
    }
}
