package com.airi.assistant.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                    "Choose a local GGUF model. Imported files are copied into app storage, saved in preferences, and activated through the existing inference layer.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                ImportedModelCard(
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
        }
    }
}

@Composable
fun ImportedModelCard(
    state: ModelUiState,
    onPickModel: () -> Unit
) {
    ModelCard(
        icon = "L",
        title = if (state.selectedModelPath.isBlank()) "Local GGUF model" else state.selectedModelName,
        subtitle = if (state.selectedModelPath.isBlank()) "No local model selected" else "${state.selectedModelSize.toReadableSize()} • ${state.selectedModelPath}",
        status = when {
            state.isModelLoading -> "Loading"
            state.isModelReady -> "Active"
            state.selectedModelPath.isNotBlank() -> "Not active"
            else -> "Import"
        },
        error = state.loadError,
        actionText = if (state.selectedModelPath.isBlank()) "Import .gguf" else "Change model",
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
        actionEnabled = !isDownloadedModelActive,
        onAction = {
            if (state.downloadedModelAvailable) onActivate() else onDownload()
        }
    )
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
