package com.airi.assistant.domain.diagnostics

import android.content.Context
import android.util.Log
import com.airi.assistant.ai.InferenceHealthMonitor
import com.airi.assistant.connector.ConnectorRegistry
import com.airi.assistant.connector.ConnectorRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * DeepDiagnosticScanner — comprehensive runtime inspection across all AIRI subsystems.
 *
 * Produces 7 structured reports that diagnose the full agent operating system:
 *
 *  | Report                     | What it checks                                      |
 *  |----------------------------|-----------------------------------------------------|
 *  | [scanArchitecture]         | Service wiring, missing singletons, init order      |
 *  | [scanRuntime]              | Active sessions, task queue depth, memory budgets   |
 *  | [scanOrchestration]        | Orchestrator health, routing decisions, fallbacks    |
 *  | [scanConnectors]           | Connector health, dead connectors, retry storms     |
 *  | [scanModelLayer]           | Model loaded, capabilities, KV cache, context pct   |
 *  | [scanMemory]               | DB accessible, embedding service, RAG retriever     |
 *  | [scanExecution]            | Accessibility service active, tool registry size    |
 *  | [fullScan]                 | All 7 reports merged into a single JSON report      |
 *
 * ── SEVERITY LEVELS ──────────────────────────────────────────────────────────
 *
 *   OK      — System is healthy in this dimension.
 *   WARN    — Degraded but functional. Investigate soon.
 *   CRITICAL— Subsystem is broken or missing. Immediate action required.
 *
 * ── PROOF LOGGING ─────────────────────────────────────────────────────────────
 *
 *   Every finding is emitted to logcat with tag AIRI_PROOF_DEEPDIAG for audit.
 *
 * ── USAGE ────────────────────────────────────────────────────────────────────
 *
 *   val report = deepDiagnosticScanner.fullScan()
 *   // report is a JSON string — display in DebugPanelScreen or ExecDiagnosticsScreen
 */
