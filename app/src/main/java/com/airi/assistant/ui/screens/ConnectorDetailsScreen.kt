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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.viewmodel.ConnectorsViewModel
import kotlinx.coroutines.launch

private fun connectorDetailIcon(id: String) = when {
    id.contains("github") -> Icons.Outlined.Code
    id.contains("telegram") -> Icons.Outlined.Send
    id.contains("google") || id.contains("calendar") -> Icons.Outlined.Event
    id.contains("mcp") -> Icons.Outlined.Extension
    id.contains("voice") -> Icons.Outlined.Mic
    id.contains("system") -> Icons.Outlined.Memory
    else -> Icons.Outlined.Hub
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorDetailsScreen(
    connectorId: String,
    onBack: () -> Unit,
    onManageAuthorization: (String) -> Unit,
    viewModel: ConnectorsViewModel = viewModel()
) {
    val row = viewModel.items.collectAsState().value.firstOrNull { it.meta.id == connectorId }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (row == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val presentation = remember(row.meta) { row.meta.presentation() }
    val isConnected = row.state.connected
    val isHealthy = row.state.healthy

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(row.meta.name) },
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
            Icon(
                imageVector = connectorDetailIcon(row.meta.id),
                contentDescription = null,
                tint = CosmicAccent,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(row.meta.name, fontSize = 24.sp, color = AiriTheme.onSurface)
            Text(row.meta.description, fontSize = 15.sp, color = AiriTheme.onSurfaceVariant)
            Text("${presentation.category} · ${presentation.version}", fontSize = 12.sp, color = CosmicAccent)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("What this connector does", fontSize = 17.sp)
                    Text("${presentation.projectAccess}.", color = AiriTheme.onSurfaceVariant)
                    Text("Model dependency: ${presentation.modelDependency}.", color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                    Text("Status: ${if (isConnected && isHealthy) "Connected and healthy" else if (isConnected) "Connected, needs attention" else "Not connected"}", color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                    if (row.state.statusLine.isNotBlank()) Text(row.state.statusLine, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                    if (row.meta.tags.isNotEmpty()) Text("Tags: ${row.meta.tags.joinToString()}", fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                }
            }

            Text("How AIRI can use it in the project", fontSize = 17.sp)
            presentation.capabilities.forEach { capability ->
                Card(Modifier.fillMaxWidth()) {
                    Text("•  $capability", Modifier.padding(14.dp), color = AiriTheme.onSurfaceVariant)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (isConnected) viewModel.disconnect(connectorId) else viewModel.connect(connectorId)
                        scope.launch { snackbar.showSnackbar(if (isConnected) "Disconnect requested" else "Connection requested") }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Spacer(Modifier.padding(3.dp))
                    Text(if (isConnected) "Disconnect" else "Connect")
                }
                OutlinedButton(
                    onClick = {
                        viewModel.connect(connectorId)
                        scope.launch { snackbar.showSnackbar("Connection check requested") }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.padding(3.dp))
                    Text("Test connection")
                }
            }
            if (connectorId == "google" || !isConnected || !isHealthy) {
                OutlinedButton(
                    onClick = { onManageAuthorization(connectorId) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage authorization")
                }
            }
            OutlinedButton(
                onClick = { scope.launch { snackbar.showSnackbar("Explanation requested for ${row.meta.name}") } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.HelpOutline, contentDescription = null)
                Spacer(Modifier.padding(3.dp))
                Text("Request an explanation")
            }
        }
    }
}
