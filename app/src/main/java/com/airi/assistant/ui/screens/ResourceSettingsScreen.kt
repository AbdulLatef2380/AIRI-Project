package com.airi.assistant.ui.screens

import android.app.ActivityManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import com.airi.assistant.resources.ResourceBudgetManager
import com.airi.assistant.resources.asResourceSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember { ResourceBudgetManager(context.applicationContext) }
    val memoryManager = remember { context.getSystemService(ActivityManager::class.java) }
    val memoryInfo = remember { ActivityManager.MemoryInfo().also { memoryManager?.getMemoryInfo(it) } }
    val deviceName = remember { "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim() }
    var snapshot by remember { mutableStateOf(manager.snapshot()) }
    var storageBudget by remember { mutableStateOf(manager.storageBudgetGb()) }
    var ramBudget by remember { mutableStateOf(manager.ramBudgetMb()) }
    var cpuBudget by remember { mutableStateOf(manager.cpuBudgetPercent()) }

    fun refresh() {
        snapshot = manager.snapshot()
        manager.notifyIfNeeded(snapshot)
    }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.resource_customization_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(deviceName, fontSize = 18.sp)
            Text(stringResource(R.string.resource_warning_targets_desc), fontSize = 14.sp)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.resource_storage))
                        Icon(Icons.Outlined.Storage, contentDescription = null)
                    }
                    Text("${snapshot.storageUsedBytes.asResourceSize()} used of ${snapshot.storageBudgetBytes.asResourceSize()} (${snapshot.storagePercent}%)")
                    LinearProgressIndicator(
                        progress = { (snapshot.storagePercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 4, 8).forEach { gb ->
                            Button(onClick = { storageBudget = gb; manager.setStorageBudgetGb(gb); refresh() }) { Text("${gb}GB") }
                        }
                    }
                    Text(stringResource(R.string.resource_data_desc), fontSize = 12.sp)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.resource_ram_target, ramBudget))
                        Icon(Icons.Outlined.Memory, contentDescription = null)
                    }
                    Text(stringResource(R.string.resource_device_total, snapshot.totalRamBytes.asResourceSize(), snapshot.availableRamBytes.asResourceSize()))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1024L, 2048L, 4096L, (memoryInfo.totalMem / (1024L * 1024L)).coerceAtLeast(1024L))
                            .distinct().sorted().forEach { mb ->
                            Button(onClick = { ramBudget = mb.toInt(); manager.setRamBudgetMb(mb.toInt()); refresh() }) { Text("${mb / 1024}GB") }
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.resource_cpu_target, cpuBudget))
                    Text("${snapshot.cpuCores} available processor cores. This value is guidance for AIRI runtime choices; Android still controls scheduling.", fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(25, 50, 75, 100).forEach { percent ->
                            Button(onClick = { cpuBudget = percent; manager.setCpuBudgetPercent(percent); refresh() }) { Text("$percent%") }
                        }
                    }
                }
            }
            if (snapshot.storageWarning || snapshot.ramWarning) {
                Text(stringResource(R.string.resource_nearly_exhausted))
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.resource_runtime_limits), fontSize = 12.sp)
        }
    }
}
