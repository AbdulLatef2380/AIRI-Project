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
import com.airi.assistant.ai.ModelConfigManager
import com.airi.assistant.ai.PerformanceMode
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val configManager = remember { ModelConfigManager(context) }

    var currentMode by remember { mutableStateOf(configManager.getPerformanceMode()) }
    val deviceInfo = remember { collectDeviceInfo(context) }
    val perfStats  = remember { collectPerfStats(context) }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
        AnalyticsService.featureDiscovered("performance_screen")
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.performance_device), fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
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

                    Text(
                        stringResource(R.string.perf_footer_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.4f),
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
                            color    = Color.White.copy(alpha = 0.55f),
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
                Text(label, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                Text(value, color = CosmicAccent.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            if (idx < rows.lastIndex) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.04f), modifier = Modifier.padding(vertical = 2.dp))
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
    val lastLatencyMs: Long
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
        lastLoadMs    = prefs.getLong("last_model_load_ms", 0L),
        tokensPerSec  = prefs.getFloat("tokens_per_sec", 0f),
        lastLatencyMs = prefs.getLong("last_latency_ms", 0L)
    )
}

private fun Long.toMemString(): String {
    if (this <= 0L) return "0 MB"
    return if (this >= 1024L) "%.1f GB".format(this / 1024.0) else "$this MB"
}
