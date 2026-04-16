package com.airi.assistant.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.airi.assistant.R
import com.airi.assistant.ai.CatalogEntry
import com.airi.assistant.ai.ModelInfo
import com.airi.assistant.ai.ModelManager
import com.airi.assistant.ai.ModelSource
import com.airi.assistant.ai.ModelType
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.ui.viewmodel.LoadErrorType
import com.airi.assistant.ui.viewmodel.ModelUiState
import com.google.gson.Gson
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val modelState by viewModel.modelState.collectAsState()
    var showGenerationSettings by remember { mutableStateOf(false) }
    var modelPendingDelete by remember { mutableStateOf<ModelInfo?>(null) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.importModel(it) }
    }

    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.scanForLocalModels()
    }

    val onScanClick: () -> Unit = {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            val perm = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                viewModel.scanForLocalModels()
            } else {
                storagePermLauncher.launch(perm)
            }
        } else {
            viewModel.scanForLocalModels()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshDownloadedModelState()
        viewModel.refreshRecommendedModels()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ActiveModelSummaryCard(
                    state = modelState,
                    onOpenGenerationSettings = { showGenerationSettings = true }
                )
            }

            if (modelState.recommendedModels.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.recommended_for_device),
                        subtitle = stringResource(R.string.recommended_for_device_subtitle)
                    )
                }
                items(items = modelState.recommendedModels, key = { "rec_${it.id}" }) { entry ->
                    CatalogCard(
                        entry = entry,
                        isDownloaded = modelState.availableModels.any { m -> m.fileName == entry.fileName },
                        isActive = modelState.isModelReady && modelState.availableModels.any { m ->
                            m.fileName == entry.fileName && m.id == modelState.selectedModelId
                        },
                        isLoadingThisEntry = modelState.isModelLoading && modelState.availableModels.any { m ->
                            m.fileName == entry.fileName && m.id == modelState.selectedModelId
                        },
                        isAnyLoading = modelState.isModelLoading,
                        loadProgress = modelState.loadProgress,
                        showRecommendedBadge = true,
                        onDownload = { viewModel.downloadCatalogModel(entry) },
                        onActivate = { viewModel.activateCatalogDownload(entry) }
                    )
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.model_store),
                    subtitle = stringResource(R.string.model_store_subtitle)
                )
            }
            items(items = modelState.catalogModels, key = { "catalog_${it.id}" }) { entry ->
                CatalogCard(
                    entry = entry,
                    isDownloaded = modelState.availableModels.any { m -> m.fileName == entry.fileName },
                    isActive = modelState.isModelReady && modelState.availableModels.any { m ->
                        m.fileName == entry.fileName && m.id == modelState.selectedModelId
                    },
                    isLoadingThisEntry = modelState.isModelLoading && modelState.availableModels.any { m ->
                        m.fileName == entry.fileName && m.id == modelState.selectedModelId
                    },
                    isAnyLoading = modelState.isModelLoading,
                    loadProgress = modelState.loadProgress,
                    showRecommendedBadge = false,
                    onDownload = { viewModel.downloadCatalogModel(entry) },
                    onActivate = { viewModel.activateCatalogDownload(entry) }
                )
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.local_models),
                    subtitle = stringResource(R.string.local_models_subtitle)
                )
            }
            item { ScanDeviceCard(isScanning = modelState.isScanning, onScan = onScanClick) }
            item { ImportModelCard(state = modelState, onPickModel = { modelPicker.launch("*/*") }) }

            if (modelState.availableModels.isEmpty()) {
                item { EmptyModelRegistryCard() }
            } else {
                items(items = modelState.availableModels, key = { "reg_${it.id}" }) { model ->
                    RegistryModelCard(
                        model = model,
                        state = modelState,
                        isScanned = modelState.scannedModelIds.contains(model.id),
                        onActivate = { viewModel.selectModel(model.id) },
                        onDelete = { modelPendingDelete = model }
                    )
                }
            }
        }
    }

    if (showGenerationSettings) {
        AdvancedGenerationSettingsDialog(
            viewModel = viewModel,
            state = modelState,
            onDismiss = { showGenerationSettings = false }
        )
    }

    modelPendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { modelPendingDelete = null },
            title = { Text(stringResource(R.string.delete_model)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.remove_model_from_airi, model.name))
                    Text(
                        if (model.source == ModelSource.DOWNLOADED) {
                            stringResource(R.string.downloaded_gguf_deleted)
                        } else {
                            stringResource(R.string.registry_entry_removed)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteLocalModel(context, model, modelState)
                        viewModel.refreshDownloadedModelState()
                        modelPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { modelPendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    modelState.loadError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearModelError() },
            title = { Text(stringResource(R.string.model_error)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error)
                    Text(
                        modelState.loadErrorType.name,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.clearModelError() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

@Composable
fun ActiveModelSummaryCard(state: ModelUiState, onOpenGenerationSettings: () -> Unit) {
    val activeModel = state.availableModels.firstOrNull { it.id == state.selectedModelId }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.active_model), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        activeModel?.name ?: state.selectedModelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusChip(
                    label = when {
                        state.isModelLoading -> stringResource(R.string.loading)
                        state.isModelReady -> stringResource(R.string.active)
                        else -> stringResource(R.string.not_active)
                    },
                    tone = when {
                        state.isModelLoading -> ChipTone.WARNING
                        state.isModelReady -> ChipTone.SUCCESS
                        else -> ChipTone.NEUTRAL
                    }
                )
            }
            activeModel?.let { model ->
                MetadataRow(
                    items = listOf(
                        model.type.label,
                        model.quantization,
                        model.size.toReadableSize(),
                        stringResource(R.string.ram_mb, "${model.ramRequiredMb.takeIf { it > 0 } ?: "?"}"),
                        stringResource(R.string.ctx_value, model.contextSize.contextLabel())
                    )
                )
            }
            if (state.isModelLoading) {
                if (state.loadProgress in 0..100) {
                    LinearProgressIndicator(progress = state.loadProgress / 100f, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.loading_model_memory), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onOpenGenerationSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.generation_settings))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ScanDeviceCard(isScanning: Boolean, onScan: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.scan_device), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.scan_device_subtitle),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(12.dp))
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            } else {
                Button(onClick = onScan) { Text(stringResource(R.string.scan)) }
            }
        }
    }
}

