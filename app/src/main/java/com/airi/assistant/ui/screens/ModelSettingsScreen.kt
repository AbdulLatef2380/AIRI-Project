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
                    Text( **...**

_This response is too long to display in full._
