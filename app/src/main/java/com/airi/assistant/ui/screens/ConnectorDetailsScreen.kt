package com.airi.assistant.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.airi.assistant.R
import com.airi.assistant.connector.ConnectorAccessProfile
import com.airi.assistant.connector.ConnectorAuthMode
import com.airi.assistant.connector.ConnectorAuthStrategies
import com.airi.assistant.connector.ConnectorAvailability
import com.airi.assistant.connector.ConnectorPermissionLevel
import com.airi.assistant.ui.components.ConnectorAccessProfileDialog
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.viewmodel.ConnectorsViewModel
import kotlinx.coroutines.launch

private fun connectorDetailIcon(id: String) = when {
    id.contains("github") -> Icons.Outlined.Verified
    id.contains("google") || id.contains("calendar") -> Icons.Outlined.Link
    id.contains("mcp") -> Icons.Outlined.Settings
    else -> Icons.Outlined.Security
}

private fun safeHttpsUri(value: String?): Uri? = value?.let { raw ->
    runCatching { Uri.parse(raw) }.getOrNull()?.takeIf {
        it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() && it.userInfo == null
    }
}

private enum class DetailConnectionState { CONNECTED, NEEDS_ATTENTION, AUTHORIZATION_REQUIRED, CONFIGURATION_REQUIRED, DISCONNECTED, COMING_SOON }

