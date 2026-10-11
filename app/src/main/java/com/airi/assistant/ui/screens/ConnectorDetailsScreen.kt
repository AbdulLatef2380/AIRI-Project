package com.airi.assistant.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.airi.assistant.R
import com.airi.assistant.connector.ConnectorAccessProfile
import com.airi.assistant.connector.ConnectorAuthMode
import com.airi.assistant.connector.ConnectorAuthStrategies
import com.airi.assistant.connector.ConnectorAvailability
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.ui.components.ConnectorAccessProfileDialog
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AIRIColors
import com.airi.assistant.ui.theme.AIRISpacing
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn
import com.airi.assistant.ui.viewmodel.ConnectorsViewModel
import kotlinx.coroutines.launch

private enum class DetailConnectionState {
    CONNECTED, NEEDS_ATTENTION, AUTHORIZATION_REQUIRED, CONFIGURATION_REQUIRED, DISCONNECTED, COMING_SOON
}

private fun detailConnectionState(
    availability: ConnectorAvailability,
    connected: Boolean,
    healthy: Boolean,
    authMode: ConnectorAuthMode,
): DetailConnectionState = when {
    availability == ConnectorAvailability.COMING_SOON || authMode == ConnectorAuthMode.COMING_SOON -> DetailConnectionState.COMING_SOON
    connected && healthy -> DetailConnectionState.CONNECTED
    connected -> DetailConnectionState.NEEDS_ATTENTION
    availability == ConnectorAvailability.PARTIAL && authMode in setOf(ConnectorAuthMode.MCP_CONFIGURATION, ConnectorAuthMode.OAUTH2_CONFIDENTIAL) -> DetailConnectionState.CONFIGURATION_REQUIRED
    authMode != ConnectorAuthMode.NONE -> DetailConnectionState.AUTHORIZATION_REQUIRED
    else -> DetailConnectionState.DISCONNECTED
}

private fun connectorIcon(id: String) = when {
    id.contains("github") -> Icons.Outlined.Code
    id.contains("google") || id.contains("calendar") -> Icons.Outlined.Link
    id.contains("mcp") || id.contains("n8n") -> Icons.Outlined.Extension
    id.contains("telegram") -> Icons.Outlined.Link
    id.contains("system") -> Icons.Outlined.Settings
    else -> Icons.Outlined.Security
}

