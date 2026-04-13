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
import com.airi.assistant.ai.ModelInfo
import com.airi.assistant.ui.viewmodel.ChatViewModel
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
                    "Import GGUF files, keep them in a local registry, and switch between saved models through the real inference loader.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                ImportModelCard(
                    state = modelState,
                    onPickModel = { modelPicker.launch("*/*") }
                )
            }

            item {
                DownloadedModelCard(
                    state = modelState,
                    onDownload = { viewModel.startDefaultModelDownload() },
                    onActivate = { viewModel.activateDownloadedModel() }
                )
            }

            item {
                Text(
                    "Local Models",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (modelState.availableModels.isEmpty()) {
                item {
                    EmptyModelRegistryCard()
                }
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
fun ImportModelCard(
    state: ModelUiState,
    onPickModel: () -> Unit
) {
    ModelCard(
        icon = "L",
        title = "Add local GGUF model",
        subtitle = "Choose a .gguf file from Android storage and save it to the local registry.",
        status = when {
            state.isModelLoading -> "Loading"
            else -> "Import"
        },
        error = state.loadError,
        actionText = "Import .gguf",
        actionEnabled = !state.isModelLoading,
        onAction = onPickModel
    )
}

@Composable
fun DownloadedModelCard(
    state: ModelUiState,
    onDownload: () -> Unit,
    onActivate: () -> Unit
) {
    val isDownloadedModelActive = state.isModelReady && state.selectedModelPath == state.downloadedModelPath
    ModelCard(
        icon = "Q",
        title = "Qwen 2.5 1.5B Instruct",
        subtitle = "4-bit GGUF • ${state.downloadedModelPath}",
        status = when {
            isDownloadedModelActive -> "Active"
            state.downloadedModelAvailable -> "Downloaded"
            else -> "Not downloaded"
        },
        error = null,
        actionText = when {
            isDownloadedModelActive -> "Active"
            state.downloadedModelAvailable -> "Activate"
            else -> "Download"
        },
        actionEnabled = !isDownloadedModelActive && !state.isModelLoading,
        onAction = {
            if (state.downloadedModelAvailable) onActivate() else onDownload()
        }
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
    ModelCard(
        icon = model.type.firstOrNull()?.uppercase() ?: "M",
        title = model.name,
        subtitle = "${model.size.toReadableSize()} • ${model.quantization} • ${model.type} • ${model.path}",
        status = when {
            isLoadingThisModel -> "Loading"
            isActive -> "Active"
            else -> "Saved"
        },
        error = null,
        actionText = when {
            isActive -> "Active"
            isLoadingThisModel -> "Loading"
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
            Text("Import a GGUF file or download the default model to add it to the registry.")
        }
    }
}

@Composable
fun ModelCard(
    icon: String,
    title: String,
    subtitle: String,
    status: String,
    error: String?,
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

            Spacer(Modifier.height(12.dp))

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
                Text(it, color = MaterialTheme.colorScheme.error)
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
