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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.airi.assistant.execution.privacy.PrivacyGuard
import com.airi.assistant.ui.components.ConnectorAccessProfileDialog
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AIRIColors
import com.airi.assistant.ui.theme.AIRISpacing
import com.airi.assistant.ui.theme.AiriTheme
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
    val disconnectedMessage = stringResource(R.string.connector_action_disconnect)
    val feedbackSentMessage = stringResource(R.string.connector_report_saved)
    val locale = LocalConfiguration.current.locales[0]
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
    val presentation = remember(meta, locale) { meta.presentation(locale) }
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
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.menu), tint = AiriTheme.onBackground)
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.connector_action_refresh)) },
                                    leadingIcon = { Icon(Icons.Outlined.Link, null) },
                                    onClick = {
                                        showMenu = false
                                        onManageAuthorization(connectorId)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.connector_action_disconnect)) },
                                    leadingIcon = { Icon(Icons.Outlined.Close, null) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.disconnect(connectorId)
                                        scope.launch { snackbar.showSnackbar(disconnectedMessage) }
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
                        if (active) scope.launch { snackbar.showSnackbar(disconnectedMessage) }
                    },
                    enabled = state != DetailConnectionState.COMING_SOON,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(52.dp),
                    shape = AIRIShapes.md,
                    colors = ButtonDefaults.buttonColors(containerColor = if (active) AiriTheme.surfaceVariant else AiriTheme.onSurfaceVariant)
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
                    Text(stringResource(R.string.connector_controls_title), color = AiriTheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    ActionRow(stringResource(R.string.connector_overview_title), stringResource(R.string.connector_overview_identity), Icons.Outlined.Security) { showOverview = true }
                    Divider(color = AiriTheme.outline.copy(alpha = 0.35f))
                    if (state != DetailConnectionState.COMING_SOON) {
                        ActionRow(stringResource(R.string.connectors_access), accessProfileLabel(selectedProfile), Icons.Outlined.Settings) { showAccessProfile = true }
                        Divider(color = AiriTheme.outline.copy(alpha = 0.35f))
                    }
                    ActionRow(stringResource(R.string.connector_report_title), stringResource(R.string.connector_report_placeholder), Icons.Outlined.ReportProblem) { showReport = true }
                }
            }

            if (state == DetailConnectionState.NEEDS_ATTENTION || state == DetailConnectionState.AUTHORIZATION_REQUIRED || state == DetailConnectionState.CONFIGURATION_REQUIRED) {
                OutlinedButton(onClick = { onManageAuthorization(connectorId) }, modifier = Modifier.fillMaxWidth(), shape = AIRIShapes.md) {
                    Icon(Icons.Outlined.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.connector_action_continue_setup))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showOverview) ConnectorOverviewDialog(meta, definition, presentation, authStrategy.mode, state, row.state, onDismiss = { showOverview = false })
    if (showReport) ConnectorReportDialog(
        context = context,
        connectorId = meta.id,
        connectorName = presentation.name,
        state = state,
        runtimeState = row.state,
        authMode = authStrategy.mode,
        onDismiss = { showReport = false },
        onSent = { showReport = false; scope.launch { snackbar.showSnackbar(feedbackSentMessage) } }
    )
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
        Surface(modifier = Modifier.size(92.dp), shape = AIRIShapes.xl, color = AiriTheme.onSurface.copy(alpha = 0.10f)) {
            Box(contentAlignment = Alignment.Center) {
                if (safeHttpsUri(iconUrl) != null) {
                    AsyncImage(
                        model = safeHttpsUri(iconUrl),
                        contentDescription = meta.name,
                        modifier = Modifier.padding(18.dp).fillMaxSize()
                    )
                } else {
                    Icon(icon, contentDescription = meta.name, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(42.dp))
                }
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
        DetailConnectionState.CONNECTED -> Triple(stringResource(R.string.connector_status_connected_verified), SemanticSuccess, Icons.Outlined.CheckCircle)
        DetailConnectionState.NEEDS_ATTENTION -> Triple(stringResource(R.string.connector_status_needs_attention), SemanticWarn, Icons.Outlined.WarningAmber)
        DetailConnectionState.AUTHORIZATION_REQUIRED -> Triple(stringResource(R.string.connector_status_authorization_required), AiriTheme.onSurfaceVariant, Icons.Outlined.Security)
        DetailConnectionState.CONFIGURATION_REQUIRED -> Triple(stringResource(R.string.connector_status_configuration_required), AiriTheme.onSurfaceVariant, Icons.Outlined.Settings)
        DetailConnectionState.DISCONNECTED -> Triple(stringResource(R.string.connector_status_not_connected), AiriTheme.onSurfaceVariant, Icons.Outlined.Link)
        DetailConnectionState.COMING_SOON -> Triple(stringResource(R.string.connector_status_coming_soon), AiriTheme.onSurfaceVariant, Icons.Outlined.ErrorOutline)
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
            Fact(stringResource(R.string.connector_fact_type), category.ifBlank { meta.type.name })
            Fact(stringResource(R.string.connector_fact_access), authMode.displayLabel())
            if (platform.isNotBlank()) Fact(stringResource(R.string.connector_fact_platform), platform)
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
        Surface(modifier = Modifier.size(38.dp), shape = AIRIShapes.sm, color = AiriTheme.onSurface.copy(alpha = 0.10f)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = AiriTheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
        }
        TextButton(onClick = onClick) { Text(stringResource(R.string.connector_action_open), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp) }
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
                Text(stringResource(R.string.connector_overview_title), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close)) }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OverviewBlock(stringResource(R.string.connector_overview_identity)) {
                    OverviewLine(stringResource(R.string.connector_overview_name), meta.name)
                    OverviewLine(stringResource(R.string.connector_overview_provider), meta.provider)
                    OverviewLine(stringResource(R.string.connector_overview_type), meta.type.name)
                    OverviewLine(stringResource(R.string.connector_overview_category), presentation.category)
                }
                OverviewBlock(stringResource(R.string.connector_overview_runtime_status)) {
                    OverviewLine(stringResource(R.string.connector_overview_state), state.displayLabel())
                    OverviewLine(stringResource(R.string.connector_overview_connected), if (runtimeState.connected) stringResource(R.string.yes) else stringResource(R.string.no))
                    OverviewLine(stringResource(R.string.connector_overview_healthy), if (runtimeState.healthy) stringResource(R.string.yes) else stringResource(R.string.no))
                    OverviewLine(stringResource(R.string.connector_overview_authentication), authMode.displayLabel())
                }
                if (presentation.capabilities.isNotEmpty()) OverviewBlock(stringResource(R.string.connector_overview_supported_capabilities)) { presentation.capabilities.forEach { OverviewBullet(it) } }
                if (presentation.limitations.isNotEmpty()) OverviewBlock(stringResource(R.string.connector_overview_requirements_limits)) { presentation.limitations.distinct().forEach { OverviewBullet(it) } }
                if (authMode != ConnectorAuthMode.NONE) OverviewBlock(stringResource(R.string.connector_overview_declared_permissions)) { ConnectorAuthStrategies.forMeta(meta).requiredScopes.toList().forEach { OverviewBullet(it) } }
                OverviewBlock(stringResource(R.string.connector_overview_official_links)) {
                    listOf(stringResource(R.string.connector_link_website) to meta.website, stringResource(R.string.connector_link_documentation) to meta.documentationUrl, stringResource(R.string.connector_link_privacy) to meta.privacyPolicyUrl).forEach { (label, value) ->
                        safeHttpsUri(value)?.let { uri ->
                            val context = LocalContext.current
                            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }) { Icon(Icons.Outlined.OpenInBrowser, null); Spacer(Modifier.width(6.dp)); Text(label) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.connector_action_done)) } }
    )
}

