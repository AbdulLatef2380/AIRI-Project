package com.airi.assistant.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ai.LlamaNative
import com.airi.assistant.ai.ModelConfigManager
import com.airi.assistant.ai.ModelManager
import com.airi.assistant.ai.PerformanceMode
import com.airi.assistant.ai.SpeculativeManager
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenModelPerformance: () -> Unit = {}
) {
    val context = LocalContext.current
    val configManager = remember { ModelConfigManager(context) }

    var currentMode by remember { mutableStateOf(configManager.getPerformanceMode()) }
    val deviceInfo = remember { collectDeviceInfo(context) }
    val perfStats  = remember { collectPerfStats(context) }

    // ── Runtime Diagnostics state — collected once, driven by ViewModel StateFlows
    val diagnostics by viewModel.runtimeDiagnostics.collectAsState()
    val runtimeEvents by viewModel.runtimeEventLog.collectAsState()

    // ── Execution Mode state ──────────────────────────────────────────────────
    val executionMode    by viewModel.executionMode.collectAsState()
    val execPrefs        = remember { viewModel.getExecModePrefs() }
    var privacyLevel     by remember { mutableStateOf(execPrefs.privacyLevel) }
    var internetGranted  by remember { mutableStateOf(execPrefs.internetPermissionGranted) }
    var offlineFallback  by remember { mutableStateOf(execPrefs.offlineFallbackEnabled) }
    var preferredProvider by remember { mutableStateOf(execPrefs.preferredProvider) }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
        // Trigger a one-shot fresh snapshot so the diagnostics panel
        // shows live data the moment the screen is opened.
        viewModel.onDiagnosticsScreenVisible()
        AnalyticsService.featureDiscovered("performance_screen")
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.performance_device), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.65f))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(300)) + expandVertically(tween(300))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                    PerformanceModeCard(
                        currentMode = currentMode,
                        onModeSelected = { mode ->
                            currentMode = mode
                            configManager.setPerformanceMode(mode)
                            viewModel.setPerformanceMode(mode)
                            AnalyticsService.featureDiscovered("performance_mode_${mode.name.lowercase()}")
                        }
                    )

                    // ── Live Runtime Diagnostics ──────────────────────────────
                    // Backed by ViewModel-owned StateFlows — no polling here.
                    // Snapshots are emitted at lifecycle boundaries only
                    // (model load, generation start/end, supervisor override).
                    RuntimeStatusPanel(diagnostics = diagnostics)
                    RuntimeWarningsPanel(warnings = diagnostics.warnings)

                    // ── Execution Mode Panel ──────────────────────────────────
                    ExecutionModePanel(
                        currentMode           = executionMode,
                        currentPrivacy        = privacyLevel,
                        internetGranted       = internetGranted,
                        offlineFallback       = offlineFallback,
                        preferredProvider     = preferredProvider,
                        cloudTokensUsed       = execPrefs.cloudTokensUsedToday,
                        cloudTokensCap        = execPrefs.maxDailyCloudTokens,
                        onModeChange          = { viewModel.setExecutionMode(it) },
                        onPrivacyChange       = { privacyLevel = it; viewModel.setPrivacyLevel(it) },
                        onInternetPermChange  = { internetGranted = it; viewModel.grantInternetPermission(it) },
                        onOfflineFallbackChange = { offlineFallback = it; execPrefs.offlineFallbackEnabled = it },
                        onProviderChange      = { preferredProvider = it; execPrefs.preferredProvider = it }
                    )

                    PerfStatCard(
                        title = stringResource(R.string.stat_device_info),
                        icon  = Icons.Outlined.PhoneAndroid,
                        rows  = listOf(
                            stringResource(R.string.stat_device)    to "${Build.MANUFACTURER} ${Build.MODEL}",
                            stringResource(R.string.stat_android)   to "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
                            stringResource(R.string.stat_cpu_abi)   to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
                            stringResource(R.string.stat_cpu_cores) to "${Runtime.getRuntime().availableProcessors()} cores"
                        )
                    )

                    PerfStatCard(
                        title = stringResource(R.string.stat_memory),
                        icon  = Icons.Outlined.Memory,
                        rows  = listOf(
                            stringResource(R.string.stat_total_ram)   to deviceInfo.totalRamMb.toMemString(),
                            stringResource(R.string.stat_available_ram) to deviceInfo.availRamMb.toMemString(),
                            stringResource(R.string.stat_used_ram)    to (deviceInfo.totalRamMb - deviceInfo.availRamMb).toMemString(),
                            stringResource(R.string.stat_low_memory)  to if (deviceInfo.isLowMemory) stringResource(R.string.stat_low_memory_yes) else stringResource(R.string.stat_low_memory_no)
                        )
                    )

                    PerfStatCard(
                        title = stringResource(R.string.stat_storage),
                        icon  = Icons.Outlined.Storage,
                        rows  = listOf(
                            stringResource(R.string.stat_total_storage) to deviceInfo.totalStorageMb.toMemString(),
                            stringResource(R.string.stat_free_storage)  to deviceInfo.freeStorageMb.toMemString()
                        )
                    )

                    PerfStatCard(
                        title = stringResource(R.string.stat_inference),
                        icon  = Icons.Outlined.Speed,
                        rows  = listOf(
                            stringResource(R.string.stat_last_load_time) to if (perfStats.lastLoadMs > 0) "${perfStats.lastLoadMs} ms" else "—",
                            stringResource(R.string.stat_tokens_per_sec) to if (perfStats.tokensPerSec > 0f) "%.1f t/s".format(perfStats.tokensPerSec) else "—",
                            stringResource(R.string.stat_last_latency)   to if (perfStats.lastLatencyMs > 0) "${perfStats.lastLatencyMs} ms" else "—"
                        )
                    )

                    // ── Detailed latency breakdown (sourced from native bridge)
                    val kvPct = if (perfStats.nCtx > 0)
                        ((100L * perfStats.nPast) / perfStats.nCtx).toInt() else 0
                    PerfStatCard(
                        title = stringResource(R.string.stat_breakdown),
                        icon  = Icons.Outlined.Timeline,
                        rows  = listOf(
                            stringResource(R.string.stat_breakdown_tokenize)    to if (perfStats.tokenizeMs    > 0) "${perfStats.tokenizeMs} ms"    else "—",
                            stringResource(R.string.stat_breakdown_prefill)     to if (perfStats.prefillMs     > 0) "${perfStats.prefillMs} ms"     else "—",
                            stringResource(R.string.stat_breakdown_first_token) to if (perfStats.firstTokenMs  > 0) "${perfStats.firstTokenMs} ms"  else "—",
                            stringResource(R.string.stat_breakdown_decode)      to if (perfStats.decodeMs      > 0) "${perfStats.decodeMs} ms"      else "—",
                            stringResource(R.string.stat_breakdown_decoded)     to if (perfStats.decodedTokens > 0) "${perfStats.decodedTokens}"    else "—",
                            stringResource(R.string.stat_breakdown_kv)          to if (perfStats.nCtx > 0)
                                stringResource(R.string.stat_breakdown_kv_value, perfStats.nPast, perfStats.nCtx, kvPct)
                                else "—"
                        )
                    )

                    // Speculative decoding controls (optional, opt-in).
                    SpecDecodingCard()

                    // ── Collapsible diagnostics panels ────────────────────────
                    RuntimeEventTimeline(events = runtimeEvents)
                    AdvancedDiagnosticsSection(diagnostics = diagnostics)

                    // Entry point to the empirical per-quantization comparison screen.
                    OutlinedButton(
                        onClick = onOpenModelPerformance,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Insights, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.model_perf_open))
                    }

                    Text(
                        stringResource(R.string.perf_footer_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = AiriTheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PerformanceModeCard(
    currentMode: PerformanceMode,
    onModeSelected: (PerformanceMode) -> Unit
) {
    SettingsSurface {
        SettingsCategoryHeader(icon = Icons.Outlined.Tune, title = stringResource(R.string.performance_mode))
        Spacer(Modifier.height(12.dp))

        PerformanceMode.values().forEach { mode ->
            val selected = mode == currentMode
            Surface(
                onClick    = { onModeSelected(mode) },
                shape      = RoundedCornerShape(14.dp),
                color      = if (selected) CosmicAccent.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f),
                border     = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) CosmicAccent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f)
                ),
                modifier   = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        onClick  = { onModeSelected(mode) },
                        colors   = RadioButtonDefaults.colors(selectedColor = CosmicAccent)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            mode.label,
                            fontWeight = FontWeight.Bold,
                            color      = if (selected) CosmicAccent else Color.White,
                            fontSize   = 14.sp
                        )
                        Text(
                            mode.description,
                            color    = AiriTheme.onBackground.copy(alpha = 0.55f),
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        when (mode) {
                            PerformanceMode.FAST     -> stringResource(R.string.perf_mode_label_fast)
                            PerformanceMode.BALANCED -> stringResource(R.string.perf_mode_label_balanced)
                            PerformanceMode.QUALITY  -> stringResource(R.string.perf_mode_label_quality)
                        },
                        color    = if (selected) CosmicAccent else Color.White.copy(alpha = 0.3f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (mode != PerformanceMode.values().last()) Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun PerfStatCard(
    title: String,
    icon: ImageVector,
    rows: List<Pair<String, String>>
) {
    SettingsSurface {
        SettingsCategoryHeader(icon = icon, title = title)
        Spacer(Modifier.height(10.dp))
        rows.forEachIndexed { idx, (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(label, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                Text(value, color = CosmicAccent.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            if (idx < rows.lastIndex) {
                Divider(color = AiriTheme.outline.copy(alpha = 0.04f), modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

private data class DeviceInfo(
    val totalRamMb: Long,
    val availRamMb: Long,
    val totalStorageMb: Long,
    val freeStorageMb: Long,
    val isLowMemory: Boolean
)

private data class PerfStats(
    val lastLoadMs: Long,
    val tokensPerSec: Float,
    val lastLatencyMs: Long,
    val tokenizeMs: Long,
    val prefillMs: Long,
    val firstTokenMs: Long,
    val decodeMs: Long,
    val decodedTokens: Int,
    val nPast: Int,
    val nCtx: Int
)

private fun collectDeviceInfo(context: Context): DeviceInfo {
    val am   = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val stat = StatFs(Environment.getDataDirectory().absolutePath)
    val blockSize  = stat.blockSizeLong
    return DeviceInfo(
        totalRamMb     = info.totalMem    / (1024L * 1024L),
        availRamMb     = info.availMem    / (1024L * 1024L),
        totalStorageMb = stat.blockCountLong * blockSize / (1024L * 1024L),
        freeStorageMb  = stat.availableBlocksLong * blockSize / (1024L * 1024L),
        isLowMemory    = info.lowMemory
    )
}

private fun collectPerfStats(context: Context): PerfStats {
    val prefs = context.getSharedPreferences("airi_perf_stats", Context.MODE_PRIVATE)
    return PerfStats(
        lastLoadMs    = prefs.getLong ("last_model_load_ms", 0L),
        tokensPerSec  = prefs.getFloat("tokens_per_sec",     0f),
        lastLatencyMs = prefs.getLong ("last_latency_ms",    0L),
        tokenizeMs    = prefs.getLong ("last_tokenize_ms",   0L),
        prefillMs     = prefs.getLong ("last_prefill_ms",    0L),
        firstTokenMs  = prefs.getLong ("last_first_tok_ms",  0L),
        decodeMs      = prefs.getLong ("last_decode_ms",     0L),
        decodedTokens = prefs.getInt  ("last_decoded_toks",  0),
        nPast         = prefs.getInt  ("last_n_past",        0),
        nCtx          = prefs.getInt  ("last_n_ctx",         0)
    )
}

private fun Long.toMemString(): String {
    if (this <= 0L) return "0 MB"
    return if (this >= 1024L) "%.1f GB".format(this / 1024.0) else "$this MB"
}

// ───────────────────────────────────────────────────────────────────────────────
// Speculative decoding card
//
// Owns the user-facing toggle, draft picker, status line, and live acceptance
// rate readout. Every interaction goes through SpeculativeManager — this
// composable holds NO direct native handles, so any failure path (vocab
// mismatch, file missing, OOM) surfaces as a status string and never crashes
// the UI.
// ───────────────────────────────────────────────────────────────────────────────
@Composable
private fun SpecDecodingCard() {
    val context = LocalContext.current
    val mgr = remember { SpeculativeManager(context) }

    var enabled    by remember { mutableStateOf(mgr.isEnabled()) }
    var draftPath  by remember { mutableStateOf(mgr.getDraftPath()) }
    var loadStatus by remember { mutableStateOf<String?>(null) }
    var draftLoaded by remember { mutableStateOf(false) }
    var stats      by remember { mutableStateOf(mgr.stats()) }

    // Try to load the draft whenever the toggle or selected path changes,
    // and refresh the stats counters every couple of seconds while visible.
    LaunchedEffect(enabled, draftPath) {
        loadStatus  = mgr.ensureLoaded()
        draftLoaded = runCatching { LlamaNative.isDraftLoaded() }.getOrDefault(false)
    }
    LaunchedEffect(Unit) {
        while (true) {
            stats       = mgr.stats()
            draftLoaded = runCatching { LlamaNative.isDraftLoaded() }.getOrDefault(false)
            kotlinx.coroutines.delay(2000)
        }
    }

    SettingsSurface {
        SettingsCategoryHeader(
            icon  = Icons.Outlined.FlashOn,
            title = stringResource(R.string.spec_title)
        )
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.spec_subtitle),
            color = AiriTheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.spec_enable),
                color = AiriTheme.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    mgr.setEnabled(it)
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        // Status line: tells the user what state speculative is actually in.
        val statusText = when {
            !enabled -> stringResource(R.string.spec_status_off)
            draftPath.isNullOrBlank() -> stringResource(R.string.spec_status_no_draft)
            loadStatus == "DRAFT_OK" || draftLoaded -> {
                val name = draftPath?.substringAfterLast('/') ?: "?"
                val sz   = runCatching { java.io.File(draftPath!!).length() }.getOrDefault(0L)
                stringResource(R.string.spec_status_loaded, name, sz / (1024L * 1024L))
            }
            loadStatus != null -> stringResource(R.string.spec_status_load_failed, loadStatus!!)
            else -> stringResource(R.string.spec_status_no_draft)
        }
        Text(statusText, color = AiriTheme.onBackground.copy(alpha = 0.75f), fontSize = 12.sp)

        Spacer(Modifier.height(8.dp))

        // Acceptance rate.
        val accText = if (stats.drafted > 0) {
            stringResource(
                R.string.spec_acceptance,
                stats.acceptancePct,
                stats.accepted.toInt(),
                stats.drafted.toInt(),
                stats.runs.toInt()
            )
        } else {
            stringResource(R.string.spec_acceptance_empty)
        }
        Text(accText, color = CosmicAccent.copy(alpha = 0.9f), fontSize = 12.sp,
             fontWeight = FontWeight.Medium)

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val current = ModelManager.getCurrent()?.path
                    val candidates = ModelManager.getAllModels()
                    val picked = mgr.autoPickDraft(candidates, current)
                    draftPath = picked?.path
                    if (picked == null) {
                        loadStatus = "NO_CANDIDATE"
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.spec_auto_pick), fontSize = 12.sp) }

            OutlinedButton(
                onClick = {
                    runCatching { LlamaNative.unloadDraftModel() }
                    mgr.setDraftPath(null)
                    draftPath  = null
                    loadStatus = null
                    draftLoaded = false
                },
                enabled = draftLoaded || !draftPath.isNullOrBlank(),
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.spec_unload), fontSize = 12.sp) }
        }

        Spacer(Modifier.height(6.dp))

        OutlinedButton(
            onClick = { mgr.resetStats(); stats = mgr.stats() },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.spec_reset_stats), fontSize = 12.sp) }

        // Special-case: NO_CANDIDATE comes from autoPickDraft, not native.
        if (loadStatus == "NO_CANDIDATE") {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.spec_status_no_candidate),
                color = Color(0xFFFF8A65),
                fontSize = 12.sp
            )
        }
    }
}
