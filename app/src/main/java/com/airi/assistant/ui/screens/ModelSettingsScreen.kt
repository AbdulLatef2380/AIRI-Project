package com.airi.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.ui.viewmodel.LoadErrorType
import com.airi.assistant.ui.viewmodel.ModelUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val modelState by viewModel.modelState.collectAsState()

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
                title = { Text("Model Gallery") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── SECTION 1: Recommended ────────────────────────────────
            if (modelState.recommendedModels.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Recommended for Your Device",
                        subtitle = "Filtered by your device RAM and CPU profile"
                    )
                }
                items(items = modelState.recommendedModels, key = { "rec_${it.id}" }) { entry ->
                    val isDownloaded = modelState.availableModels.any { m -> m.fileName == entry.fileName }
                    val isActive = modelState.isModelReady && modelState.availableModels.any { m ->
                        m.fileName == entry.fileName && m.id == modelState.selectedModelId
                    }
                    val isLoadingThisEntry = modelState.isModelLoading &&
                        modelState.availableModels.any { m ->
                            m.fileName == entry.fileName && m.id == modelState.selectedModelId
                        }
                    CatalogCard(
                        entry = entry,
                        isDownloaded = isDownloaded,
                        isActive = isActive,
                        isLoadingThisEntry = isLoadingThisEntry,
                        isAnyLoading = modelState.isModelLoading,
                        loadProgress = modelState.loadProgress,
                        showRecommendedBadge = true,
                        onDownload = { viewModel.downloadCatalogModel(entry) },
                        onActivate = { viewModel.activateCatalogDownload(entry) }
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
            }

            // ── SECTION 2: Model Store ────────────────────────────────
            item {
                SectionHeader(
                    title = "Model Store",
                    subtitle = "Real GGUF models — download directly from HuggingFace"
                )
            }
            items(items = modelState.catalogModels, key = { "catalog_${it.id}" }) { entry ->
                val isDownloaded = modelState.availableModels.any { m -> m.fileName == entry.fileName }
                val isActive = modelState.isModelReady && modelState.availableModels.any { m ->
                    m.fileName == entry.fileName && m.id == modelState.selectedModelId
                }
                val isLoadingThisEntry = modelState.isModelLoading &&
                    modelState.availableModels.any { m ->
                        m.fileName == entry.fileName && m.id == modelState.selectedModelId
                    }
                CatalogCard(
                    entry = entry,
                    isDownloaded = isDownloaded,
                    isActive = isActive,
                    isLoadingThisEntry = isLoadingThisEntry,
                    isAnyLoading = modelState.isModelLoading,
                    loadProgress = modelState.loadProgress,
                    showRecommendedBadge = false,
                    onDownload = { viewModel.downloadCatalogModel(entry) },
                    onActivate = { viewModel.activateCatalogDownload(entry) }
                )
            }
            item { Spacer(Modifier.height(4.dp)) }

            // ── SECTION 3: Scan + Import ──────────────────────────────
            item {
                SectionHeader(
                    title = "Local Models",
                    subtitle = "Scan your device or import a .gguf file manually"
                )
            }
            item { ScanDeviceCard(isScanning = modelState.isScanning, onScan = onScanClick) }
            item { ImportModelCard(state = modelState, onPickModel = { modelPicker.launch("*/*") }) }

            // ── SECTION 4: Registry ───────────────────────────────────
            if (modelState.availableModels.isEmpty()) {
                item { EmptyModelRegistryCard() }
            } else {
                items(items = modelState.availableModels, key = { "reg_${it.id}" }) { model ->
                    RegistryModelCard(
                        model = model,
                        state = modelState,
                        isScanned = modelState.scannedModelIds.contains(model.id),
                        onActivate = { viewModel.selectModel(model.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ScanDeviceCard(isScanning: Boolean, onScan: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 2.dp, shadowElevation = 2.dp
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Scan Device", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text("Search Download, Documents, and AIRI folders for .gguf files",
                    style = MaterialTheme.typography.bodySmall)
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
    val statusLabel = when {
        isActive -> "Active"; isLoadingThisEntry -> "Loading"
        isDownloaded -> "Downloaded"; else -> "Not downloaded"
    }
    val actionText = when {
        isActive -> "Active"; isLoadingThisEntry -> "Loading…"
        isDownloaded -> "Activate"; else -> "Download"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp, shadowElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(entry.name.first().uppercase(), fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.name, fontWeight = FontWeight.Bold)
                        if (showRecommendedBadge) {
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                                Text("Recommended",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(entry.description, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetaChip(entry.quantization)
                MetaChip(entry.sizeBytes.toReadableSize())
                MetaChip("RAM: ${entry.ramRequiredMb} MB")
                MetaChip("CTX: ${entry.contextSize / 1024}K")
            }
            Spacer(Modifier.height(10.dp))
            if (isLoadingThisEntry && loadProgress in 0..100) {
                LinearProgressIndicator(progress = loadProgress / 100f, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, enabled = false, label = { Text(statusLabel) })
                Button(onClick = { if (isDownloaded) onActivate() else onDownload() },
                    enabled = !isActive && !isAnyLoading) { Text(actionText) }
            }
        }
    }
}

@Composable
fun MetaChip(label: String) {
    Surface(shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ImportModelCard(state: ModelUiState, onPickModel: () -> Unit) {
    ModelCard(
        icon = "L", title = "Add local GGUF model",
        subtitle = "Choose a .gguf file from storage and add it to the registry.",
        metaItems = emptyList(),
        status = if (state.isModelLoading) "Loading" else "Import",
        error = state.loadError, errorType = state.loadErrorType,
        loadProgress = state.loadProgress, isLoadingThis = state.isModelLoading,
        actionText = "Import .gguf", actionEnabled = !state.isModelLoading,
        extraLabel = null, onAction = onPickModel
    )
}

@Composable
fun RegistryModelCard(model: ModelInfo, state: ModelUiState, isScanned: Boolean, onActivate: () -> Unit) {
    val isActive = state.isModelReady && state.selectedModelId == model.id
    val isLoadingThisModel = state.isModelLoading && state.selectedModelId == model.id
    val meta = buildList {
        add(model.quantization); add(model.size.toReadableSize())
        if (model.ramRequiredMb > 0) add("RAM: ${model.ramRequiredMb} MB")
        if (model.contextSize > 0) add("CTX: ${model.contextSize / 1024}K")
    }
    ModelCard(
        icon = model.type.firstOrNull()?.uppercase() ?: "M",
        title = model.name, subtitle = model.path, metaItems = meta,
        status = when { isLoadingThisModel -> "Loading"; isActive -> "Active"; else -> "Saved" },
        error = if (isActive || isLoadingThisModel) state.loadError else null,
        errorType = if (isActive || isLoadingThisModel) state.loadErrorType else LoadErrorType.NONE,
        loadProgress = if (isLoadingThisModel) state.loadProgress else -1,
        isLoadingThis = isLoadingThisModel,
        actionText = when { isActive -> "Active"; isLoadingThisModel -> "Loading…"; else -> "Activate" },
        actionEnabled = !isActive && !state.isModelLoading,
        extraLabel = if (isScanned) "Detected automatically" else null,
        onAction = onActivate
    )
}

@Composable
fun EmptyModelRegistryCard() {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant, tonalElevation = 2.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("No local models yet", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Download a model from the store above, import a .gguf file, or tap Scan to detect models on your device.")
        }
    }
}

@Composable
fun ModelCard(
    icon: String, title: String, subtitle: String, metaItems: List<String>,
    status: String, error: String?, errorType: LoadErrorType, loadProgress: Int,
    isLoadingThis: Boolean, actionText: String, actionEnabled: Boolean = true,
    extraLabel: String?, onAction: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(icon, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    extraLabel?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            if (metaItems.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { metaItems.forEach { MetaChip(it) } }
            }
            when {
                isLoadingThis && loadProgress in 0..100 -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = loadProgress / 100f, modifier = Modifier.fillMaxWidth())
                }
                isLoadingThis -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, enabled = false, label = { Text(status) })
                Button(onClick = onAction, enabled = actionEnabled) { Text(actionText) }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = when (errorType) {
                    LoadErrorType.INSUFFICIENT_RAM -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun Long.toReadableSize(): String {
    if (this <= 0L) return "0 MB"
    val mb = this / (1024.0 * 1024.0)
    val gb = mb / 1024.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(mb)
}
