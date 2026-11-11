package com.airi.assistant.ui.screens

import android.app.ActivityManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import com.airi.assistant.resources.*
import com.airi.assistant.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember { ResourceBudgetManager(context.applicationContext) }
    var snapshot by remember { mutableStateOf(manager.snapshot()) }
    var storageBudget by remember { mutableStateOf(manager.storageBudgetGb()) }
    var profile by remember { mutableStateOf("balanced") }
    fun refresh() { snapshot = manager.snapshot(); manager.notifyIfNeeded(snapshot) }
    LaunchedEffect(Unit) { refresh() }
    val statusColor = when { snapshot.deviceStorageWarning || snapshot.ramWarning -> SemanticError; snapshot.storageWarning -> SemanticWarn; else -> SemanticSuccess }
    Scaffold(containerColor = AiriTheme.background, topBar = {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background), navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, stringResource(R.string.back), tint = AiriTheme.onBackground) } }, title = { Text(stringResource(R.string.resource_customization_title), color = AiriTheme.onBackground, fontWeight = FontWeight.Bold) })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ResourceHero(snapshot, statusColor)
            DeviceProfile(snapshot)
            StorageDashboard(snapshot, storageBudget) { value -> storageBudget = value; manager.setStorageBudgetGb(value); refresh() }
            ResourceBreakdown(snapshot)
            MemoryPolicy(snapshot, profile) { profile = it }
            Text(stringResource(R.string.resource_runtime_limits), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable private fun ResourceHero(s: ResourceBudgetManager.Snapshot, status: Color) {
    Surface(shape = AIRIShapes.xl, color = AiriTheme.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.background(Color.Transparent).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { Text("✦", color = CosmicAccent, fontSize = 28.sp); Text(stringResource(R.string.resource_center), color = AiriTheme.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(stringResource(R.string.resource_center_desc), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp) }
                Surface(shape = AIRIShapes.pill, color = status.copy(alpha = .14f)) { Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.CheckCircle, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(if (status == SemanticSuccess) stringResource(R.string.resource_healthy) else stringResource(R.string.resource_attention), color = status, fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MetricTile("AIRI", s.storageUsedBytes.asResourceSize(), stringResource(R.string.resource_managed), Modifier.weight(1f)); MetricTile("Device", s.deviceFreeBytes.asResourceSize(), stringResource(R.string.resource_free), Modifier.weight(1f)); MetricTile("RAM", s.processPssBytes.asResourceSize(), stringResource(R.string.resource_process_pss), Modifier.weight(1f)) }
        }
    }
}

@Composable private fun MetricTile(title: String, value: String, caption: String, modifier: Modifier = Modifier) { Column(modifier.background(AiriTheme.onSurface.copy(.05f), AIRIShapes.md).padding(10.dp)) { Text(title, color = AiriTheme.onSurfaceVariant, fontSize = 10.sp); Text(value, color = AiriTheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text(caption, color = AiriTheme.onSurfaceVariant, fontSize = 9.sp) } }

@Composable private fun DeviceProfile(s: ResourceBudgetManager.Snapshot) {
    ResourceSection(Icons.Outlined.PhoneAndroid, stringResource(R.string.resource_device_profile)) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { ResourceLine("Android", "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}"); ResourceLine("CPU", "${s.cpuCores} cores · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI"}"); ResourceLine("Storage", "${s.deviceTotalBytes.asResourceSize()} total · ${s.deviceFreeBytes.asResourceSize()} available"); ResourceLine("Memory", "${s.totalRamBytes.asResourceSize()} total · ${s.availableRamBytes.asResourceSize()} available") } }
}

@Composable private fun StorageDashboard(s: ResourceBudgetManager.Snapshot, budget: Int, onBudget: (Int) -> Unit) {
    ResourceSection(Icons.Outlined.Storage, stringResource(R.string.resource_storage_budget)) {
        Text(stringResource(R.string.resource_using, s.storageUsedBytes.asResourceSize(), s.storageBudgetBytes.asResourceSize()), color = AiriTheme.onSurface, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator({ (s.storagePercent / 100f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(9.dp), color = if (s.storageWarning) SemanticWarn else CosmicAccent, trackColor = AiriTheme.outline.copy(.18f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.resource_remaining, s.storageFreeBytes.asResourceSize()), color = AiriTheme.onSurfaceVariant, fontSize = 11.sp); Text(stringResource(R.string.resource_device_free, s.deviceFreeBytes.asResourceSize()), color = AiriTheme.onSurfaceVariant, fontSize = 11.sp) }
        Text(stringResource(R.string.resource_budget_note), color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(2,4,8,16,32,64,128).filter { it <= s.deviceFreeBytes / (1024L*1024*1024) }.forEach { gb -> FilterChip(selected = budget == gb, onClick = { onBudget(gb) }, label = { Text("${gb} GB") }) } }
    }
}

@Composable private fun ResourceBreakdown(s: ResourceBudgetManager.Snapshot) {
    ResourceSection(Icons.Outlined.DonutLarge, stringResource(R.string.resource_breakdown)) { if (s.categories.isEmpty()) Text(stringResource(R.string.resource_no_files), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp) else s.categories.take(8).forEach { item -> Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).background(CosmicAccent, RoundedCornerShape(5.dp))); Spacer(Modifier.width(8.dp)); Text(item.category.label, color = AiriTheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(item.sizeBytes.asResourceSize(), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp); Text("${item.itemCount}", color = AiriTheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp)) } } }
}

@Composable private fun MemoryPolicy(s: ResourceBudgetManager.Snapshot, profile: String, onProfile: (String) -> Unit) {
    ResourceSection(Icons.Outlined.Memory, stringResource(R.string.resource_memory_target)) { Text(stringResource(R.string.resource_observed_pss, s.processPssBytes.asResourceSize()), color = AiriTheme.onSurface, fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.resource_profile_note), color = AiriTheme.onSurfaceVariant, fontSize = 11.sp); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("eco", "balanced", "performance").forEach { key -> FilterChip(selected = profile == key, onClick = { onProfile(key) }, label = { Text(key.replaceFirstChar { it.uppercase() }) }) } } }
}

@Composable private fun ResourceSection(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) { Surface(shape = AIRIShapes.xl, color = AiriTheme.surface, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(20.dp)); Text(title, color = AiriTheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp) }; content() } } }
@Composable private fun ResourceLine(label: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp); Text(value, color = AiriTheme.onSurface, fontSize = 12.sp) } }