@Composable
private fun OverviewBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = AiriTheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Divider(color = AiriTheme.outline.copy(alpha = 0.3f))
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
        Text("•", color = AiriTheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Text(text, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private enum class ConnectorReportCategory {
    CONNECTION, AUTHORIZATION, TOOL_BEHAVIOR, OTHER
}

@Composable
private fun ConnectorReportDialog(
    context: Context,
    connectorId: String,
    connectorName: String,
    state: DetailConnectionState,
    runtimeState: com.airi.assistant.connector.ConnectorState,
    authMode: ConnectorAuthMode,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    var description by remember(connectorId) { mutableStateOf("") }
    var category by remember(connectorId) { mutableStateOf(ConnectorReportCategory.CONNECTION) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connector_report_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.connector_report_prompt, connectorName), color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
                Text(stringResource(R.string.connector_report_category_label), color = AiriTheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        ConnectorReportCategory.CONNECTION to R.string.connector_report_category_connection,
                        ConnectorReportCategory.AUTHORIZATION to R.string.connector_report_category_authorization,
                        ConnectorReportCategory.TOOL_BEHAVIOR to R.string.connector_report_category_tool,
                        ConnectorReportCategory.OTHER to R.string.connector_report_category_other,
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = category == value,
                            onClick = { category = value },
                            label = { Text(stringResource(label), fontSize = 11.sp) },
                        )
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(2000) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    placeholder = { Text(stringResource(R.string.connector_report_placeholder)) },
                    supportingText = {
                        Text(stringResource(R.string.connector_report_character_count, description.length))
                    }
                )
                Text(
                    stringResource(R.string.connector_report_diagnostics, state.displayLabel(), authMode.displayLabel()),
                    color = AiriTheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                Text(
                    stringResource(R.string.connector_report_privacy_note),
                    color = AiriTheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.connector_action_cancel)) } },
        confirmButton = {
            TextButton(
                enabled = description.trim().length >= 8,
                onClick = {
                    val sanitizedDescription = PrivacyGuard.redactForTrace(description.trim()).take(2000)
                    context.getSharedPreferences("airi_user_reports", Context.MODE_PRIVATE).edit()
                        .putString("last_connector", connectorId)
                        .putString("last_report", sanitizedDescription)
                        .putString("last_report_category", category.name)
                        .putString("last_report_state", state.name)
                        .putBoolean("last_report_connected", runtimeState.connected)
                        .putBoolean("last_report_healthy", runtimeState.healthy)
                        .putString("last_report_auth_mode", authMode.name)
                        .putString("last_report_locale", java.util.Locale.getDefault().toLanguageTag())
                        .putLong("last_report_at", System.currentTimeMillis()).apply()
                    onSent()
                }
            ) { Text(stringResource(R.string.connector_action_send)) }
        }
    )
}