private fun detailConnectionState(
    availability: ConnectorAvailability,
    connected: Boolean,
    healthy: Boolean,
    authMode: ConnectorAuthMode,
): DetailConnectionState = when {
    availability == ConnectorAvailability.COMING_SOON || authMode == ConnectorAuthMode.COMING_SOON -> DetailConnectionState.COMING_SOON
    connected && healthy -> DetailConnectionState.CONNECTED
    connected -> DetailConnectionState.NEEDS_ATTENTION
    availability == ConnectorAvailability.PARTIAL && authMode in setOf(
        ConnectorAuthMode.MCP_CONFIGURATION,
        ConnectorAuthMode.OAUTH2_CONFIDENTIAL,
    ) -> DetailConnectionState.CONFIGURATION_REQUIRED
    authMode != ConnectorAuthMode.NONE -> DetailConnectionState.AUTHORIZATION_REQUIRED
    else -> DetailConnectionState.DISCONNECTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorDetailsScreen(
    connectorId: String,
    onBack: () -> Unit,
    onManageAuthorization: (String) -> Unit,
    onExplain: (String) -> Unit = {},
    viewModel: ConnectorsViewModel = viewModel(),
) {
    val row = viewModel.items.collectAsState().value.firstOrNull { it.meta.id == connectorId }
    val profiles by viewModel.accessProfiles.collectAsState()
    val selectedProfile = profiles[connectorId] ?: ConnectorAccessProfile.NOT_CONFIGURED
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAccessProfileDialog by remember(connectorId) { mutableStateOf(false) }

    if (row == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val meta = row.meta
    val definition = remember(meta.id) { com.airi.assistant.connector.OfficialConnectorCatalog.get(meta.id) }
    val presentation = remember(meta) { meta.presentation() }
    val authStrategy = remember(meta) { ConnectorAuthStrategies.forMeta(meta) }
    val state = detailConnectionState(meta.availability, row.state.connected, row.state.healthy, authStrategy.mode)
    val isConnected = state == DetailConnectionState.CONNECTED || state == DetailConnectionState.NEEDS_ATTENTION
    val accessOptions = remember(meta.id, meta.runtimeId) { viewModel.selectableAccessProfiles(connectorId) }
    val websiteUri = safeHttpsUri(meta.website)
    val privacyUri = safeHttpsUri(meta.privacyPolicyUrl)
    val docsUri = safeHttpsUri(meta.documentationUrl)
    val iconUri = safeHttpsUri(meta.iconUrl)

    Scaffold(
        containerColor = AiriTheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(meta.name, maxLines = 1, color = AiriTheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = CosmicAccent.copy(alpha = 0.08f),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(modifier = Modifier.size(64.dp).clip(CircleShape), color = CosmicAccent.copy(alpha = 0.18f)) {
                        if (iconUri != null) {
                            AsyncImage(model = iconUri, contentDescription = "", modifier = Modifier.padding(12.dp))
                        } else {
                            Icon(connectorDetailIcon(meta.id), contentDescription = null, tint = CosmicAccent, modifier = Modifier.padding(18.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(meta.name, color = AiriTheme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(meta.provider ?: presentation.category, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                        Text(presentation.description, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }

            ConnectionStateBanner(state = state, runtimeStatus = row.state.statusLine, error = row.state.errorMessage)

            DetailSection(title = "About this connector") {
                Text(definition?.longDescription?.ifBlank { meta.description } ?: meta.description, color = AiriTheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
                if (presentation.capabilities.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    presentation.capabilities.forEach { capability ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(17.dp))
                            Text(capability, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                }
            }

            DetailSection(title = "Connector information") {
                DetailRow("Type", meta.type.name)
                DetailRow("Provider", meta.provider)
                DetailRow("Authentication", authStrategy.mode.displayLabel())
                DetailRow("Status", state.displayLabel())
                DetailRow("Runtime", meta.runtimeId)
                if (definition?.supportedPlatforms?.isNotEmpty() == true) DetailRow("Platforms", definition.supportedPlatforms.joinToString())
                if (definition?.version?.isNotBlank() == true) DetailRow("Version", definition.version)
            }

            if (meta.capabilities.isNotEmpty()) {
                DetailSection(title = "Capabilities and permissions") {
                    meta.capabilities.forEach { capability ->
                        val active = row.state.connected && row.state.healthy && !capability.requiresConfirmation
                        Surface(color = if (active) Color(0xFF173B2A) else AiriTheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(if (active) Icons.Outlined.Verified else Icons.Outlined.WarningAmber, contentDescription = null, tint = if (active) Color(0xFF63D695) else AiriTheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(capability.id, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(capability.description, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                                    Text(if (active) "Enabled after runtime verification" else if (capability.requiresConfirmation) "Requires confirmation at execution" else "Available after authorization", color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
                                }
                                Text(capability.permission.name, color = CosmicAccent, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            if (authStrategy.requiredScopes.isNotEmpty()) {
                DetailSection(title = "Required permissions") {
                    authStrategy.requiredScopes.forEach { scopeName -> Text("• $scopeName", color = AiriTheme.onSurfaceVariant, fontSize = 12.sp) }
                    Text("Only these declared scopes are requested by this connector surface.", color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }

            if (authStrategy.steps.isNotEmpty()) {
                DetailSection(title = "Connection steps") {
                    authStrategy.steps.forEachIndexed { index, step -> Text("${index + 1}. $step", color = AiriTheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp) }
                }
            }

            val limitations = definition?.limitations.orEmpty().distinct()
            if (limitations.isNotEmpty()) {
                DetailSection(title = "Requirements and limitations") {
                    limitations.forEach { Text("• $it", color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp) }
                }
            }

            if (definition?.supportedActions?.isNotEmpty() == true || definition?.supportedTriggers?.isNotEmpty() == true) {
                DetailSection(title = "Declared runtime surface") {
                    if (definition.supportedActions.isNotEmpty()) DetailRow("Actions", definition.supportedActions.joinToString())
                    if (definition.supportedTriggers.isNotEmpty()) DetailRow("Triggers", definition.supportedTriggers.joinToString())
                }
            }

            LinkButton("Official website", websiteUri)
            LinkButton("Documentation", docsUri)
            LinkButton("Privacy policy", privacyUri)

            if (state != DetailConnectionState.COMING_SOON) {
                DetailSection(title = stringResource(R.string.connectors_access_profile_title)) {
                    Text(stringResource(R.string.connectors_access_profile_description), color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(accessProfileLabel(selectedProfile), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { showAccessProfileDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.connectors_access_profile_change))
                    }
                }
            }

            Button(
                onClick = {
                    when {
                        state == DetailConnectionState.CONNECTED || state == DetailConnectionState.NEEDS_ATTENTION -> viewModel.disconnect(connectorId)
                        authStrategy.officialAuthorizationRequired -> onManageAuthorization(connectorId)
                        else -> viewModel.connect(connectorId)
                    }
                    scope.launch { snackbar.showSnackbar(primaryActionLabel(state, authStrategy)) }
                },
                enabled = state != DetailConnectionState.COMING_SOON,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Icon(if (isConnected) Icons.Outlined.Link else Icons.Outlined.CheckCircle, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(primaryActionLabel(state, authStrategy), fontWeight = FontWeight.Bold)
            }

            if (state == DetailConnectionState.NEEDS_ATTENTION || state == DetailConnectionState.AUTHORIZATION_REQUIRED || state == DetailConnectionState.CONFIGURATION_REQUIRED) {
                OutlinedButton(onClick = { onManageAuthorization(connectorId) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Reconnect or configure")
                }
            }
            OutlinedButton(onClick = { onExplain("Explain the ${meta.name} connector using only its declared capabilities, permissions, authentication strategy, and limitations.") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.HelpOutline, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Explain safely")
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showAccessProfileDialog) {
        ConnectorAccessProfileDialog(
            title = stringResource(R.string.connectors_access_profile_title),
            description = stringResource(R.string.connectors_access_profile_description),
            currentProfile = selectedProfile,
            availableProfiles = accessOptions,
            onConfirm = { profile -> viewModel.setAccessProfile(connectorId, profile); showAccessProfileDialog = false },
            onDismiss = { showAccessProfileDialog = false },
        )
    }
}

@Composable
private fun ConnectionStateBanner(state: DetailConnectionState, runtimeStatus: String, error: String?) {
    val (label, color, icon) = when (state) {
        DetailConnectionState.CONNECTED -> Triple("Connected and verified", Color(0xFF63D695), Icons.Outlined.Verified)
        DetailConnectionState.NEEDS_ATTENTION -> Triple("Connected but needs attention", Color(0xFFFFC857), Icons.Outlined.WarningAmber)
        DetailConnectionState.AUTHORIZATION_REQUIRED -> Triple("Authorization required", CosmicAccent, Icons.Outlined.Security)
        DetailConnectionState.CONFIGURATION_REQUIRED -> Triple("Configuration required", CosmicAccent, Icons.Outlined.Settings)
        DetailConnectionState.DISCONNECTED -> Triple("Not connected", AiriTheme.onSurfaceVariant, Icons.Outlined.Link)
        DetailConnectionState.COMING_SOON -> Triple("Coming soon", AiriTheme.onSurfaceVariant, Icons.Outlined.ErrorOutline)
    }
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Column {
                Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (runtimeStatus.isNotBlank()) Text(runtimeStatus, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                if (!error.isNullOrBlank()) Text(error, color = Color(0xFFFF8A80), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, color = AiriTheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(color = AiriTheme.outline.copy(alpha = 0.35f))
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(0.38f))
        Text(value, color = AiriTheme.onBackground, fontSize = 12.sp, modifier = Modifier.weight(0.62f))
    }
}

@Composable
private fun LinkButton(label: String, uri: Uri?) {
    val context = LocalContext.current
    if (uri == null) return
    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.OpenInBrowser, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

private fun ConnectorAuthMode.displayLabel(): String = when (this) {
    ConnectorAuthMode.OAUTH2_PKCE -> "OAuth 2.0 with PKCE"
    ConnectorAuthMode.OAUTH2_CONFIDENTIAL -> "OAuth 2.0 (server exchange)"
    ConnectorAuthMode.API_KEY -> "API key"
    ConnectorAuthMode.PERSONAL_ACCESS_TOKEN -> "Personal access token"
    ConnectorAuthMode.DEVICE_CODE -> "Device code"
    ConnectorAuthMode.LOCAL_PERMISSION -> "Android permission"
    ConnectorAuthMode.MCP_CONFIGURATION -> "MCP configuration"
    ConnectorAuthMode.WEBHOOK -> "Webhook"
    ConnectorAuthMode.NONE -> "None"
    ConnectorAuthMode.COMING_SOON -> "Not available yet"
}

private fun DetailConnectionState.displayLabel(): String = when (this) {
    DetailConnectionState.CONNECTED -> "Connected and verified"
    DetailConnectionState.NEEDS_ATTENTION -> "Needs attention"
    DetailConnectionState.AUTHORIZATION_REQUIRED -> "Authorization required"
    DetailConnectionState.CONFIGURATION_REQUIRED -> "Configuration required"
    DetailConnectionState.DISCONNECTED -> "Disconnected"
    DetailConnectionState.COMING_SOON -> "Coming soon"
}

private fun primaryActionLabel(state: DetailConnectionState, strategy: com.airi.assistant.connector.ConnectorAuthStrategy): String = when (state) {
    DetailConnectionState.CONNECTED -> "Disconnect"
    DetailConnectionState.NEEDS_ATTENTION -> "Reconnect"
    DetailConnectionState.AUTHORIZATION_REQUIRED -> if (strategy.mode == ConnectorAuthMode.MCP_CONFIGURATION) "Configure" else "Authorize"
    DetailConnectionState.CONFIGURATION_REQUIRED -> "Configure"
    DetailConnectionState.DISCONNECTED -> if (strategy.mode == ConnectorAuthMode.MCP_CONFIGURATION) "Configure" else "Connect"
    DetailConnectionState.COMING_SOON -> "Coming soon"
}

private fun accessProfileLabel(profile: ConnectorAccessProfile): String = when (profile) {
    ConnectorAccessProfile.NOT_CONFIGURED -> "Not configured"
    ConnectorAccessProfile.READ_ONLY -> "Read only"
    ConnectorAccessProfile.WRITE_ONLY -> "Write only"
    ConnectorAccessProfile.READ_WRITE -> "Read and write"
    ConnectorAccessProfile.FULL_ACCESS -> "Full access"
}