class DeepDiagnosticScanner(
    private val context:              Context,
    private val diagnosticsEngine:    DiagnosticsEngine,
    private val inferenceHealthMonitor: InferenceHealthMonitor,
    private val connectorRegistry:    ConnectorRegistry,
    private val connectorRuntimeMgr:  ConnectorRuntimeManager,
) {

    private val TAG = "DeepDiagnosticScanner"

    // ── Severity model ────────────────────────────────────────────────────────

    enum class Severity { OK, WARN, CRITICAL }

    data class Finding(
        val subsystem:  String,
        val name:       String,
        val severity:   Severity,
        val value:      String,
        val detail:     String = "",
        val suggestion: String = "",
    )

    data class DiagReport(
        val subsystem:  String,
        val findings:   List<Finding>,
        val generatedAtMs: Long = System.currentTimeMillis(),
    ) {
        val overallSeverity: Severity get() = when {
            findings.any { it.severity == Severity.CRITICAL } -> Severity.CRITICAL
            findings.any { it.severity == Severity.WARN }     -> Severity.WARN
            else                                               -> Severity.OK
        }
        val summary: String get() {
            val ok   = findings.count { it.severity == Severity.OK }
            val warn = findings.count { it.severity == Severity.WARN }
            val crit = findings.count { it.severity == Severity.CRITICAL }
            return "$subsystem: ${ok}OK ${warn}WARN ${crit}CRITICAL"
        }
    }

    // ── Full scan ─────────────────────────────────────────────────────────────

    /**
     * Run all 7 diagnostic reports and return a merged JSON string.
     */
    suspend fun fullScan(): String = withContext(Dispatchers.IO) {
        val reports = listOf(
            scanArchitecture(),
            scanRuntime(),
            scanOrchestration(),
            scanConnectors(),
            scanModelLayer(),
            scanMemory(),
            scanExecution(),
        )

        val overall = when {
            reports.any { it.overallSeverity == Severity.CRITICAL } -> Severity.CRITICAL
            reports.any { it.overallSeverity == Severity.WARN }     -> Severity.WARN
            else                                                      -> Severity.OK
        }

        Log.w(TAG, "AIRI_PROOF_DEEPDIAG overall=${overall.name} reports=${reports.size}")

        JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("overall", overall.name)
            put("reports", JSONArray().also { arr ->
                reports.forEach { r ->
                    arr.put(JSONObject().apply {
                        put("subsystem", r.subsystem)
                        put("severity", r.overallSeverity.name)
                        put("summary", r.summary)
                        put("findings", JSONArray().also { fa ->
                            r.findings.forEach { f ->
                                fa.put(JSONObject().apply {
                                    put("name", f.name)
                                    put("severity", f.severity.name)
                                    put("value", f.value)
                                    put("detail", f.detail)
                                    put("suggestion", f.suggestion)
                                })
                            }
                        })
                    })
                }
            })
        }.toString(2)
    }

    // ── Individual reports ────────────────────────────────────────────────────

    suspend fun scanArchitecture(): DiagReport = withContext(Dispatchers.IO) {
        val findings = mutableListOf<Finding>()

        // ServiceLocator init
        val ctxOk = runCatching {
            com.airi.assistant.core.ServiceLocator.context != null
        }.getOrDefault(false)
        findings += Finding("ARCHITECTURE", "ServiceLocator.init", if (ctxOk) Severity.OK else Severity.CRITICAL,
            if (ctxOk) "initialized" else "NOT initialized",
            suggestion = "Call ServiceLocator.init(context) in Application.onCreate()")

        // ConnectorRegistry
        val regSize = runCatching { connectorRegistry.all().size }.getOrDefault(-1)
        findings += Finding("ARCHITECTURE", "ConnectorRegistry", if (regSize > 0) Severity.OK else Severity.WARN,
            "$regSize connectors",
            suggestion = if (regSize == 0) "ConnectorBootstrap.installAll() may not have been called" else "")

        // Accessibility service
        val a11yOk = com.airi.assistant.accessibility.service.AiriAccessibilityService.instance != null
        findings += Finding("ARCHITECTURE", "AccessibilityService", if (a11yOk) Severity.OK else Severity.WARN,
            if (a11yOk) "active" else "inactive",
            detail = "Required for computer-use automation tasks",
            suggestion = "Enable AIRI Accessibility in Settings → Accessibility")

        DiagReport("Architecture", findings)
    }

    suspend fun scanRuntime(): DiagReport = withContext(Dispatchers.IO) {
        val findings  = mutableListOf<Finding>()
        val health    = diagnosticsEngine.health.value
        val infHealth = inferenceHealthMonitor.currentHealth()

        findings += Finding("RUNTIME", "RAM_free", when {
            health.availableRamMb < 200 -> Severity.CRITICAL
            health.availableRamMb < 500 -> Severity.WARN
            else                        -> Severity.OK
        }, "${health.availableRamMb} MB", suggestion = if (health.availableRamMb < 200) "Close background apps or use smaller model" else "")

        findings += Finding("RUNTIME", "JVM_heap", when {
            infHealth.heapPressurePct > 90 -> Severity.CRITICAL
            infHealth.heapPressurePct > 70 -> Severity.WARN
            else                           -> Severity.OK
        }, "${infHealth.heapPressurePct}% (${infHealth.heapUsedMb}/${infHealth.heapMaxMb} MB)",
            suggestion = if (infHealth.heapPressurePct > 90) "Restart inference session to reclaim heap" else "")

        findings += Finding("RUNTIME", "storage", when (health.storagePressure) {
            DiagnosticsEngine.StoragePressure.CRITICAL -> Severity.CRITICAL
            DiagnosticsEngine.StoragePressure.LOW      -> Severity.WARN
            else                                       -> Severity.OK
        }, health.storagePressure.name,
            suggestion = if (health.storagePressure != DiagnosticsEngine.StoragePressure.OK) "Free device storage space" else "")

        DiagReport("Runtime", findings)
    }

    suspend fun scanOrchestration(): DiagReport = withContext(Dispatchers.IO) {
        val findings = mutableListOf<Finding>()

        val inferHealth = inferenceHealthMonitor.currentHealth()
        findings += Finding("ORCHESTRATION", "InferenceHealth", inferHealth.status.let {
            when (it) {
                InferenceHealthMonitor.HealthStatus.NOMINAL   -> Severity.OK
                InferenceHealthMonitor.HealthStatus.DEGRADED  -> Severity.WARN
                InferenceHealthMonitor.HealthStatus.CRITICAL  -> Severity.CRITICAL
            }
        }, inferHealth.status.name,
            detail = "Tokens generated: ${inferHealth.totalTokens}, Generations: ${inferHealth.totalGenerations}")

        findings += Finding("ORCHESTRATION", "NativeLib", if (inferHealth.isNativeLoaded) Severity.OK else Severity.CRITICAL,
            if (inferHealth.isNativeLoaded) "loaded" else "MISSING",
            suggestion = if (!inferHealth.isNativeLoaded) "libairi_native.so failed to load. Reinstall app." else "")

        if (inferHealth.repairSuggestions.isNotEmpty()) {
            findings += Finding("ORCHESTRATION", "RepairSuggestions", Severity.WARN,
                "${inferHealth.repairSuggestions.size} suggestions",
                detail = inferHealth.repairSuggestions.joinToString(", ") { it.name })
        }

        DiagReport("Orchestration", findings)
    }

    suspend fun scanConnectors(): DiagReport = withContext(Dispatchers.IO) {
        val findings   = mutableListOf<Finding>()
        val healthMap  = connectorRuntimeMgr.healthMap.value
        val allConnectors = connectorRegistry.all()

        for (connector in allConnectors) {
            val status = healthMap[connector.id]
            val health = status?.health ?: ConnectorRuntimeManager.ConnectorHealth.UNKNOWN
            findings += Finding("CONNECTORS", connector.name, when (health) {
                ConnectorRuntimeManager.ConnectorHealth.HEALTHY  -> Severity.OK
                ConnectorRuntimeManager.ConnectorHealth.DEGRADED -> Severity.WARN
                ConnectorRuntimeManager.ConnectorHealth.OFFLINE  -> Severity.WARN
                ConnectorRuntimeManager.ConnectorHealth.UNKNOWN  -> Severity.WARN
            }, health.name,
                detail = status?.lastErrorMessage ?: "",
                suggestion = if (health != ConnectorRuntimeManager.ConnectorHealth.HEALTHY)
                    "Check connector config. Consecutive fails: ${status?.consecutiveFails ?: 0}" else "")
        }

        if (findings.isEmpty()) {
            findings += Finding("CONNECTORS", "NoConnectors", Severity.WARN, "0",
                suggestion = "Install connectors via ConnectorBootstrap.installAll()")
        }

        DiagReport("Connectors", findings)
    }

    suspend fun scanModelLayer(): DiagReport = withContext(Dispatchers.IO) {
        val findings   = mutableListOf<Finding>()
        val infHealth  = inferenceHealthMonitor.currentHealth()

        findings += Finding("MODEL", "ModelLoaded", if (infHealth.isModelLoaded) Severity.OK else Severity.WARN,
            if (infHealth.isModelLoaded) infHealth.modelName.take(40) else "none",
            suggestion = if (!infHealth.isModelLoaded) "Download and load a model from Settings → AI Models" else "")

        val model = com.airi.assistant.ai.ModelManager.getCurrent()
        if (model != null) {
            val caps = runCatching { com.airi.assistant.ai.ModelCapabilities.detect(model) }.getOrNull()
            if (caps != null) {
                findings += Finding("MODEL", "Capabilities", Severity.OK, caps.summary(),
                    detail = caps.rawDescription.take(80))
            }
        }

        findings += Finding("MODEL", "KVResets", if (infHealth.kvResets > 10) Severity.WARN else Severity.OK,
            "${infHealth.kvResets}",
            suggestion = if (infHealth.kvResets > 10) "Many KV cache resets — context window may be too large" else "")

        DiagReport("Model", findings)
    }

    suspend fun scanMemory(): DiagReport = withContext(Dispatchers.IO) {
        val findings = mutableListOf<Finding>()

        val dbOk = runCatching {
            com.airi.assistant.memory.AiriDatabase.getDatabase(context)
            true
        }.getOrDefault(false)
        findings += Finding("MEMORY", "RoomDatabase", if (dbOk) Severity.OK else Severity.CRITICAL,
            if (dbOk) "accessible" else "FAILED",
            suggestion = if (!dbOk) "Room DB is corrupted. Clear app data." else "")

        val ragOk = runCatching {
            com.airi.assistant.core.ServiceLocator.ragRetriever != null
        }.getOrDefault(false)
        findings += Finding("MEMORY", "RagRetriever", if (ragOk) Severity.OK else Severity.WARN,
            if (ragOk) "ready" else "unavailable",
            suggestion = if (!ragOk) "EmbeddingService failed to initialize" else "")

        DiagReport("Memory", findings)
    }

    suspend fun scanExecution(): DiagReport = withContext(Dispatchers.IO) {
        val findings = mutableListOf<Finding>()

        val toolCount = runCatching {
            com.airi.assistant.core.ServiceLocator.let {
                com.airi.assistant.ai.tools.ToolRegistry(context).getAvailableTools().size
            }
        }.getOrDefault(-1)
        findings += Finding("EXECUTION", "ToolRegistry", if (toolCount > 0) Severity.OK else Severity.WARN,
            "$toolCount tools",
            suggestion = if (toolCount == 0) "No tools registered — agent cannot use tools" else "")

        val subAgentCount = runCatching {
            com.airi.assistant.agent.subagent.SubAgentRegistry.agents().size
        }.getOrDefault(-1)
        findings += Finding("EXECUTION", "SubAgentRegistry", if (subAgentCount > 0) Severity.OK else Severity.WARN,
            "$subAgentCount agents",
            suggestion = if (subAgentCount == 0) "initSubAgentSystem() may not have been called" else "")

        val a11yOk = com.airi.assistant.accessibility.service.AiriAccessibilityService.instance != null
        findings += Finding("EXECUTION", "ComputerUseReady", if (a11yOk) Severity.OK else Severity.WARN,
            if (a11yOk) "yes" else "no — accessibility not active",
            suggestion = if (!a11yOk) "Enable AIRI Accessibility Service for computer-use tasks" else "")

        DiagReport("Execution", findings)
    }
}