@Composable
fun CatalogCard(
    entry: CatalogEntry,
    isDownloaded: Boolean,
    isActive: Boolean,
    isLoadingThisEntry: Boolean,
    isAnyLoading: Boolean,
    loadProgress: Int,
    showRecommendedBadge: Boolean,
    onDownload: () -> Unit,
    onActivate: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeAvatar(type = entry.type)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        if (showRecommendedBadge) {
                            Spacer(Modifier.width(8.dp))
                            StatusChip(stringResource(R.string.recommended), ChipTone.INFO)
                        }
                    }
                    Text(entry.description, style = MaterialTheme.typography.bodySmall)
                }
            }

            MetadataRow(
                items = listOf(
                    entry.type.label,
                    entry.quantization,
                    entry.sizeBytes.toReadableSize(),
                    stringResource(R.string.ram_mb, "${entry.ramRequiredMb}"),
                    stringResource(R.string.ctx_value, entry.contextSize.contextLabel())
                )
            )

            if (isLoadingThisEntry) {
                if (loadProgress in 0..100) {
                    LinearProgressIndicator(progress = loadProgress / 100f, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        isActive -> StatusChip(stringResource(R.string.active), ChipTone.SUCCESS)
                        isLoadingThisEntry -> StatusChip(stringResource(R.string.loading), ChipTone.WARNING)
                        isDownloaded -> StatusChip(stringResource(R.string.downloaded), ChipTone.SUCCESS)
                        else -> StatusChip(stringResource(R.string.not_installed), ChipTone.NEUTRAL)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDownload,
                        enabled = !isDownloaded && !isAnyLoading
                    ) { Text(stringResource(R.string.download)) }
                    Button(
                        onClick = onActivate,
                        enabled = isDownloaded && !isActive && !isAnyLoading
                    ) { Text(if (isLoadingThisEntry) stringResource(R.string.loading_ellipsis) else stringResource(R.string.activate)) }
                }
            }
        }
    }
}

