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
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.viewmodel.ConnectorsViewModel
import com.airi.assistant.connector.ConnectorAvailability
import com.airi.assistant.connector.ConnectorAccessProfile
import com.airi.assistant.connector.ConnectorAuthStrategies
import com.airi.assistant.ui.components.ConnectorAccessProfileDialog
import android.content.Intent
import android.net.Uri
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
    onExplain: (String) -> Unit = {},
    viewModel: ConnectorsViewModel = viewModel()
) {
    val row = viewModel.items.collectAsState().value.firstOrNull { it.meta.id == connectorId }
    val selectedProfile = viewModel.accessProfiles.collectAsState().value[connectorId]
        ?: ConnectorAccessProfile.NOT_CONFIGURED
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAccessProfileDialog by remember(connectorId) { mutableStateOf(false) }

    if (row == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val presentation = remember(row.meta) { row.meta.presentation() }
    val isConnected = row.state.connected
    val isHealthy = row.state.healthy
    val isComingSoon = row.meta.availability == ConnectorAvailability.COMING_SOON
    val authStrategy = remember(row.meta) { ConnectorAuthStrategies.forMeta(row.meta) }
    val accessOptions = remember(row.meta.id, row.meta.runtimeId) {
        viewModel.selectableAccessProfiles(connectorId)
    }
    val providerUri = remember(row.meta.website) {
        runCatching { Uri.parse(row.meta.website) }.getOrNull()?.takeIf {
            it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() && it.userInfo == null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(presentation.name) },
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
            Text(presentation.name, fontSize = 24.sp, color = AiriTheme.onSurface)
            Text(presentation.description, fontSize = 15.sp, color = AiriTheme.onSurfaceVariant)
            Text("${presentation.category} · ${presentation.version}", fontSize = 12.sp, color = CosmicAccent)
            row.meta.provider?.let { Text("Service provider: $it", fontSize = 12.sp, color = AiriTheme.onSurfaceVariant) }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("What this connector does", fontSize = 17.sp)
                    Text("${presentation.projectAccess}.", color = AiriTheme.onSurfaceVariant)
                    Text("Model dependency: ${presentation.modelDependency}.", color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth()) {
                        Text("Status", Modifier.weight(1f), color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                        Text(when {
                            isComingSoon -> stringResource(R.string.connectors_status_coming_soon)
                            isConnected && isHealthy -> stringResource(R.string.connectors_status_connected)
                            isConnected -> stringResource(R.string.connectors_status_needs_attention)
                            row.meta.availability == ConnectorAvailability.PARTIAL -> stringResource(R.string.connectors_status_configuration_required)
                            else -> stringResource(R.string.connectors_status_disconnected)
                        }, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Text("Authentication: ${row.meta.authenticationType ?: "Not declared"}", color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(authStrategy.summary, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                    if (authStrategy.requiredScopes.isNotEmpty()) {
                        Text("Requested scopes: ${authStrategy.requiredScopes.joinToString()}", color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                    }
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

            if (row.meta.capabilities.isNotEmpty()) {
                Text("Capabilities and permissions", fontSize = 17.sp)
                row.meta.capabilities.forEach { capability ->
                    Text(
                        "${capability.id} · ${capability.permission}${if (capability.requiresConfirmation) " · confirmation required" else ""}",
                        color = AiriTheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }

            providerUri?.let { uri ->
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.OpenInBrowser, contentDescription = null)
                    Spacer(Modifier.padding(3.dp))
                    Text(stringResource(R.string.connector_open_provider))
                }
            }

            if (!isComingSoon) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.connectors_access_profile_title), fontSize = 17.sp)
                        Text(
                            stringResource(R.string.connectors_access_profile_description),
                            color = AiriTheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                        val currentProfileLabel = when (selectedProfile) {
                            ConnectorAccessProfile.NOT_CONFIGURED -> R.string.connectors_access_profile_none
                            ConnectorAccessProfile.READ_ONLY -> R.string.connectors_access_profile_read_only
                            ConnectorAccessProfile.WRITE_ONLY -> R.string.connectors_access_profile_write_only
                            ConnectorAccessProfile.READ_WRITE -> R.string.connectors_access_profile_read_write
                            ConnectorAccessProfile.FULL_ACCESS -> R.string.connectors_access_profile_full
                        }
                        Text(stringResource(currentProfileLabel), color = AiriTheme.onSurface)
                        OutlinedButton(
                            onClick = { showAccessProfileDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.connectors_access_profile_change)) }
                        Text(
                            stringResource(R.string.connectors_access_profile_confirmation_note),
                            color = AiriTheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    if (isConnected) viewModel.disconnect(connectorId)
                    else if (authStrategy.officialAuthorizationRequired) onManageAuthorization(connectorId)
                    else viewModel.connect(connectorId)
                    scope.launch {
                        snackbar.showSnackbar(context.getString(if (isConnected) R.string.integration_disconnect else R.string.integration_connect))
                    }
                },
                enabled = !isComingSoon,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Spacer(Modifier.padding(3.dp))
                    Text(
                        if (isComingSoon) stringResource(R.string.connectors_status_coming_soon)
                        else stringResource(if (isConnected) R.string.integration_disconnect else R.string.integration_connect)
                    )
            }
            OutlinedButton(
                onClick = {
                    if (authStrategy.officialAuthorizationRequired && !isConnected) onManageAuthorization(connectorId)
                    else viewModel.connect(connectorId)
                    scope.launch {
                        snackbar.showSnackbar(context.getString(R.string.connectors_test_connection_started))
                    }
                },
                enabled = !isComingSoon,
                modifier = Modifier.fillMaxWidth()
            ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.padding(3.dp))
                    Text(stringResource(R.string.test_connection))
            }
            if (!isComingSoon && (authStrategy.officialAuthorizationRequired || !isConnected || !isHealthy)) {
                OutlinedButton(
                    onClick = { onManageAuthorization(connectorId) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.connectors_manage_authorization))
                }
            }
            OutlinedButton(
                onClick = { onExplain("Explain the ${row.meta.name} connector, its permissions, project use cases, and safe limitations.") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.HelpOutline, contentDescription = null)
                Spacer(Modifier.padding(3.dp))
                Text("Request an explanation")
            }
        }
    }
    if (showAccessProfileDialog) {
        ConnectorAccessProfileDialog(
            title = stringResource(R.string.connectors_access_profile_title),
            description = stringResource(R.string.connectors_access_profile_description),
            currentProfile = selectedProfile,
            availableProfiles = accessOptions,
            onConfirm = { profile ->
                viewModel.setAccessProfile(connectorId, profile)
                showAccessProfileDialog = false
            },
            onDismiss = { showAccessProfileDialog = false },
        )
    }
}
