package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.cloud.EmbeddedProviderConfig
import com.airi.assistant.execution.prefs.ExecModePreferences
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.ui.viewmodel.ModelUiState
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

/**
 * Cloud & Hybrid Model Store section for [ModelSettingsScreen].
 *
 * Renders three sub-sections:
 *  1. Active inference mode selector (LOCAL / CLOUD / HYBRID)
 *  2. Free built-in providers catalog ([EmbeddedProviderConfig.catalog])
 *  3. Advanced: manual remote endpoint entry (llama-server / OpenAI-compat)
 *
 * Integration points:
 *  - [ChatViewModel.activateBuiltinProvider] → sets active provider + refreshes [ModelUiState.isCloudReady]
 *  - [ChatViewModel.activateRemoteModel] → sets active remote + refreshes isCloudReady
 *  - [ChatViewModel.clearCloudModel] → deactivates + refreshes
 *  - [ChatViewModel.setExecutionMode] → persists + refreshes
 */
@Composable
fun CloudModelStoreSection(
    viewModel: ChatViewModel,
    modelState: ModelUiState
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val activeBuiltin  = remember { mutableStateOf(
        EmbeddedProviderConfig.getActiveProvider(context)
    )}
    var showKeyDialog  by remember { mutableStateOf<EmbeddedProviderConfig.ProviderConfig?>(null) }
    var showAdvanced   by remember { mutableStateOf(false) }
    var execModeExpanded by remember { mutableStateOf(false) }

    val execPrefs = remember { viewModel.getExecModePrefs() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4FC3F7).copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Cloud, contentDescription = null,
                    tint = Color(0xFF4FC3F7), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.cloud_models_title), fontWeight = FontWeight.Bold,
                    color = AiriTheme.onBackground, fontSize = 15.sp)
                Text(stringResource(R.string.cloud_models_subtitle),
                    color = AiriTheme.onSurfaceVariant.copy(alpha = 0.45f), fontSize = 11.sp)
            }
        }
        CloudInferenceModePicker(
            execPrefs  = execPrefs,
            onModeSet  = { mode ->
                viewModel.setExecutionMode(mode)
                if (mode != ExecutionMode.LOCAL_ONLY) {
                    viewModel.grantInternetPermission(true)
                }
            }
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "Free Providers",
            color = AiriTheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        EmbeddedProviderConfig.catalog.forEach { config ->
            val isActive = activeBuiltin.value?.id == config.id
            val hasKey   = EmbeddedProviderConfig.hasKeyFor(context, config)

            EmbeddedProviderCard(
                config   = config,
                isActive = isActive,
                hasKey   = hasKey,
                onActivate = {
                    if (config.tier == EmbeddedProviderConfig.ProviderTier.LOCAL_SERVER || hasKey) {
                        // Ready to activate
                        viewModel.activateBuiltinProvider(config)
                        activeBuiltin.value = config
                    } else {
                        // Need API key first
                        showKeyDialog = config
                    }
                },
                onDeactivate = {
                    viewModel.clearCloudModel()
                    activeBuiltin.value = null
                },
                onGetKey = {
                    showKeyDialog = config
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        Surface(
            onClick = { showAdvanced = !showAdvanced },
            color   = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AIRIShapes.md)
                    .background(AiriTheme.outline)
                    .border(1.dp, AiriTheme.outline, AIRIShapes.md)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.SettingsEthernet, contentDescription = null,
                    tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cloud_custom_endpoint_label), color = AiriTheme.onBackground, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.cloud_custom_endpoint_desc),
                        color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Icon(
                    if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null, tint = AiriTheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        AnimatedVisibility(visible = showAdvanced,
            enter = expandVertically(), exit = shrinkVertically()) {
            AddRemoteModelInlineContent(
                viewModel = viewModel,
                onActivated = { showAdvanced = false }
            )
        }

        Spacer(Modifier.height(16.dp))
    }
    showKeyDialog?.let { cfg ->
        ApiKeyEntryDialog(
            config      = cfg,
            context     = context,
            existingKey = EmbeddedProviderConfig.getKey(context, cfg) ?: "",
            onSave = { _ ->
                // Key is already persisted inside the dialog's confirmButton onClick.
                // Now bridge the provider into RemoteModelRegistry via the reactivate path.
                viewModel.reactivateBuiltinProviderAfterKeyEntry(cfg)
                activeBuiltin.value = EmbeddedProviderConfig.getActiveProvider(context)
                showKeyDialog = null
            },
            onDismiss = { showKeyDialog = null }
        )
    }
}
@Composable
private fun CloudInferenceModePicker(
    execPrefs: com.airi.assistant.execution.prefs.ExecModePreferences,
    onModeSet: (ExecutionMode) -> Unit
) {
    var selectedMode by remember { mutableStateOf(execPrefs.executionMode) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            Triple(ExecutionMode.LOCAL_ONLY,  Icons.Outlined.DevicesOther, "Local"),
            Triple(ExecutionMode.CLOUD_ONLY,  Icons.Outlined.Cloud,        "Cloud"),
            Triple(ExecutionMode.HYBRID,      Icons.Outlined.Bolt,         "Hybrid")
        ).forEach { (mode, icon, label) ->
            val selected = selectedMode == mode
            Surface(
                onClick = {
                    selectedMode = mode
                    onModeSet(mode)
                },
                shape = AIRIShapes.md,
                color = if (selected) CosmicAccent.copy(alpha = 0.18f)
                        else AiriTheme.outline,
                modifier = Modifier
                    .weight(1f)
                    .border(
                        1.dp,
                        if (selected) CosmicAccent.copy(alpha = 0.55f)
                        else AiriTheme.outline,
                        AIRIShapes.md
                    )
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(icon, contentDescription = label,
                        tint = if (selected) CosmicAccent else AiriTheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(label,
                        color = if (selected) CosmicAccent else AiriTheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    // Mode description
    val modeDesc = when (selectedMode) {
        ExecutionMode.LOCAL_ONLY -> "Privacy-first · fully offline · llama.cpp only"
        ExecutionMode.CLOUD_ONLY -> "Remote APIs · fastest · requires internet"
        ExecutionMode.HYBRID     -> "Smart routing: picks best engine per request"
    }
    Text(
        modeDesc,
        color = AiriTheme.outline,
        fontSize = 10.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
    )
}
@Composable
private fun EmbeddedProviderCard(
    config:      EmbeddedProviderConfig.ProviderConfig,
    isActive:    Boolean,
    hasKey:      Boolean,
    onActivate:  () -> Unit,
    onDeactivate: () -> Unit,
    onGetKey:    () -> Unit
) {
    val context = LocalContext.current
    val accentColor = Color(config.badgeColor)
    val tierLabel = when (config.tier) {
        EmbeddedProviderConfig.ProviderTier.FREE_SIGNUP   -> "FREE"
        EmbeddedProviderConfig.ProviderTier.LOCAL_SERVER  -> "LOCAL"
        EmbeddedProviderConfig.ProviderTier.PAID          -> "PAID"
    }

    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AIRIShapes.md)
                .background(
                    if (isActive) accentColor.copy(alpha = 0.10f)
                    else AiriTheme.onSurface.copy(alpha = 0.03f)
                )
                .border(
                    1.dp,
                    if (isActive) accentColor.copy(alpha = 0.50f)
                    else AiriTheme.outline,
                    AIRIShapes.md
                )
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Provider icon / tier badge
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when (config.tier) {
                            EmbeddedProviderConfig.ProviderTier.LOCAL_SERVER -> Icons.Outlined.Computer
                            else -> Icons.Outlined.Cloud
                        },
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(config.displayLabel, color = AiriTheme.onBackground,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        // Tier badge
                        Box(
                            modifier = Modifier
                                .clip(AIRIShapes.xs)
                                .background(accentColor.copy(alpha = 0.20f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(tierLabel, color = accentColor,
                                fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        if (isActive) {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .clip(AIRIShapes.xs)
                                    .background(Color(0xFF00C853).copy(alpha = 0.20f))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(stringResource(R.string.cloud_active_badge), color = Color(0xFF00C853),
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(config.description, color = AiriTheme.onSurfaceVariant.copy(alpha = 0.45f),
                        fontSize = 11.sp, lineHeight = 15.sp)
                    // Context window + RPM
                    if (config.contextWindow.isNotBlank() || config.rpmLimit.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (config.contextWindow.isNotBlank()) {
                                ProviderStatChip("ctx ${config.contextWindow}", accentColor)
                            }
                            if (config.rpmLimit.isNotBlank()) {
                                ProviderStatChip(config.rpmLimit, accentColor)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Action row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isActive) {
                    // Deactivate
                    OutlinedButton(
                        onClick = onDeactivate,
                        shape = AIRIShapes.sm,
                        border = ButtonDefaults.outlinedButtonBorder,
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Text(stringResource(R.string.deactivate), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                } else {
                    // Activate / Get key
                    Button(
                        onClick = onActivate,
                        shape = AIRIShapes.sm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor   = AiriTheme.background
                        ),
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            if (hasKey || config.tier == EmbeddedProviderConfig.ProviderTier.LOCAL_SERVER)
                                Icons.Outlined.PlayArrow else Icons.Outlined.Key,
                            contentDescription = null, modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (hasKey || config.tier == EmbeddedProviderConfig.ProviderTier.LOCAL_SERVER)
                                "Use This" else "Connect Free",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // "Get key" link for providers that need signup
                if (!hasKey && config.tier == EmbeddedProviderConfig.ProviderTier.FREE_SIGNUP
                    && config.signupUrl.isNotBlank()) {
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(config.signupUrl))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Outlined.OpenInNew, contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = AiriTheme.onSurfaceVariant.copy(alpha = 0.45f))
                        Spacer(Modifier.width(3.dp))
                        Text(stringResource(R.string.cloud_get_free_key), color = AiriTheme.onSurfaceVariant.copy(alpha = 0.45f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderStatChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color.copy(alpha = 0.75f), fontSize = 9.sp)
    }
}
@Composable
private fun ApiKeyEntryDialog(
    config:      EmbeddedProviderConfig.ProviderConfig,
    context:     android.content.Context,
    existingKey: String,
    onSave:      (String) -> Unit,
    onDismiss:   () -> Unit
) {
    var key by remember { mutableStateOf(existingKey) }
    var obscure by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor    = Color(0xFF12162E),
        titleContentColor = AiriTheme.onSurface,
        textContentColor  = AiriTheme.onSurface,
        shape             = AIRIShapes.xl,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Key, contentDescription = null,
                    tint = CosmicAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.connect_label, config.displayLabel), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "AIRI uses this key to route requests automatically. Stored encrypted on-device only — never sent to AIRI servers.",
                    color = AiriTheme.onSurfaceVariant, fontSize = 12.sp
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.cloud_access_key_label), color = AiriTheme.onSurfaceVariant) },
                    visualTransformation = if (obscure)
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    else androidx.compose.ui.text.input.VisualTransformation.None,
                    trailingIcon = {
                        IconButton(onClick = { obscure = !obscure }) {
                            Icon(
                                if (obscure) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                contentDescription = null, tint = CosmicAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = CosmicAccent,
                        unfocusedBorderColor = AiriTheme.onSurface.copy(alpha = 0.15f),
                        focusedTextColor     = AiriTheme.onSurface,
                        unfocusedTextColor   = AiriTheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (config.signupUrl.isNotBlank()) {
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(config.signupUrl))
                            context.startActivity(intent)
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Outlined.OpenInNew, contentDescription = null,
                            modifier = Modifier.size(13.dp), tint = CosmicAccent.copy(alpha = 0.7f))
                        Spacer(Modifier.width(4.dp))
                        Text("Get free key at ${config.signupUrl.removePrefix("https://").take(30)}",
                            color = CosmicAccent.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (key.isNotBlank()) {
                        EmbeddedProviderConfig.saveKey(context, config, key)
                        onSave(key)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CosmicAccent,
                    contentColor   = AiriTheme.background
                ),
                enabled = key.isNotBlank()
            ) {
                Text(stringResource(R.string.cloud_save_activate), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
            }
        }
    )
}
@Composable
private fun AddRemoteModelInlineContent(
    viewModel:   ChatViewModel,
    onActivated: () -> Unit
) {
    var modelName  by remember { mutableStateOf("") }
    var serverUrl  by remember { mutableStateOf("") }
    var apiKey     by remember { mutableStateOf("") }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting  by remember { mutableStateOf(false) }
    val executor = remember { com.airi.assistant.ai.remote.RemoteModelExecutor() }
    val scope    = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = modelName, onValueChange = { modelName = it },
            label = { Text(stringResource(R.string.model_name_label), color = AiriTheme.onSurfaceVariant) },
            placeholder = { Text(stringResource(R.string.cloud_model_name_placeholder), color = AiriTheme.outline) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CosmicAccent,
                unfocusedBorderColor = AiriTheme.onSurface.copy(alpha = 0.15f),
                focusedTextColor = AiriTheme.onSurface, unfocusedTextColor = AiriTheme.onSurface
            )
        )
        OutlinedTextField(
            value = serverUrl, onValueChange = { serverUrl = it },
            label = { Text(stringResource(R.string.server_url), color = AiriTheme.onSurfaceVariant) },
            placeholder = { Text("http://192.168.x.x:8080/v1", color = AiriTheme.outline) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CosmicAccent,
                unfocusedBorderColor = AiriTheme.onSurface.copy(alpha = 0.15f),
                focusedTextColor = AiriTheme.onSurface, unfocusedTextColor = AiriTheme.onSurface
            )
        )
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.cloud_api_key_optional_label), color = AiriTheme.onSurfaceVariant) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CosmicAccent,
                unfocusedBorderColor = AiriTheme.onSurface.copy(alpha = 0.15f),
                focusedTextColor = AiriTheme.onSurface, unfocusedTextColor = AiriTheme.onSurface
            )
        )
        testStatus?.let {
            Surface(
                shape = AIRIShapes.xs,
                color = if (it.startsWith("✓")) Color(0xFF1B5E20) else Color(0xFF7F0000)
            ) {
                Text(it, color = AiriTheme.onBackground, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        isTesting = true; testStatus = null
                        val remote = com.airi.assistant.ai.remote.RemoteModel(
                            id = "test", name = "test", serverUrl = serverUrl, apiKey = apiKey)
                        val ok = executor.testConnection(remote)
                        testStatus = if (ok) "✓ Connection successful" else "✗ Connection failed"
                        isTesting = false
                    }
                },
                enabled = serverUrl.isNotBlank() && !isTesting,
                shape = AIRIShapes.sm
            ) {
                if (isTesting) CircularProgressIndicator(modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp, color = CosmicAccent)
                else Text(stringResource(R.string.test), color = CosmicAccent, fontSize = 12.sp)
            }
            Button(
                onClick = {
                    val remote = com.airi.assistant.ai.remote.RemoteModel(
                        id               = java.util.UUID.randomUUID().toString(),
                        name             = modelName.ifBlank { "Custom Server" },
                        serverUrl        = serverUrl.trimEnd('/'),
                        apiKey           = apiKey,
                        // : user-created endpoints must be flagged so
                        // migrateStaleModelNames() never renames them.
                        isCustomEndpoint = true
                    )
                    com.airi.assistant.ai.remote.RemoteModelRegistry.add(remote)
                    viewModel.activateRemoteModel(remote)
                    onActivated()
                },
                enabled = serverUrl.isNotBlank() && modelName.isNotBlank(),
                shape = AIRIShapes.sm,
                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = AiriTheme.background)
            ) {
                Text(stringResource(R.string.cloud_add_use), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