@Composable
fun ImportModelCard(state: ModelUiState, onPickModel: () -> Unit) {
    ModelCard(
        icon = "G",
        title = stringResource(R.string.add_local_gguf_model),
        subtitle = stringResource(R.string.choose_gguf_file),
        metaItems = emptyList(),
        type = null,
        status = if (state.isModelLoading) stringResource(R.string.loading) else stringResource(R.string.import_action),
        error = state.loadError,
        errorType = state.loadErrorType,
        loadProgress = state.loadProgress,
        isLoadingThis = state.isModelLoading,
        actionText = stringResource(R.string.import_gguf),
        actionEnabled = !state.isModelLoading,
        extraLabel = null,
        onAction = onPickModel,
        onDelete = null
    )
}

@Composable
fun RegistryModelCard(
    model: ModelInfo,
    state: ModelUiState,
    isScanned: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit
) {
    val isActive = state.isModelReady && state.selectedModelId == model.id
    val isLoadingThisModel = state.isModelLoading && state.selectedModelId == model.id
    val meta = buildList {
        add(model.quantization)
        add(model.size.toReadableSize())
        if (model.ramRequiredMb > 0) add(stringResource(R.string.ram_mb, "${model.ramRequiredMb}"))
        if (model.contextSize > 0) add(stringResource(R.string.ctx_value, model.contextSize.contextLabel()))
    }
    ModelCard(
        icon = model.type.label.first().uppercase(),
        title = model.name,
        subtitle = model.path,
        metaItems = meta,
        type = model.type,
        status = when {
            isLoadingThisModel -> stringResource(R.string.loading)
            isActive -> stringResource(R.string.active)
            else -> stringResource(R.string.downloaded)
        },
        error = if (isActive || isLoadingThisModel) state.loadError else null,
        errorType = if (isActive || isLoadingThisModel) state.loadErrorType else LoadErrorType.NONE,
        loadProgress = if (isLoadingThisModel) state.loadProgress else -1,
        isLoadingThis = isLoadingThisModel,
        actionText = when {
            isActive -> stringResource(R.string.active)
            isLoadingThisModel -> stringResource(R.string.loading_ellipsis)
            else -> stringResource(R.string.activate)
        },
        actionEnabled = !isActive && !state.isModelLoading,
        extraLabel = if (isScanned) stringResource(R.string.detected_automatically) else null,
        onAction = onActivate,
        onDelete = onDelete
    )
}

@Composable
fun EmptyModelRegistryCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.no_local_models_yet), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.no_local_models_description))
        }
    }
}

