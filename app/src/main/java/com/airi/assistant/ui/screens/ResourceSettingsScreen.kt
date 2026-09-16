package com.airi.assistant.ui.screens

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
import androidx.compose.material3.SmallTopAppBar
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
import com.airi.assistant.resources.ResourceBudgetManager
import com.airi.assistant.resources.asResourceSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember { ResourceBudgetManager(context.applicationContext) }
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
            SmallTopAppBar(
                title = { Text("Resource customization") },
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
            Text("Choose how much AIRI may use. Storage is a real private-app budget; RAM and CPU are runtime guidance limits.", fontSize = 14.sp)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Storage")
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
                    Text("Models and saved data use this budget. Keep free space for updates and recovery.", fontSize = 12.sp)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("RAM guidance: ${ramBudget} MB")
                        Icon(Icons.Outlined.Memory, contentDescription = null)
                    }
                    Text("Device total: ${snapshot.totalRamBytes.asResourceSize()} · available: ${snapshot.availableRamBytes.asResourceSize()}")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1024, 2048, 4096, 8192).forEach { mb ->
                            Button(onClick = { ramBudget = mb; manager.setRamBudgetMb(mb); refresh() }) { Text("${mb / 1024}GB") }
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CPU guidance: $cpuBudget%")
                    Text("${snapshot.cpuCores} available processor cores. AIRI will use this value when choosing inference settings.", fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(25, 50, 75, 100).forEach { percent ->
                            Button(onClick = { cpuBudget = percent; manager.setCpuBudgetPercent(percent); refresh() }) { Text("$percent%") }
                        }
                    }
                }
            }
            if (snapshot.storageWarning || snapshot.ramWarning) {
                Text("Warning: resources are nearly exhausted. Delete unused models/data or increase the budget before continuing.")
            }
            Spacer(Modifier.height(4.dp))
            Text("The Android operating system controls actual RAM and CPU scheduling. These settings are applied as safe AIRI runtime limits, not as a reservation of system resources.", fontSize = 12.sp)
        }
    }
}
