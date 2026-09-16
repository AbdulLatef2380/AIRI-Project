package com.airi.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ai.skills.OfficialSkillLibrary
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailsScreen(skillId: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val registry = remember { SkillRegistry(context) }
    val entry = remember(skillId) { OfficialSkillLibrary.ALL.firstOrNull { it.manifest.id == skillId } }
    val manifest = entry?.manifest
    val skillInfo = remember(skillId) { registry.getAllSkillInfos().firstOrNull { it.name == skillId } }
    val available = skillInfo?.isConnected != false
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var enabled by remember(skillId) { mutableStateOf(registry.isSkillEnabled(skillId)) }

    if (manifest == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(manifest.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(manifest.iconEmoji.ifBlank { "✦" }, fontSize = 48.sp)
            Text(manifest.name, fontSize = 24.sp, color = AiriTheme.onSurface)
            Text(manifest.description, fontSize = 15.sp, color = AiriTheme.onSurfaceVariant)
            Text("Category: ${manifest.category} · v${manifest.version}", fontSize = 12.sp, color = CosmicAccent)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How this skill works", fontSize = 17.sp)
                    Text("AIRI can select this skill when your request matches its capabilities. It remains governed by permissions, connector availability, memory access, and the execution policy.", color = AiriTheme.onSurfaceVariant)
                    Text("Memory access: ${manifest.memoryAccess} · Model access: ${manifest.modelAccess}", fontSize = 12.sp)
                    if (manifest.dependencies.isNotEmpty()) {
                        Text("Requirements: ${manifest.dependencies.joinToString()}", fontSize = 12.sp)
                    }
                }
            }

            Text("Capabilities", fontSize = 17.sp)
            manifest.tools.forEach { tool ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tool.name, color = AiriTheme.onSurface)
                        Text(tool.description, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (!available) {
                            scope.launch { snackbar.showSnackbar("Connect the required service before enabling this skill") }
                        } else {
                            enabled = !enabled
                            registry.setSkillEnabled(skillId, enabled)
                            scope.launch { snackbar.showSnackbar(if (enabled) "Skill enabled" else "Skill disabled") }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Spacer(Modifier.padding(3.dp))
                    Text(if (enabled) "Enabled" else "Enable")
                }
                OutlinedButton(
                    onClick = { scope.launch { snackbar.showSnackbar("Ask AIRI to use ${manifest.name} in a new message") } },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.padding(3.dp))
                    Text("Try")
                }
            }
            OutlinedButton(
                onClick = { scope.launch { snackbar.showSnackbar("Explanation requested for ${manifest.name}") } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.HelpOutline, contentDescription = null)
                Spacer(Modifier.padding(3.dp))
                Text("Request an explanation")
            }
        }
    }
}
