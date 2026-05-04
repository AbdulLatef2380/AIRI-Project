package com.airi.assistant.crash

import android.content.Context
import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * CrashReportStore — persists orchestration and sub-agent crash reports to disk.
 *
 * ── DESIGN ────────────────────────────────────────────────────────────────
 *
 *   Reports are stored as a JSON list in {filesDir}/airi_crash_reports.json.
 *   The list is capped at [MAX_REPORTS] entries (LRU eviction). Each report
 *   contains a structured [CrashReport] with component, error class, message,
 *   stack digest (first 800 chars), plan/node IDs if applicable, and a
 *   session timestamp.
 *
 *   Reports do NOT contain user input, model output, or any PII. They are
 *   purely structural — component name, exception class, truncated stack.
 *
 * ── CONSENT ───────────────────────────────────────────────────────────────
 *
 *   CrashReportStore persists reports locally regardless of consent. Consent
 *   governs only whether reports are transmitted to Firebase Analytics via
 *   [PrivacyTelemetryReporter]. Users can view and delete reports at any
 *   time from PrivacyDataSettingsScreen.
 */
class CrashReportStore(private val context: Context) {

    private val TAG    = "CrashReportStore"
    private val gson   = Gson()
    private val file   = File(context.filesDir, "airi_crash_reports.json")

    private val _reports = MutableStateFlow<List<CrashReport>>(emptyList())
    val reports: StateFlow<List<CrashReport>> = _reports.asStateFlow()

    init { load() }

    data class CrashReport(
        val id:           String = UUID.randomUUID().toString(),
        val timestampMs:  Long   = System.currentTimeMillis(),
        val component:    String,
        val errorClass:   String,
        val errorMessage: String,
        val stackDigest:  String,
        val planId:       String? = null,
        val nodeId:       String? = null,
        val agentId:      String? = null,
        val sessionTag:   String  = SimpleDateFormat("yyyyMMdd_HH", Locale.US).format(Date())
    )

    /**
     * Record a crash. The [throwable] stack trace is truncated to 800 chars.
     */
    fun record(
        component:  String,
        throwable:  Throwable,
        planId:     String? = null,
        nodeId:     String? = null,
        agentId:    String? = null
    ): CrashReport {
        val report = CrashReport(
            component    = component,
            errorClass   = throwable.javaClass.simpleName,
            errorMessage = throwable.message?.take(200) ?: "no message",
            stackDigest  = throwable.stackTraceToString().take(800),
            planId       = planId,
            nodeId       = nodeId,
            agentId      = agentId
        )
        add(report)
        LoggingService.warn(TAG, "AIRI_PROOF CRASH_RECORDED component=$component id=${report.id} class=${report.errorClass}")
        return report
    }

    /**
     * Record a crash from structured fields (when no throwable is available).
     */
    fun recordManual(
        component:  String,
        errorClass: String,
        message:    String,
        planId:     String? = null,
        nodeId:     String? = null,
        agentId:    String? = null
    ): CrashReport {
        val report = CrashReport(
            component    = component,
            errorClass   = errorClass,
            errorMessage = message.take(200),
            stackDigest  = "",
            planId       = planId,
            nodeId       = nodeId,
            agentId      = agentId
        )
        add(report)
        LoggingService.warn(TAG, "AIRI_PROOF CRASH_RECORDED_MANUAL component=$component id=${report.id}")
        return report
    }

    /** Delete all stored reports. */
    fun clearAll() {
        _reports.value = emptyList()
        runCatching { file.delete() }
        LoggingService.info(TAG, "AIRI_PROOF CRASH_REPORTS_CLEARED")
    }

    /** Delete a single report by ID. */
    fun delete(reportId: String) {
        _reports.value = _reports.value.filter { it.id != reportId }
        persist()
    }

    private fun add(report: CrashReport) {
        val current  = _reports.value.toMutableList()
        current.add(0, report)
        if (current.size > MAX_REPORTS) current.subList(MAX_REPORTS, current.size).clear()
        _reports.value = current
        persist()
    }

    private fun persist() {
        runCatching {
            val tmp = File(file.parent, "${file.name}.tmp")
            tmp.writeText(gson.toJson(_reports.value), Charsets.UTF_8)
            tmp.renameTo(file)
        }.onFailure { Log.e(TAG, "Persist failed: ${it.message}") }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val type = object : TypeToken<List<CrashReport>>() {}.type
            val list: List<CrashReport> = gson.fromJson(file.readText(Charsets.UTF_8), type) ?: emptyList()
            _reports.value = list.take(MAX_REPORTS)
            Log.i(TAG, "Loaded ${list.size} crash reports from disk")
        }.onFailure { Log.e(TAG, "Load failed: ${it.message}") }
    }

    companion object {
        private const val MAX_REPORTS = 100
    }
}