private fun safeHttpsUri(value: String?): Uri? = value?.let { raw ->
    runCatching { Uri.parse(raw) }.getOrNull()?.takeIf {
        it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() && it.userInfo == null
    }
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
    var showOverview by remember(connectorId) { mutableStateOf(false) }
    var showReport by remember(connectorId) { mutableStateOf(false) }
    var showMenu by remember(connectorId) { mutableStateOf(false) }
    var showAccessProfile by remember(connectorId) { mutableStateOf(false) }

    if (row == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val meta = row.meta
    val definition = remember(meta.id) { com.airi.assistant.connector.OfficialConnectorCatalog.get(meta.id) }
    val presentation = remember(meta) { meta.presentation() }
    val authStrategy = remember(meta) { ConnectorAuthStrategies.forMeta(meta) }
    val state = detailConnectionState(meta.availability, row.state.connected, row.state.healthy, authStrategy.mode)
    val active = state == DetailConnectionState.CONNECTED || state == DetailConnectionState.NEEDS_ATTENTION
    val accessOptions = remember(meta.id, meta.runtimeId) { viewModel.selectableAccessProfiles(connectorId) }

    Scaffold(
        containerColor = AiriTheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                actions = {
                    if (active) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "Connector options", tint = AiriTheme.onBackground)
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Refresh connection") },
                                    leadingIcon = { Icon(Icons.Outlined.Link, null) },
                                    onClick = {
                                        showMenu = false
                                        onManageAuthorization(connectorId)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Disconnect") },
                                    leadingIcon = { Icon(Icons.Outlined.Close, null) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.disconnect(connectorId)
                                        scope.launch { snackbar.showSnackbar("Connector disconnected") }
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        },
        bottomBar = {
            Surface(color = AiriTheme.background, shadowElevation = 10.dp) {
                Button(
                    onClick = {
                        when {
                            active -> viewModel.disconnect(connectorId)
                            else -> onManageAuthorization(connectorId)
                        }
                        if (active) scope.launch { snackbar.showSnackbar("Connector disconnected") }
                    },
                    enabled = state != DetailConnectionState.COMING_SOON,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(52.dp),
                    shape = AIRIShapes.md,
                    colors = ButtonDefaults.buttonColors(containerColor = if (active) AiriTheme.surfaceVariant else CosmicAccent)
                ) {
                    Icon(if (active) Icons.Outlined.Close else Icons.Outlined.CheckCircle, null)
                    Spacer(Modifier.width(8.dp))
                    Text(primaryActionLabel(state, authStrategy), fontWeight = FontWeight.Bold, color = if (active) AiriTheme.onBackground else Color.White)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AIRISpacing.md)
        ) {
            Spacer(Modifier.height(4.dp))
            ConnectorHero(meta, presentation.iconUrl, presentation.description, state)
            ConnectorStatusPill(state, row.state.statusLine, row.state.errorMessage)
            CompactConnectorFacts(meta, presentation.category, authStrategy.mode, definition?.supportedPlatforms?.firstOrNull().orEmpty())

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = AIRIShapes.lg,
                color = AiriTheme.surface,
                tonalElevation = 2.dp
            ) {
                Column(Modifier.padding(AIRISpacing.lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connector controls", color = AiriTheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    ActionRow("Overview", "Identity, permissions, capabilities and requirements", Icons.Outlined.Security) { showOverview = true }
                    HorizontalDivider(color = AiriTheme.outline.copy(alpha = 0.35f))
                    if (state != DetailConnectionState.COMING_SOON) {
                        ActionRow("Agent access", accessProfileLabel(selectedProfile), Icons.Outlined.Settings) { showAccessProfile = true }
                        HorizontalDivider(color = AiriTheme.outline.copy(alpha = 0.35f))
                    }
                    ActionRow("Report a problem", "Send feedback about this connector", Icons.Outlined.ReportProblem) { showReport = true }
                }
            }

            if (state == DetailConnectionState.NEEDS_ATTENTION || state == DetailConnectionState.AUTHORIZATION_REQUIRED || state == DetailConnectionState.CONFIGURATION_REQUIRED) {
                OutlinedButton(onClick = { onManageAuthorization(connectorId) }, modifier = Modifier.fillMaxWidth(), shape = AIRIShapes.md) {
                    Icon(Icons.Outlined.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Continue setup")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showOverview) ConnectorOverviewDialog(meta, definition, presentation, authStrategy.mode, state, row.state, onDismiss = { showOverview = false })
    if (showReport) ConnectorReportDialog(context, meta, onDismiss = { showReport = false }, onSent = { showReport = false; scope.launch { snackbar.showSnackbar("Thanks for the feedback") } })
    if (showAccessProfile) {
        ConnectorAccessProfileDialog(
            title = stringResource(R.string.connectors_access_profile_title),
            description = stringResource(R.string.connectors_access_profile_description),
            currentProfile = selectedProfile,
            availableProfiles = accessOptions,
            onConfirm = { profile -> viewModel.setAccessProfile(connectorId, profile); showAccessProfile = false },
            onDismiss = { showAccessProfile = false },
        )
    }
}

@Composable
private fun ConnectorHero(meta: ConnectorMeta, iconUrl: String?, description: String, state: DetailConnectionState) {
    val icon = connectorIcon(meta.id)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Surface(modifier = Modifier.size(92.dp), shape = AIRIShapes.xl, color = CosmicAccent.copy(alpha = 0.12f)) {
            Box(contentAlignment = Alignment.Center) {
                if (safeHttpsUri(iconUrl) != null) AsyncImage(model = safeHttpsUri(iconUrl), contentDescription = meta.name, modifier = Modifier.padding(18.dp).fillMaxSize())
                else Icon(icon, contentDescription = meta.name, tint = CosmicAccent, modifier = Modifier.size(42.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(meta.name, color = AiriTheme.onBackground, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(description.trim(), color = AiriTheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ConnectorStatusPill(state: DetailConnectionState, runtimeStatus: String, error: String?) {
    val (label, color, icon) = when (state) {
        DetailConnectionState.CONNECTED -> Triple("Connected and verified", SemanticSuccess, Icons.Outlined.CheckCircle)
        DetailConnectionState.NEEDS_ATTENTION -> Triple("Needs attention", SemanticWarn, Icons.Outlined.WarningAmber)
        DetailConnectionState.AUTHORIZATION_REQUIRED -> Triple("Authorization required", CosmicAccent, Icons.Outlined.Security)
        DetailConnectionState.CONFIGURATION_REQUIRED -> Triple("Configuration required", CosmicAccent, Icons.Outlined.Settings)
        DetailConnectionState.DISCONNECTED -> Triple("Not connected", AiriTheme.onSurfaceVariant, Icons.Outlined.Link)
        DetailConnectionState.COMING_SOON -> Triple("Coming soon", AiriTheme.onSurfaceVariant, Icons.Outlined.ErrorOutline)
    }
    Surface(color = color.copy(alpha = 0.12f), shape = AIRIShapes.pill) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (runtimeStatus.isNotBlank() || !error.isNullOrBlank()) {
        Text(error?.takeIf { it.isNotBlank() } ?: runtimeStatus, color = if (!error.isNullOrBlank()) SemanticError else AiriTheme.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CompactConnectorFacts(meta: ConnectorMeta, category: String, authMode: ConnectorAuthMode, platform: String) {
    Surface(modifier = Modifier.fillMaxWidth(), color = AiriTheme.surfaceVariant.copy(alpha = 0.65f), shape = AIRIShapes.md) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Fact("Type", category.ifBlank { meta.type.name })
            Fact("Access", authMode.displayLabel())
            if (platform.isNotBlank()) Fact("Platform", platform)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 5.dp)) {
        Text(label, color = AiriTheme.onSurfaceVariant, fontSize = 10.sp)
        Text(value, color = AiriTheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().background(Color.Transparent).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(modifier = Modifier.size(38.dp), shape = AIRIShapes.sm, color = CosmicAccent.copy(alpha = 0.12f)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = CosmicAccent, modifier = Modifier.size(19.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = AiriTheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
        }
        TextButton(onClick = onClick) { Text("Open", color = CosmicAccent, fontSize = 12.sp) }
    }
}

@Composable
private fun ConnectorOverviewDialog(
    meta: ConnectorMeta,
    definition: com.airi.assistant.connector.ConnectorDefinition?,
    presentation: ConnectorPresentation,
    authMode: ConnectorAuthMode,
    state: DetailConnectionState,
    runtimeState: com.airi.assistant.connector.ConnectorState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connector overview", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OverviewBlock("Identity") {
                    OverviewLine("Name", meta.name)
                    OverviewLine("Provider", meta.provider)
                    OverviewLine("Type", meta.type.name)
                    OverviewLine("Category", presentation.category)
                }
                OverviewBlock("Runtime status") {
                    OverviewLine("State", state.displayLabel())
                    OverviewLine("Connected", if (runtimeState.connected) "Yes" else "No")
                    OverviewLine("Healthy", if (runtimeState.healthy) "Yes" else "No")
                    OverviewLine("Authentication", authMode.displayLabel())
                }
                if (meta.capabilities.isNotEmpty()) OverviewBlock("Supported capabilities") { meta.capabilities.forEach { OverviewBullet(it.description) } }
                if (definition?.limitations?.isNotEmpty() == true) OverviewBlock("Requirements and limits") { definition.limitations.distinct().forEach { OverviewBullet(it) } }
                if (authMode != ConnectorAuthMode.NONE) OverviewBlock("Declared permissions") { ConnectorAuthStrategies.forMeta(meta).requiredScopes.toList().forEach { OverviewBullet(it) } }
                OverviewBlock("Official links") {
                    listOf("Website" to meta.website, "Documentation" to meta.documentationUrl, "Privacy" to meta.privacyPolicyUrl).forEach { (label, value) ->
                        safeHttpsUri(value)?.let { uri ->
                            val context = LocalContext.current
                            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }) { Icon(Icons.Outlined.OpenInBrowser, null); Spacer(Modifier.width(6.dp)); Text(label) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun OverviewBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = AiriTheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        HorizontalDivider(color = AiriTheme.outline.copy(alpha = 0.3f))
        content()
    }
}

@Composable
private fun OverviewLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(0.38f))
        Text(value, color = AiriTheme.onBackground, fontSize = 12.sp, modifier = Modifier.weight(0.62f))
    }
}

@Composable
private fun OverviewBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
        Text("•", color = CosmicAccent, fontWeight = FontWeight.Bold)
        Text(text, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun ConnectorReportDialog(context: Context, meta: ConnectorMeta, onDismiss: () -> Unit, onSent: () -> Unit) {
    var description by remember(meta.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report a problem", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tell us what needs attention in ${meta.name}.", color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(2000) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    placeholder = { Text("Describe the problem or suggestion") },
                    supportingText = { Text("${description.length} / 2000") }
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(
                enabled = description.trim().length >= 8,
                onClick = {
                    context.getSharedPreferences("airi_user_reports", Context.MODE_PRIVATE).edit()
                        .putString("last_connector", meta.id)
                        .putString("last_report", description.trim())
                        .putLong("last_report_at", System.currentTimeMillis()).apply()
                    onSent()
                }
            ) { Text("Send") }
        }
    )
}

private fun ConnectorAuthMode.displayLabel(): String = when (this) {
    ConnectorAuthMode.OAUTH2_PKCE -> "OAuth 2.0"
    ConnectorAuthMode.OAUTH2_CONFIDENTIAL -> "OAuth server"
    ConnectorAuthMode.API_KEY -> "API key"
    ConnectorAuthMode.PERSONAL_ACCESS_TOKEN -> "Access token"
    ConnectorAuthMode.DEVICE_CODE -> "Device code"
    ConnectorAuthMode.LOCAL_PERMISSION -> "Device permission"
    ConnectorAuthMode.MCP_CONFIGURATION -> "MCP setup"
    ConnectorAuthMode.WEBHOOK -> "Webhook"
    ConnectorAuthMode.NONE -> "No sign-in"
    ConnectorAuthMode.COMING_SOON -> "Not available"
}

private fun DetailConnectionState.displayLabel(): String = when (this) {
    DetailConnectionState.CONNECTED -> "Connected and verified"
    DetailConnectionState.NEEDS_ATTENTION -> "Needs attention"
    DetailConnectionState.AUTHORIZATION_REQUIRED -> "Authorization required"
    DetailConnectionState.CONFIGURATION_REQUIRED -> "Configuration required"
    DetailConnectionState.DISCONNECTED -> "Not connected"
    DetailConnectionState.COMING_SOON -> "Coming soon"
}

private fun primaryActionLabel(state: DetailConnectionState, strategy: com.airi.assistant.connector.ConnectorAuthStrategy): String = when (state) {
    DetailConnectionState.CONNECTED -> "Disconnect connector"
    DetailConnectionState.NEEDS_ATTENTION -> "Reconnect connector"
    DetailConnectionState.AUTHORIZATION_REQUIRED -> if (strategy.mode == ConnectorAuthMode.MCP_CONFIGURATION) "Configure connector" else "Connect connector"
    DetailConnectionState.CONFIGURATION_REQUIRED -> "Configure connector"
    DetailConnectionState.DISCONNECTED -> if (strategy.mode == ConnectorAuthMode.MCP_CONFIGURATION) "Configure connector" else "Connect connector"
    DetailConnectionState.COMING_SOON -> "Coming soon"
}

private fun accessProfileLabel(profile: ConnectorAccessProfile): String = when (profile) {
    ConnectorAccessProfile.NOT_CONFIGURED -> "No agent access"
    ConnectorAccessProfile.READ_ONLY -> "Read only"
    ConnectorAccessProfile.WRITE_ONLY -> "Write only"
    ConnectorAccessProfile.READ_WRITE -> "Read and write"
    ConnectorAccessProfile.FULL_ACCESS -> "Full access"
}