@Composable
fun ModelCard(
    icon: String,
    title: String,
    subtitle: String,
    metaItems: List<String>,
    type: ModelType?,
    status: String,
    error: String?,
    errorType: LoadErrorType,
    loadProgress: Int,
    isLoadingThis: Boolean,
    actionText: String,
    actionEnabled: Boolean = true,
    extraLabel: String?,
    onAction: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (type != null) TypeAvatar(type = type) else StaticAvatar(icon = icon)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    extraLabel?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            val metadata = buildList {
                type?.let { add(it.label) }
                addAll(metaItems)
            }
            if (metadata.isNotEmpty()) {
                MetadataRow(items = metadata)
            }

            when {
                isLoadingThis && loadProgress in 0..100 -> LinearProgressIndicator(
                    progress = loadProgress / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
                isLoadingThis -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusChip(
                    label = status,
                    tone = when (status) {
                        stringResource(R.string.active), stringResource(R.string.downloaded) -> ChipTone.SUCCESS
                        stringResource(R.string.loading), stringResource(R.string.loading_ellipsis) -> ChipTone.WARNING
                        else -> ChipTone.NEUTRAL
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onDelete?.let {
                        OutlinedButton(onClick = it, enabled = !isLoadingThis) { Text(stringResource(R.string.delete)) }
                    }
                    Button(onClick = onAction, enabled = actionEnabled) { Text(actionText) }
                }
            }

            error?.let {
                Text(
                    it,
                    color = when (errorType) {
                        LoadErrorType.INSUFFICIENT_RAM -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun AdvancedGenerationSettingsDialog(
    viewModel: ChatViewModel,
    state: ModelUiState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("airi_ui_state", Context.MODE_PRIVATE) }
    val temperature by viewModel.temperature.collectAsState()
    val maxTokens by viewModel.maxTokens.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val activeModel = state.availableModels.firstOrNull { it.id == state.selectedModelId }
    val maxContext = activeModel?.contextSize?.takeIf { it > 0 } ?: 4096
    val contextOptions = remember(maxContext) {
        listOf(2048, 4096, 8192, 16384, 32768).filter { it <= maxContext }.ifEmpty { listOf(maxContext) }
    }
    var selectedContext by remember(maxContext) {
        mutableStateOf(prefs.getInt("gen_context_size", minOf(4096, maxContext)).coerceAtMost(maxContext))
    }
    var topK by remember { mutableStateOf(prefs.getInt("gen_top_k", 40)) }
    var topP by remember { mutableStateOf(prefs.getFloat("gen_top_p", 0.9f)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.generation_settings), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.advanced_generation_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )

                SettingSlider(
                    title = stringResource(R.string.temperature),
                    valueLabel = "%.1f".format(temperature),
                    value = temperature,
                    valueRange = 0.1f..2.0f,
                    steps = 18,
                    onValueChange = { viewModel.setTemperature(it) }
                )

                SettingSlider(
                    title = stringResource(R.string.max_tokens),
                    valueLabel = "$maxTokens",
                    value = maxTokens.toFloat(),
                    valueRange = 64f..2048f,
                    steps = 15,
                    onValueChange = { viewModel.setMaxTokens(it.toInt()) }
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.context_size), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        contextOptions.forEach { option ->
                            FilterChip(
                                selected = selectedContext == option,
                                onClick = {
                                    selectedContext = option
                                    prefs.edit().putInt("gen_context_size", option).apply()
                                },
                                label = { Text(option.contextLabel()) }
                            )
                        }
                    }
                    if (activeModel?.type == ModelType.GEMMA) {
                        Text(
                            stringResource(R.string.gemma_context_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                SettingSlider(
                    title = stringResource(R.string.top_k),
                    valueLabel = "$topK",
                    value = topK.toFloat(),
                    valueRange = 1f..100f,
                    steps = 98,
                    onValueChange = {
                        topK = it.toInt()
                        prefs.edit().putInt("gen_top_k", topK).apply()
                    }
                )

                SettingSlider(
                    title = stringResource(R.string.top_p),
                    valueLabel = "%.2f".format(topP),
                    value = topP,
                    valueRange = 0.1f..1.0f,
                    steps = 17,
                    onValueChange = {
                        topP = it
                        prefs.edit().putFloat("gen_top_p", topP).apply()
                    }
                )

                Column {
                    Text(stringResource(R.string.system_prompt), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { viewModel.setSystemPrompt(it) },
                        placeholder = { Text(stringResource(R.string.leave_empty_airi_default)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(valueLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    }
}

@Composable
fun MetadataRow(items: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        items.forEach { MetaChip(it) }
    }
}

@Composable
fun MetaChip(label: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private enum class ChipTone { SUCCESS, WARNING, INFO, NEUTRAL }

@Composable
private fun StatusChip(label: String, tone: ChipTone) {
    val (container, content) = when (tone) {
        ChipTone.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ChipTone.WARNING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ChipTone.INFO -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        ChipTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(20.dp), color = container, contentColor = content) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TypeAvatar(type: ModelType) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(50.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(type.label.first().uppercase(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun StaticAvatar(icon: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(50.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(icon, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun deleteLocalModel(context: Context, model: ModelInfo, state: ModelUiState) {
    ModelManager.remove(model)
    if (model.source == ModelSource.DOWNLOADED || model.path.contains(context.packageName)) {
        runCatching { File(model.path).delete() }
    }
    val prefs = context.getSharedPreferences("airi_ui_state", Context.MODE_PRIVATE)
    val editor = prefs.edit()
    editor.putString("model_registry_json", Gson().toJson(ModelManager.getAllModels()))
    if (state.selectedModelId == model.id) {
        editor.remove("selected_model_id")
        editor.remove("selected_model_path")
    }
    editor.apply()
}

private fun Long.toReadableSize(): String {
    if (this <= 0L) return "0 MB"
    val mb = this / (1024.0 * 1024.0)
    val gb = mb / 1024.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(mb)
}

private fun Int.contextLabel(): String {
    return if (this >= 1024) "${this / 1024}K" else "$this"
}
