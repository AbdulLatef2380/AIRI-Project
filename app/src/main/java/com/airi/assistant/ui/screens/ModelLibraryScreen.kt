package com.airi.assistant.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.cloud.EmbeddedProviderConfig
import com.airi.assistant.execution.cloud.OpenRouterAdapter
import com.airi.assistant.execution.prefs.ExecModePreferences
import com.airi.assistant.execution.security.SecureApiKeyStore
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.ui.viewmodel.ModelUiState
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

/**
 * ModelLibraryScreen — Phase C5 Library screen.
 *
 * Shows the user ALL available AI models (local + cloud) with their
 * capabilities, current routing state, and provider health — without
 * exposing raw API management. AIRI routes automatically; this screen
 * helps the user understand what AIRI is doing and why.
 *
 * ── Sections ──────────────────────────────────────────────────────────────
 *   1. Active Routing Status  — what's running right now and why
 *   2. Smart Routing Mode     — LOCAL / HYBRID / CLOUD_ONLY toggle
 *   3. Local AI               — on-device GGUF models (privacy, offline)
 *   4. Cloud AI               — free cloud providers (connected to EmbeddedProviderConfig)
 *   5. OpenRouter Models      — task-specific models AIRI auto-selects
 *
 * ── Integration ───────────────────────────────────────────────────────────
 * Reads [ChatViewModel.modelState] (StateFlow) — stable, survives
 * recomposition. Calls [ChatViewModel.activateBuiltinProvider] and
 * [ChatViewModel.setExecutionMode] on user interaction — same paths used
 * by CloudModelStoreSection so no new code path is introduced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelLibraryScreen(
    viewModel: ChatViewModel,
    onBack:    () -> Unit
) {
    val context    = LocalContext.current
    val modelState by viewModel.modelState.collectAsState()
    val scope      = rememberCoroutineScope()
    val snackbar   = remember { SnackbarHostState() }

    // Current execution mode from prefs — read once, updated via ViewModel
    val execPrefs  = remember { ExecModePreferences(context) }
    var execMode   by remember { mutableStateOf(execPrefs.executionMode) }

    // Active built-in provider selection (same source CloudModelStoreSection reads)
    var activeProv by remember { mutableStateOf(EmbeddedProviderConfig.getActiveProvider(context)) }

    // Key entry dialog state
    var keyDialog         by remember { mutableStateOf<EmbeddedProviderConfig.ProviderConfig?>(null) }
    // Brave Search API key dialog (uses CloudProvider directly, not EmbeddedProviderConfig)
    var keyDialogProvider by remember { mutableStateOf<com.airi.assistant.execution.CloudProvider?>(null) }

    Scaffold(
        containerColor = AiriTheme.background,
        snackbarHost   = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.model_library_title), color = AiriTheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(stringResource(R.string.model_library_subtitle), color = AiriTheme.onSurfaceVariant.copy(alpha = 0.45f), fontSize = 11.sp)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                ActiveRoutingCard(modelState = modelState)
            }
            item {
                SmartRoutingModeCard(
                    current = execMode,
                    onSelect = { mode ->
                        execMode = mode
                        viewModel.setExecutionMode(mode)
                        scope.launch {
                            snackbar.showSnackbar(
                                when (mode) {
                                    ExecutionMode.LOCAL_ONLY  -> "Offline AI — cloud disabled"
                                    ExecutionMode.HYBRID      -> "Smart Routing — AIRI chooses best model"
                                    ExecutionMode.CLOUD_ONLY  -> "Cloud Intelligence — local model bypassed"
                                    else                      -> "Mode updated"
                                },
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                )
            }
            item {
                SectionHeader(
                    icon  = Icons.Outlined.PhoneAndroid,
                    title = "Offline AI",
                    badge = if (modelState.isModelReady) "ACTIVE" else null,
                    badgeColor = CosmicAccent
                )
            }

            item {
                LocalModelCard(modelState = modelState)
            }
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader(
                    icon  = Icons.Outlined.Cloud,
                    title = "Cloud Intelligence",
                    badge = if (modelState.isCloudReady) "ACTIVE" else null,
                    badgeColor = Color(0xFF00BFA5)
                )
            }

            val freeTierProviders = EmbeddedProviderConfig.catalog.filter {
                it.tier == EmbeddedProviderConfig.ProviderTier.FREE_SIGNUP ||
                it.tier == EmbeddedProviderConfig.ProviderTier.LOCAL_SERVER
            }

            items(freeTierProviders, key = { it.id }) { config ->
                val isActive = activeProv?.id == config.id && modelState.isCloudReady
                val hasKey   = EmbeddedProviderConfig.hasKeyFor(context, config)
                CloudProviderCard(
                    config   = config,
                    isActive = isActive,
                    hasKey   = hasKey,
                    onConnect = {
                        if (hasKey || config.tier == EmbeddedProviderConfig.ProviderTier.LOCAL_SERVER) {
                            scope.launch {
                                viewModel.activateBuiltinProvider(config)
                                activeProv = config
                                snackbar.showSnackbar("${config.displayLabel} activated", duration = SnackbarDuration.Short)
                            }
                        } else {
                            keyDialog = config
                        }
                    },
                    onDeactivate = if (isActive) {
                        {
                            viewModel.clearCloudModel()
                            activeProv = null
                        }
                    } else null
                )
            }
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader(
                    icon  = Icons.Outlined.Search,
                    title = "Search & Research",
                    badge = if (SecureApiKeyStore(context).hasKey(CloudProvider.BRAVE)) "ACTIVE" else null,
                    badgeColor = Color(0xFFFF6D00)
                )
            }
            item {
                BraveSearchApiCard(
                    context     = context,
                    hasBraveKey = runCatching { com.airi.assistant.core.ServiceLocator.secureApiKeyStore.hasKey(com.airi.assistant.execution.CloudProvider.BRAVE) }.getOrDefault(false),
                    onEnterKey  = { keyDialogProvider = CloudProvider.BRAVE }
                )
            }
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader(
                    icon  = Icons.Outlined.AutoAwesome,
                    title = "Smart Routing Models",
                    badge = "AUTO",
                    badgeColor = Color(0xFF7C4DFF)
                )
            }
            item {
                Text(
                    "When using OpenRouter, AIRI automatically selects the best model for each task.",
                    color = AiriTheme.onSurfaceVariant.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            items(OPENROUTER_TASK_MODELS, key = { it.modelId }) { entry ->
                OpenRouterTaskModelCard(entry = entry)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    keyDialog?.let { config ->
        ApiKeyEntryDialog(
            config = config,
            onDismiss = { keyDialog = null },
            onConfirm = { key ->
                EmbeddedProviderConfig.saveKey(context, config, key)
                scope.launch {
                    viewModel.activateBuiltinProvider(config)
                    activeProv = config
                    keyDialog  = null
                    snackbar.showSnackbar("${config.displayLabel} connected", duration = SnackbarDuration.Short)
                }
            }
        )
    }
    keyDialogProvider?.let { provider ->
        BraveKeyEntryDialog(
            provider  = provider,
            context   = context,
            onDismiss = { keyDialogProvider = null },
            onConfirm = { key ->
                runCatching { com.airi.assistant.core.ServiceLocator.secureApiKeyStore.saveKey(provider, key) }
                keyDialogProvider = null
                scope.launch {
                    snackbar.showSnackbar("${provider.displayName} key saved", duration = SnackbarDuration.Short)
                }
            }
        )
    }
}
@Composable
private fun BraveSearchApiCard(
    context:     Context,
    hasBraveKey: Boolean,
    onEnterKey:  () -> Unit
) {
    Surface(
        shape    = AIRIShapes.md,
        color    = Color(0xFF1C1C1E),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Row(
            modifier              = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(AIRIShapes.sm)
                    .background(Color(0xFFFF6D00).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Search, null, tint = Color(0xFFFF6D00), modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.brave_search_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                Text(
                    if (hasBraveKey) "Enabled — real web results + page content"
                    else "Not configured — using DDG fallback (~30% coverage)",
                    fontSize = 11.sp,
                    color    = if (hasBraveKey) Color(0xFF30D158) else AiriTheme.onSurface.copy(alpha = 0.5f),
                    lineHeight = 14.sp
                )
                Text(stringResource(R.string.brave_search_desc), fontSize = 10.sp,
                    color = AiriTheme.outline)
            }
            TextButton(onClick = onEnterKey) {
                Text(if (hasBraveKey) "Update" else "Add Key", color = Color(0xFF0A84FF), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BraveKeyEntryDialog(
    provider:  com.airi.assistant.execution.CloudProvider,
    context:   Context,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1C1C1E),
        title = { Text("${provider.displayName} API Key", color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.brave_key_dialog_body),
                    fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                OutlinedTextField(
                    value         = input,
                    onValueChange = { input = it },
                    placeholder   = { Text(stringResource(R.string.brave_key_placeholder), color = AiriTheme.outline) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (input.isNotBlank()) onConfirm(input.trim()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant) } }
    )
}
@Composable
private fun ActiveRoutingCard(modelState: ModelUiState) {
    val activeLabel = when {
        modelState.isModelReady && modelState.isCloudReady ->
            "${modelState.selectedModelName} + ${modelState.cloudModelName}"
        modelState.isModelReady  -> modelState.selectedModelName
        modelState.isCloudReady  -> modelState.cloudModelName.ifBlank { "Cloud AI" }
        else                     -> "No AI active"
    }
    val statusColor = when {
        modelState.isAnyInferenceReady -> Color(0xFF00E676)
        else                           -> Color(0xFFFF5252)
    }

    Surface(
        shape = AIRIShapes.md,
        color = AiriTheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.model_library_active_ai), color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
                Text(
                    activeLabel,
                    color      = AiriTheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
            }
            if (modelState.isAnyInferenceReady) {
                Surface(
                    shape = AIRIShapes.xs,
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        "READY",
                        color    = statusColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}
@Composable
private fun SmartRoutingModeCard(
    current:  ExecutionMode,
    onSelect: (ExecutionMode) -> Unit
) {
    Surface(
        shape = AIRIShapes.md,
        color = AiriTheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tune, contentDescription = null,
                    tint = CosmicAccent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.model_library_smart_routing), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(12.dp))

            listOf(
                Triple(ExecutionMode.LOCAL_ONLY, "Offline AI Only",
                    "Local models only — full privacy, no internet required"),
                Triple(ExecutionMode.HYBRID,     "Smart Routing",
                    "AIRI automatically picks the best model for each task"),
                Triple(ExecutionMode.CLOUD_ONLY, "Cloud Intelligence",
                    "Route all requests to cloud — best quality, needs internet")
            ).forEach { (mode, label, desc) ->
                val isSelected = current == mode
                val borderColor by animateColorAsState(
                    if (isSelected) CosmicAccent else AiriTheme.outline,
                    animationSpec = tween(AIRIAnimations.FAST), label = "border_$mode"
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(AIRIShapes.sm)
                        .border(1.dp, borderColor, AIRIShapes.sm)
                        .background(if (isSelected) CosmicAccent.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable { onSelect(mode) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick  = { onSelect(mode) },
                        colors   = RadioButtonDefaults.colors(
                            selectedColor   = CosmicAccent,
                            unselectedColor = AiriTheme.onSurface.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(label, color = AiriTheme.onBackground, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text(desc, color = AiriTheme.onSurfaceVariant.copy(alpha = 0.45f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
@Composable
private fun LocalModelCard(modelState: ModelUiState) {
    Surface(
        shape = AIRIShapes.md,
        color = AiriTheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Memory, contentDescription = null,
                tint = CosmicAccent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (modelState.isModelReady) modelState.selectedModelName else "No local model loaded",
                    color = AiriTheme.onBackground, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        modelState.isModelReady  -> "On-device · Private · Offline"
                        modelState.isModelLoading -> "Loading…"
                        else                     -> "Load a GGUF model to enable offline AI"
                    },
                    color = AiriTheme.onSurfaceVariant, fontSize = 11.sp
                )
            }
            if (modelState.isModelReady) {
                CapabilityBadge("LOCAL", Color(0xFF00E676))
            } else if (modelState.isModelLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = CosmicAccent
                )
            }
        }
    }
}
@Composable
private fun CloudProviderCard(
    config:      EmbeddedProviderConfig.ProviderConfig,
    isActive:    Boolean,
    hasKey:      Boolean,
    onConnect:   () -> Unit,
    onDeactivate: (() -> Unit)?
) {
    val accentColor = Color(config.badgeColor)
    val borderColor by animateColorAsState(
        if (isActive) accentColor.copy(alpha = 0.6f) else AiriTheme.outline,
        animationSpec = tween(AIRIAnimations.NORMAL), label = "card_border_${config.id}"
    )

    Surface(
        shape = AIRIShapes.md,
        color = AiriTheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, AIRIShapes.md)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Color(0xFF00E676) else accentColor.copy(alpha = 0.5f))
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(config.displayLabel, color = AiriTheme.onBackground,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(config.description, color = AiriTheme.onSurfaceVariant,
                        fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Metadata badges
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (config.contextWindow.isNotBlank())
                    InfoBadge("ctx ${config.contextWindow}")
                if (config.rpmLimit.isNotBlank())
                    InfoBadge(config.rpmLimit)
                val tierLabel = when (config.tier) {
                    EmbeddedProviderConfig.ProviderTier.FREE_SIGNUP   -> "Free"
                    EmbeddedProviderConfig.ProviderTier.LOCAL_SERVER  -> "Local"
                    EmbeddedProviderConfig.ProviderTier.PAID          -> "Paid"
                }
                InfoBadge(tierLabel)
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isActive && onDeactivate != null) {
                    OutlinedButton(
                        onClick = onDeactivate,
                        shape   = AIRIShapes.xs,
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = AiriTheme.onSurface.copy(alpha = 0.5f)),
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(stringResource(R.string.deactivate), fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        shape   = AIRIShapes.xs,
                        colors  = ButtonDefaults.buttonColors(containerColor = accentColor.copy(alpha = 0.85f)),
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) {
                        Text(
                            if (hasKey || config.tier == EmbeddedProviderConfig.ProviderTier.LOCAL_SERVER)
                                "Use This" else "Connect",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (isActive) {
                    CapabilityBadge("ACTIVE", Color(0xFF00E676))
                }
            }
        }
    }
}
private data class TaskModelEntry(
    val modelId:    String,
    val taskLabel:  String,
    val taskIcon:   ImageVector,
    val description: String,
    val contextLen: String
)

private val OPENROUTER_TASK_MODELS = listOf(
    TaskModelEntry(
        modelId    = OpenRouterAdapter.MODEL_CODING,
        taskLabel  = "Coding & Debugging",
        taskIcon   = Icons.Outlined.Code,
        description = "DeepSeek Coder — best free model for code generation, review, and debugging",
        contextLen = "16k"
    ),
    TaskModelEntry(
        modelId    = OpenRouterAdapter.MODEL_REASONING,
        taskLabel  = "Deep Reasoning",
        taskIcon   = Icons.Outlined.Psychology,
        description = "DeepSeek R1 — chain-of-thought reasoning, math, and analytical tasks",
        contextLen = "64k"
    ),
    TaskModelEntry(
        modelId    = OpenRouterAdapter.DEFAULT_MODEL,
        taskLabel  = "General & Long Context",
        taskIcon   = Icons.Outlined.AutoAwesome,
        description = "Gemini 2.0 Flash — default model, vision support, 1M token context window",
        contextLen = "1M"
    ),
    TaskModelEntry(
        modelId    = OpenRouterAdapter.MODEL_MULTILINGUAL,
        taskLabel  = "Arabic & Multilingual",
        taskIcon   = Icons.Outlined.Language,
        description = "Qwen 2.5 72B — strong Arabic, Chinese, and multilingual reasoning",
        contextLen = "128k"
    ),
    TaskModelEntry(
        modelId    = OpenRouterAdapter.MODEL_FAST,
        taskLabel  = "Fast Responses",
        taskIcon   = Icons.Outlined.FlashOn,
        description = "Llama 3.3 8B — fastest free model for simple questions and quick replies",
        contextLen = "128k"
    )
)

@Composable
private fun OpenRouterTaskModelCard(entry: TaskModelEntry) {
    Surface(
        shape = AIRIShapes.md,
        color = AiriTheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                entry.taskIcon, contentDescription = null,
                tint = CosmicAccent.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.taskLabel, color = AiriTheme.onBackground,
                        fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    InfoBadge(entry.contextLen)
                }
                Text(entry.description, color = AiriTheme.onSurfaceVariant,
                    fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Surface(
                shape = AIRIShapes.xs,
                color = Color(0xFF7C4DFF).copy(alpha = 0.15f)
            ) {
                Text(stringResource(R.string.auto_badge), color = Color(0xFF7C4DFF), fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}
@Composable
private fun SectionHeader(
    icon:       ImageVector,
    title:      String,
    badge:      String?    = null,
    badgeColor: Color      = CosmicAccent
) {
    Row(
        Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null,
            tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, color = AiriTheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            letterSpacing = 0.5.sp)
        badge?.let {
            Spacer(Modifier.width(8.dp))
            CapabilityBadge(it, badgeColor)
        }
    }
}

@Composable
private fun CapabilityBadge(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(5.dp), color = color.copy(alpha = 0.15f)) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun InfoBadge(label: String) {
    Surface(shape = RoundedCornerShape(5.dp), color = AiriTheme.outline.copy(alpha = 0.07f)) {
        Text(label, color = AiriTheme.onSurfaceVariant, fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
@Composable
private fun ApiKeyEntryDialog(
    config:    EmbeddedProviderConfig.ProviderConfig,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var key by remember { mutableStateOf("") }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = SurfaceCard,
        shape            = AIRIShapes.md,
        title = {
            Text(stringResource(R.string.connect_label, config.displayLabel), color = AiriTheme.onBackground,
                fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "AIRI will use this key to route requests automatically. " +
                    "You only need to enter it once.",
                    color = AiriTheme.onSurfaceVariant, fontSize = 13.sp
                )
                OutlinedTextField(
                    value         = key,
                    onValueChange = { key = it },
                    label         = { Text(stringResource(R.string.api_key_label), color = AiriTheme.onSurfaceVariant) },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = CosmicAccent,
                        unfocusedBorderColor = AiriTheme.onSurface.copy(alpha = 0.2f),
                        focusedTextColor     = AiriTheme.onSurface,
                        unfocusedTextColor   = AiriTheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (config.signupUrl.isNotBlank()) {
                    Text(
                        "Get a free key at ${config.signupUrl}",
                        color    = CosmicAccent,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            runCatching {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(config.signupUrl)
                                )
                                context.startActivity(intent)
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick  = { if (key.isNotBlank()) onConfirm(key.trim()) },
                enabled  = key.isNotBlank(),
                colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
            ) { Text(stringResource(R.string.connect), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
            }
        }
    )
}
