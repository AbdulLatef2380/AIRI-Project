package com.airi.assistant.ui.screens

import android.net.Uri
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val modelState by viewModel.modelState.collectAsState()
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.importModel(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshDownloadedModelState()
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
            item {
                Text(
                    "Model Store",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "نماذج GGUF حقيقية جاهزة للتحميل مباشرة من HuggingFace",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(
                items = modelState.catalogModels,
                key = { it.id }
            ) { entry ->
                val isDownloaded = modelState.availableModels.any { m -> m.fileName == entry.fileName }
                val isActive = modelState.isModelReady && modelState.availableModels.any { m ->
                    m.fileName == entry.fileName && m.id == modelState.selectedModelId
                }
                val isLoadingThisEntry = modelState.isModelLoading &&
                    modelState.availableModels.any { m -> m.fileName == entry.fileName && m.id == modelState.selectedModelId }

                CatalogCard(
                    entry = entry,
                    isDownloaded = isDownloaded,
                    isActive = isActive,
                    isLoadingThisEntry = isLoadingThisEntry,
                    isAnyLoading = modelState.isModelLoading,
                    loadProgress = modelState.loadProgress,
                    onDownload = { viewModel.downloadCatalogModel(entry) },
                    onActivate = { viewModel.activateCatalogDownload(entry) }
                )
            }

            item { Spacer(Modifier.height(4.dp)) }

            item {
                Text(
                    "Local Models",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            item {
                ImportModelCard(
                    state = modelState,
                    onPickModel = { modelPicker.launch("*/*") }
                )
            }

            if (modelState.availableModels.isEmpty()) {
                item { EmptyModelRegistryCard() }
            } else {
                items(
                    items = modelState.availableModels,
                    key = { it.id }
                ) { model ->
                    RegistryModelCard(
                        model = model,
                        state = modelState,
                        onActivate = { viewModel.selectModel(model.id) }
                    )
                }
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
    onDownload: () -> Unit,
    onActivate: () -> Unit
) {
    val statusLabel = when {
        isActive -> "Active"
        isLoadingThisEntry -> "Loading"
        isDownloaded -> "Downloaded"
        else -> "Not downloaded"
    }
    val actionText = when {
        isActive -> "Active"
        isLoadingThisEntry -> "Loading..."
        isDownloaded -> "Activate"
        else -> "Download"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            entry.name.first().uppercase(),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.name, fontWeight = FontWeight.Bold)
                    Text(entry.description, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetaChip("${entry.quantization}")
                MetaChip(entry.sizeBytes.toReadableSize())
                MetaChip("RAM: ${entry.ramRequiredMb} MB")
                MetaChip("CTX: ${entry.contextSize / 1024}K")
            }

            Spacer(Modifier.height(10.dp))

            if (isLoadingThisEntry && loadProgress in 0..100) {
                LinearProgressIndicator(
                    progress = loadProgress / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(onClick = {}, enabled = false, label = { Text(statusLabel) })
                Button(
                    onClick = { if (isDownloaded) onActivate() else onDownload() },
                    enabled = !isActive && !isAnyLoading
                ) {
                    Text(actionText)
                }
            }
        }
    }
}

@Composable
fun MetaChip(label: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun ImportModelCard(
    state: ModelUiState,
    onPickModel: () -> Unit
) {
    ModelCard(
        icon = "L",
        title = "Add local GGUF model",
        subtitle = "Choose a .gguf file from Android storage and save it to the local registry.",
        metaItems = emptyList(),
        status = when {
            state.isModelLoading -> "Loading"
            else -> "Import"
        },
        error = state.loadError,
        errorType = state.loadErrorType,
        loadProgress = state.loadProgress,
        isLoadingThis = state.isModelLoading,
        actionText = "Import .gguf",
        actionEnabled = !state.isModelLoading,
        onAction = onPickModel
    )
}

@Composable
fun RegistryModelCard(
    model: ModelInfo,
    state: ModelUiState,
    onActivate: () -> Unit
) {
    val isActive = state.isModelReady && state.selectedModelId == model.id
    val isLoadingThisModel = state.isModelLoading && state.selectedModelId == model.id

    val meta = buildList {
        add(model.quantization)
        add(model.size.toReadableSize())
        if (model.ramRequiredMb > 0) add("RAM: ${model.ramRequiredMb} MB")
        if (model.contextSize > 0) add("CTX: ${model.contextSize / 1024}K")
    }

    ModelCard(
        icon = model.type.firstOrNull()?.uppercase() ?: "M",
        title = model.name,
        subtitle = model.path,
        metaItems = meta,
        status = when {
            isLoadingThisModel -> "Loading"
            isActive -> "Active"
            else -> "Saved"
        },
        error = if (isActive || isLoadingThisModel) state.loadError else null,
        errorType = if (isActive || isLoadingThisModel) state.loadErrorType else LoadErrorType.NONE,
        loadProgress = if (isLoadingThisModel) state.loadProgress else -1,
        isLoadingThis = isLoadingThisModel,
        actionText = when {
            isActive -> "Active"
            isLoadingThisModel -> "Loading..."
            else -> "Activate"
        },
        actionEnabled = !isActive && !state.isModelLoading,
        onAction = onActivate
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
            Text("No saved local models yet", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Download a model from the store above, or import a .gguf file from storage.")
        }
    }
}

@Composable
fun ModelCard(
    icon: String,
    title: String,
    subtitle: String,
    metaItems: List<String>,
    status: String,
    error: String?,
    errorType: LoadErrorType,
    loadProgress: Int,
    isLoadingThis: Boolean,
    actionText: String,
    actionEnabled: Boolean = true,
    onAction: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(icon, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (metaItems.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    metaItems.forEach { MetaChip(it) }
                }
            }

            if (isLoadingThis && loadProgress in 0..100) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = loadProgress / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (isLoadingThis) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(onClick = {}, enabled = false, label = { Text(status) })
                Button(onClick = onAction, enabled = actionEnabled) {
                    Text(actionText)
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                val errorColor = when (errorType) {
                    LoadErrorType.INSUFFICIENT_RAM -> MaterialTheme.colorScheme.tertiary
                    LoadErrorType.INVALID_FORMAT, LoadErrorType.TOO_SMALL -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.error
                }
                Text(it, color = errorColor, style = MaterialTheme.typography.bodySmall)
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
