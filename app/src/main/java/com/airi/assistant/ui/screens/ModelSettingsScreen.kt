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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
                title = { Text("Model Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                        title = "Recommended for Your Device",
                        subtitle = "Filtered by RAM profile so weak devices are not offered oversized models"
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
                    title = "Model Store",
                    subtitle = "Production GGUF models with model type, RAM, context, and quantization metadata"
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
                    title = "Local Models",
                    subtitle = "Downloaded, imported, and scanned GGUF files available on this device"
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
            title = { Text("Delete model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Remove ${model.name} from AIRI?")
                    Text(
                        if (model.source == ModelSource.DOWNLOADED) {
                            "The downloaded GGUF file will be deleted from AIRI model storage."
                        } else {
                            "The registry entry will be removed. External files are only deleted when Android grants write access."
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
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { modelPendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    modelState.loadError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearModelError() },
            title = { Text("Model error") },
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
                    Text("OK")
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
                    Text("Active Model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        activeModel?.name ?: state.selectedModelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusChip(
                    label = when {
                        state.isModelLoading -> "Loading"
                        state.isModelReady -> "Active"
                        else -> "Not active"
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
                        "RAM ${model.ramRequiredMb.takeIf { it > 0 } ?: "?"} MB",
                        "CTX ${model.contextSize.contextLabel()}"
                    )
                )
            }
            if (state.isModelLoading) {
                if (state.loadProgress in 0..100) {
                    LinearProgressIndicator(progress = state.loadProgress / 100f, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text("Loading model into memory…", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onOpenGenerationSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Generation Settings")
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
                Text("Scan Device", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Search Download, Documents, and AIRI folders for .gguf files",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(12.dp))
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            } else {
                Button(onClick = onScan) { Text("Scan") }
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
                            StatusChip("Recommended", ChipTone.INFO)
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
                    "RAM ${entry.ramRequiredMb} MB",
                    "CTX ${entry.contextSize.contextLabel()}"
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
                        isActive -> StatusChip("Active", ChipTone.SUCCESS)
                        isLoadingThisEntry -> StatusChip("Loading", ChipTone.WARNING)
                        isDownloaded -> StatusChip("Downloaded", ChipTone.SUCCESS)
                        else -> StatusChip("Not installed", ChipTone.NEUTRAL)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDownload,
                        enabled = !isDownloaded && !isAnyLoading
                    ) { Text("Download") }
                    Button(
                        onClick = onActivate,
                        enabled = isDownloaded && !isActive && !isAnyLoading
                    ) { Text(if (isLoadingThisEntry) "Loading…" else "Activate") }
                }
            }
        }
    }
}

@Composable
fun ImportModelCard(state: ModelUiState, onPickModel: () -> Unit) {
    ModelCard(
        icon = "G",
        title = "Add local GGUF model",
        subtitle = "Choose a .gguf file from storage and add it to the registry.",
        metaItems = emptyList(),
        type = null,
        status = if (state.isModelLoading) "Loading" else "Import",
        error = state.loadError,
        errorType = state.loadErrorType,
        loadProgress = state.loadProgress,
        isLoadingThis = state.isModelLoading,
        actionText = "Import .gguf",
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
        if (model.ramRequiredMb > 0) add("RAM ${model.ramRequiredMb} MB")
        if (model.contextSize > 0) add("CTX ${model.contextSize.contextLabel()}")
    }
    ModelCard(
        icon = model.type.label.first().uppercase(),
        title = model.name,
        subtitle = model.path,
        metaItems = meta,
        type = model.type,
        status = when {
            isLoadingThisModel -> "Loading"
            isActive -> "Active"
            else -> "Downloaded"
        },
        error = if (isActive || isLoadingThisModel) state.loadError else null,
        errorType = if (isActive || isLoadingThisModel) state.loadErrorType else LoadErrorType.NONE,
        loadProgress = if (isLoadingThisModel) state.loadProgress else -1,
        isLoadingThis = isLoadingThisModel,
        actionText = when {
            isActive -> "Active"
            isLoadingThisModel -> "Loading…"
            else -> "Activate"
        },
        actionEnabled = !isActive && !state.isModelLoading,
        extraLabel = if (isScanned) "Detected automatically" else null,
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
            Text("No local models yet", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Download a model from the store above, import a .gguf file, or tap Scan to detect models on your device.")
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
                        "Active", "Downloaded" -> ChipTone.SUCCESS
                        "Loading" -> ChipTone.WARNING
                        else -> ChipTone.NEUTRAL
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onDelete?.let {
                        OutlinedButton(onClick = it, enabled = !isLoadingThis) { Text("Delete") }
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
        title = { Text("Generation Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Settings are persisted and constrained by the active model metadata.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )

                SettingSlider(
                    title = "Temperature",
                    valueLabel = "%.1f".format(temperature),
                    value = temperature,
                    valueRange = 0.1f..2.0f,
                    steps = 18,
                    onValueChange = { viewModel.setTemperature(it) }
                )

                SettingSlider(
                    title = "Max Tokens",
                    valueLabel = "$maxTokens",
                    value = maxTokens.toFloat(),
                    valueRange = 64f..2048f,
                    steps = 15,
                    onValueChange = { viewModel.setMaxTokens(it.toInt()) }
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Context Size", style = MaterialTheme.typography.labelLarge)
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
                            "Gemma uses more memory at high context. AIRI keeps context choices inside the model limit.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                SettingSlider(
                    title = "Top-K",
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
                    title = "Top-P",
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
                    Text("System Prompt", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { viewModel.setSystemPrompt(it) },
                        placeholder = { Text("Leave empty to use AIRI default") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