@Composable
private fun ConnectorAuthMode.displayLabel(): String = when (this) {
    ConnectorAuthMode.OAUTH2_PKCE -> stringResource(R.string.connector_auth_oauth)
    ConnectorAuthMode.OAUTH2_CONFIDENTIAL -> stringResource(R.string.connector_auth_oauth_server)
    ConnectorAuthMode.API_KEY -> stringResource(R.string.connector_auth_api_key)
    ConnectorAuthMode.PERSONAL_ACCESS_TOKEN -> stringResource(R.string.connector_auth_access_token)
    ConnectorAuthMode.DEVICE_CODE -> stringResource(R.string.connector_auth_device_code)
    ConnectorAuthMode.LOCAL_PERMISSION -> stringResource(R.string.connector_auth_device_permission)
    ConnectorAuthMode.MCP_CONFIGURATION -> stringResource(R.string.connector_auth_mcp_setup)
    ConnectorAuthMode.WEBHOOK -> stringResource(R.string.connector_auth_webhook)
    ConnectorAuthMode.NONE -> stringResource(R.string.connector_auth_none)
    ConnectorAuthMode.COMING_SOON -> stringResource(R.string.connector_auth_not_available)
}

@Composable
private fun DetailConnectionState.displayLabel(): String = when (this) {
    DetailConnectionState.CONNECTED -> stringResource(R.string.connector_status_connected_verified)
    DetailConnectionState.NEEDS_ATTENTION -> stringResource(R.string.connector_status_needs_attention)
    DetailConnectionState.AUTHORIZATION_REQUIRED -> stringResource(R.string.connector_status_authorization_required)
    DetailConnectionState.CONFIGURATION_REQUIRED -> stringResource(R.string.connector_status_configuration_required)
    DetailConnectionState.DISCONNECTED -> stringResource(R.string.connector_status_not_connected)
    DetailConnectionState.COMING_SOON -> stringResource(R.string.connector_status_coming_soon)
}

@Composable
private fun primaryActionLabel(state: DetailConnectionState, strategy: com.airi.assistant.connector.ConnectorAuthStrategy): String = when (state) {
    DetailConnectionState.CONNECTED -> stringResource(R.string.connector_state_disconnect)
    DetailConnectionState.NEEDS_ATTENTION -> stringResource(R.string.connector_state_reconnect)
    DetailConnectionState.AUTHORIZATION_REQUIRED -> if (strategy.mode == ConnectorAuthMode.MCP_CONFIGURATION) stringResource(R.string.connector_state_configure) else stringResource(R.string.connector_state_connect)
    DetailConnectionState.CONFIGURATION_REQUIRED -> stringResource(R.string.connector_state_configure)
    DetailConnectionState.DISCONNECTED -> if (strategy.mode == ConnectorAuthMode.MCP_CONFIGURATION) stringResource(R.string.connector_state_configure) else stringResource(R.string.connector_state_connect)
    DetailConnectionState.COMING_SOON -> stringResource(R.string.connector_status_coming_soon)
}

private fun accessProfileLabel(profile: ConnectorAccessProfile): String = when (profile) {
    ConnectorAccessProfile.NOT_CONFIGURED -> "No agent access"
    ConnectorAccessProfile.READ_ONLY -> "Read only"
    ConnectorAccessProfile.WRITE_ONLY -> "Write only"
    ConnectorAccessProfile.READ_WRITE -> "Read and write"
    ConnectorAccessProfile.FULL_ACCESS -> "Full access"
}
