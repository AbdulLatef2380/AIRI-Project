package com.airi.assistant.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.airi.assistant.R
import com.airi.assistant.ai.CatalogEntry
import com.airi.assistant.ai.DeviceProfiler
import com.airi.assistant.ai.ModelConfigManager
import com.airi.assistant.ai.ModelInfo
import com.airi.assistant.ai.ModelManager
import com.airi.assistant.ai.ModelSource
import com.airi.assistant.ai.ModelType
import com.airi.assistant.ai.SUPPORTED_ARCHS
import com.airi.assistant.ai.remote.RemoteModel
import com.airi.assistant.ai.remote.RemoteModelExecutor
import com.airi.assistant.ai.remote.RemoteModelRegistry
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.ui.viewmodel.LoadErrorType
import com.airi.assistant.ui.viewmodel.ModelUiState
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

// ─── Main Screen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val context        = LocalContext.current
    val scope          = rememberCoroutineScope()
    val modelState     by viewModel.modelState.collectAsState()
    var showGenerationSettings by remember { mutableStateOf(false) }
    var modelPendingDelete     by remember { mutableStateOf<ModelInfo?>(null) }
    var modelForSettings       by remember { mutableStateOf<ModelInfo?>(null) }
    var showAddModelSheet      by remember { mutableStateOf(false) }
    var selectedCategory       by remember { mutableStateOf("All") }
    var readyExpanded          by remember { mutableStateOf(true) }
    var availableExpanded      by remember { mutableStateOf(true) }
    var downloadingIds         by remember { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()

    val categories = remember { listOf("All", "Gemma", "Qwen", "Llama", "Coding", "Small") }

    val downloadedFileNames = remember(modelState.availableModels) {
        modelState.availableModels.map { it.fileName }.toSet()
    }

    val filteredDownloaded = remember(modelState.availableModels, selectedCategory) {
        modelState.availableModels.filter { model ->
            when (selectedCategory) {
                "All"    -> true
                "Gemma"  -> model.type == ModelType.GEMMA
                "Qwen"   -> model.type == ModelType.QWEN
                "Llama"  -> model.name.contains("llama", ignoreCase = true)
                "Coding" -> model.name.contains("code", ignoreCase = true)
                "Small"  -> model.size < 1_000L * 1024L * 1024L || model.ramRequiredMb <= 1800
                else     -> true
            }
        }
    }

    val filteredCatalog = remember(modelState.catalogModels, downloadedFileNames, selectedCategory) {
        modelState.catalogModels
            // Hide entries the native build can't load and entries the spec excludes.
            .filter { entry -> entry.supported && entry.architecture in SUPPORTED_ARCHS }
            .filter { entry -> !downloadedFileNames.contains(entry.fileName) }
            .filter { entry ->
                when (selectedCategory) {
                    "All"    -> true
                    "Gemma"  -> entry.type == ModelType.GEMMA || entry.name.contains("gemma", ignoreCase = true)
                    "Qwen"   -> entry.type == ModelType.QWEN  || entry.name.contains("qwen",  ignoreCase = true)
                    "Llama"  -> entry.name.contains("llama",  ignoreCase = true)
                    "Coding" -> entry.name.contains("code",   ignoreCase = true) || entry.description.contains("code", ignoreCase = true)
                    "Small"  -> entry.sizeBytes < 1_000L * 1024L * 1024L || entry.ramRequiredMb <= 1800
                    else     -> true
                }
            }
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.importModel(it) }
    }

    // B-23: Embedding model picker — user selects a GGUF embedding file
    val embeddingPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.loadEmbeddingFromUri(context, it) }
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

    // Clear downloading state when model appears in downloaded list
    LaunchedEffect(downloadedFileNames) {
        downloadingIds = downloadingIds.filter { id ->
            !downloadedFileNames.contains(modelState.catalogModels.firstOrNull { it.id == id }?.fileName)
        }.toSet()
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.model_settings),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = AiriTheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.92f)
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddModelSheet = true },
                containerColor = CosmicAccent,
                contentColor   = Color.Black
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_model_fab))
            }
        }
    ) { padding ->
        // container: flex 1, padding 2
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(2.dp),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // Category filter chips row
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { category ->
                        val active = category == selectedCategory
                        Surface(
                            onClick = { selectedCategory = category },
                            modifier = Modifier.height(36.dp),
                            shape = CircleShape,
                            color = if (active) Color(0xFF007AFF) else Color(0xFF1C1C1E),
                            contentColor = Color.White
                        ) {
                            Box(
                                modifier = Modifier
                                    .border(
                                        0.5.dp,
                                        Color.White.copy(alpha = if (active) 0f else 0.12f),
                                        CircleShape
                                    )
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    category,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // ── "Ready to Use" accordion (downloaded models) ──────────────────
            item {
                RefModelGroupAccordion(
                    title = "Ready to Use",
                    count = filteredDownloaded.size,
                    isExpanded = readyExpanded,
                    hasActiveModel = filteredDownloaded.any {
                        it.id == modelState.selectedModelId && modelState.isModelReady
                    },
                    onToggle = { readyExpanded = !readyExpanded }
                ) {
                    if (filteredDownloaded.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 16.dp)
                        ) {
                            Text(
                                "No downloaded models yet. Browse below to get started.",
                                fontSize = 14.sp,
                                color = AiriTheme.onBackground.copy(alpha = 0.45f),
                                lineHeight = 20.sp
                            )
                        }
                    } else {
                        filteredDownloaded.forEach { model ->
                            val isActive = modelState.isModelReady && model.id == modelState.selectedModelId
                            val isLoading = modelState.isModelLoading && model.id == modelState.selectedModelId
                            RefDownloadedModelCard(
                                model = model,
                                isActive = isActive,
                                isLoading = isLoading,
                                loadProgress = if (isLoading) modelState.loadProgress else -1,
                                onActivate = { viewModel.selectModel(model.id) },
                                onDelete   = { modelPendingDelete = model },
                                onSettings = { modelForSettings = model }
                            )
                        }
                    }
                }
            }

            // ── "Available to Download" accordion (catalog, not downloaded) ───
            item {
                RefModelGroupAccordion(
                    title = "Available to Download",
                    count = filteredCatalog.size,
                    isExpanded = availableExpanded,
                    hasActiveModel = false,
                    onToggle = { availableExpanded = !availableExpanded }
                ) {
                    filteredCatalog.forEach { entry ->
                        val isDownloading = downloadingIds.contains(entry.id)
                        RefCatalogModelCard(
                            entry = entry,
                            isDownloading = isDownloading,
                            onDownload = {
                                downloadingIds = downloadingIds + entry.id
                                viewModel.downloadCatalogModel(entry)
                            },
                            onCancel = {
                                viewModel.cancelCatalogDownload()
                                downloadingIds = downloadingIds - entry.id
                            }
                        )
                    }
                }
            }

            // Active model summary + generation settings
            item {
                Spacer(Modifier.height(8.dp))
                ActiveModelSummaryCard(
                    state = modelState,
                    onOpenGenerationSettings = { showGenerationSettings = true }
                )
            }

            // Scan + import local models
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader(
                    title    = stringResource(R.string.local_models),
                    subtitle = stringResource(R.string.local_models_subtitle)
                )
            }
            item { ScanDeviceCard(isScanning = modelState.isScanning, onScan = onScanClick) }
            item {
                ImportModelCard(
                    state = modelState,
                    onPickModel = {
                        modelPicker.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
                    }
                )
            }
            // ── Cloud & Hybrid Model Store (Phase 1 + Phase 5) ────────────────
            // Full cloud provider catalog: free Groq, Gemini, OpenRouter,
            // local Ollama/LM Studio, and advanced custom endpoint.
            // Activated providers update isCloudReady → unlocks chat input
            // without requiring a local GGUF model.
            item {
                Spacer(Modifier.height(8.dp))
                Divider(color = AiriTheme.outline.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))
                CloudModelStoreSection(
                    viewModel  = viewModel,
                    modelState = modelState
                )
            }

            // B-23: Embedding model picker — enables semantic memory search.
            // Without an embedding model, memory_recall falls back to
            // chronological recall regardless of query content.
            item {
                Spacer(Modifier.height(8.dp))
                Divider(color = AiriTheme.outline.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))
                EmbeddingModelSection(
                    viewModel       = viewModel,
                    embeddingPicker = embeddingPicker
                )
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showGenerationSettings) {
        AdvancedGenerationSettingsDialog(
            viewModel = viewModel,
            state     = modelState,
            onDismiss = { showGenerationSettings = false }
        )
    }

    modelForSettings?.let { model ->
        ModelPerCardSettingsDialog(
            model     = model,
            context   = context,
            onDismiss = { modelForSettings = null }
        )
    }

    if (showAddModelSheet) {
        AddModelBottomSheet(
            onDismiss   = { showAddModelSheet = false },
            onPickLocal = {
                showAddModelSheet = false
                modelPicker.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
            },
            scope = scope
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

// ─── Reference: ModelAccordion ────────────────────────────────────────────────
// Exact match to airi-custom ModelAccordion.tsx
// height: 55dp, title 14sp, gap 8dp

@Composable
fun RefModelGroupAccordion(
    title: String,
    count: Int,
    isExpanded: Boolean,
    hasActiveModel: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Accordion header — height 55dp
        Surface(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp),
            color = if (hasActiveModel) Color(0xFF0A2540) else Color(0xFF111111),
            contentColor = Color.White
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasActiveModel) Color(0xFF5AC8FA) else Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (count > 0) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.10f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "$count",
                                fontSize = 12.sp,
                                color = AiriTheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = AiriTheme.onBackground.copy(alpha = 0.55f)
                    )
                }
            }
        }

        AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

// ─── Reference: ModelCard — Downloaded ───────────────────────────────────────
// Exact dp match to airi-custom ModelCard.tsx (isDownloaded = true)
// card: borderRadius 24, margin 6, border 1
// compactHeader: paddingH 18, paddingV 12
// headerLeft: row gap 10, icon 16x16
// headerRight: row gap 8, sizeInfo, statusDot 8x8
// actionButtonsRow: paddingH 18, paddingBottom 12, gap 8
// primaryActionButton: weight 1, borderRadius 16, height 40, border 1
// iconButton: padding 10, borderRadius 16, size 40

@Composable
fun RefDownloadedModelCard(
    model: ModelInfo,
    isActive: Boolean,
    isLoading: Boolean,
    loadProgress: Int,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onSettings: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(6.dp) // margin: 6
            .border(
                width = 1.dp,
                color = if (isActive) Color(0xFF007AFF).copy(alpha = 0.55f)
                        else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(24.dp) // borderRadius: 24
            )
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF0E1117))
    ) {
        Column {
            // compactHeader: paddingH 18, paddingV 12
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // headerLeft: row, gap 10, flex 1
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // modelTypeIcon 16x16
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(modelTypeColor(model.type).copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            model.type.label.first().uppercase(),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = modelTypeColor(model.type)
                        )
                    }
                    // compactModelName: ellipsizeMode middle
                    Text(
                        model.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AiriTheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // headerRight: row, gap 8
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // sizeInfo: CpuChip 10x10 + size text
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = AiriTheme.onBackground.copy(alpha = 0.45f)
                        )
                        Text(
                            model.size.toReadableSize(),
                            fontSize = 11.sp,
                            color = AiriTheme.onBackground.copy(alpha = 0.50f)
                        )
                    }
                    // statusDot: 8x8, borderRadius 4
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isActive  -> Color(0xFF34C759)
                                    isLoading -> Color(0xFFFF9500)
                                    else      -> Color.White.copy(alpha = 0.22f)
                                }
                            )
                    )
                }
            }

            // Loading progress bar (when loading this model)
            if (isLoading) {
                if (loadProgress in 0..100) {
                    LinearProgressIndicator(
                        progress = loadProgress / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF007AFF),
                        trackColor = Color.White.copy(alpha = 0.08f)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF007AFF),
                        trackColor = Color.White.copy(alpha = 0.08f)
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            // actionButtonsRow: paddingH 18, paddingBottom 12, gap 8
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // primaryActionButton: flex 1, height 40, borderRadius 16, border 1
                OutlinedButton(
                    onClick = onActivate,
                    enabled = !isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        width = 1.dp
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isActive) Color(0xFF34C759).copy(alpha = 0.12f)
                                         else Color.Transparent,
                        contentColor   = if (isActive) Color(0xFF34C759) else Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AiriTheme.onBackground
                        )
                    } else {
                        Text(
                            if (isActive) "Active" else "Load",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                // iconButton: padding 10, borderRadius 16, size 40 — Settings
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { onSettings() }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.model_settings_icon),
                        modifier = Modifier.size(20.dp),
                        tint = AiriTheme.onBackground.copy(alpha = 0.65f)
                    )
                }

                // iconButton: Delete
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFFF3B30).copy(alpha = 0.08f))
                        .clickable { onDelete() }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFFFF3B30).copy(alpha = 0.75f)
                    )
                }

                // iconButton: Expand/Collapse
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { isExpanded = !isExpanded }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (isExpanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                        modifier = Modifier.size(16.dp),
                        tint = AiriTheme.onBackground.copy(alpha = 0.55f)
                    )
                }
            }

            // detailsContent: paddingH 18, paddingBottom 18, gap 12
            AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // descriptionContainer: bg surface, borderRadius 16, padding 12
                    Surface(
                        color = Color(0xFF1C1C1E),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Model",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = AiriTheme.onBackground.copy(alpha = 0.45f),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Text(
                                model.name,
                                fontSize = 14.sp,
                                color = AiriTheme.onBackground,
                                lineHeight = 20.sp
                            )
                            if (model.path.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    model.path,
                                    fontSize = 11.sp,
                                    color = AiriTheme.outline,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // technicalDetailCard grid: bg surface, borderRadius 16, padding 12
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RefDetailCard(
                            label = "RAM",
                            value = "${model.ramRequiredMb.takeIf { it > 0 } ?: "—"} MB",
                            modifier = Modifier.weight(1f)
                        )
                        RefDetailCard(
                            label = "Context",
                            value = if (model.contextSize > 0) model.contextSize.contextLabel() else "—",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RefDetailCard(
                            label = "Size",
                            value = model.size.toReadableSize(),
                            modifier = Modifier.weight(1f)
                        )
                        RefDetailCard(
                            label = "Quant",
                            value = model.quantization.ifBlank { model.type.label },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

// ─── Reference: ModelCard — Catalog (not downloaded) ─────────────────────────
// Exact dp match to airi-custom ModelCard.tsx (isDownloaded = false)

@Composable
fun RefCatalogModelCard(
    entry: CatalogEntry,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit = {}
) {
    val context = LocalContext.current
    val profile = remember { DeviceProfiler.profile(context) }
    val unsupportedByRam = profile.totalRamMb < entry.ramRequiredMb
    var isExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(6.dp) // margin: 6
            .border(
                width = 1.dp,
                color = AiriTheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(24.dp) // borderRadius: 24
            )
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF0E1117))
    ) {
        Column {
            // compactHeader: paddingH 18, paddingV 12
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // headerLeft: row, gap 10, flex 1
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(modelTypeColor(entry.type).copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            entry.type.label.first().uppercase(),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = modelTypeColor(entry.type)
                        )
                    }
                    Text(
                        entry.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AiriTheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // headerRight: row, gap 8
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = if (unsupportedByRam) Color(0xFFFF3B30).copy(alpha = 0.7f)
                                   else Color.White.copy(alpha = 0.45f)
                        )
                        Text(
                            entry.sizeBytes.toReadableSize(),
                            fontSize = 11.sp,
                            color = if (unsupportedByRam) Color(0xFFFF3B30).copy(alpha = 0.7f)
                                    else Color.White.copy(alpha = 0.50f)
                        )
                    }
                    // statusDot: always grey (not downloaded)
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isDownloading) Color(0xFFFF9500)
                                else Color.White.copy(alpha = 0.18f)
                            )
                    )
                }
            }

            // RAM warning
            if (unsupportedByRam) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color(0xFFFF3B30).copy(alpha = 0.75f)
                    )
                    Text(
                        "Requires ${entry.ramRequiredMb} MB RAM — may not run on this device",
                        fontSize = 11.sp,
                        color = Color(0xFFFF3B30).copy(alpha = 0.75f),
                        lineHeight = 14.sp
                    )
                }
            }

            // Download progress bar
            if (isDownloading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF007AFF),
                    trackColor = Color.White.copy(alpha = 0.08f)
                )
                Spacer(Modifier.height(8.dp))
            }

            // actionButtonsRow: paddingH 18, paddingBottom 12, gap 8
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // primaryActionButton: flex 1, height 40, borderRadius 16, border 1
                OutlinedButton(
                    onClick = { if (!isDownloading && !unsupportedByRam) onDownload() },
                    enabled = !isDownloading && !unsupportedByRam,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF007AFF).copy(alpha = 0.10f),
                        contentColor   = Color(0xFF007AFF),
                        disabledContainerColor = Color(0xFF007AFF).copy(alpha = 0.10f),
                        disabledContentColor   = Color(0xFF007AFF)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF007AFF)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Downloading…", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    } else {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (unsupportedByRam) "Unsupported" else "Download",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                // Cancel button — only shown while a download is in progress
                if (isDownloading) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFFFF3B30).copy(alpha = 0.10f),
                            contentColor   = Color(0xFFFF3B30)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Cancel download",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }

                // iconButton: Expand/Collapse
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { isExpanded = !isExpanded }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (isExpanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                        modifier = Modifier.size(16.dp),
                        tint = AiriTheme.onBackground.copy(alpha = 0.55f)
                    )
                }
            }

            // detailsContent expanded: paddingH 18, paddingBottom 18, gap 12
            AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // descriptionContainer: bg surface, borderRadius 16, padding 12
                    if (entry.description.isNotBlank()) {
                        Surface(
                            color = Color(0xFF1C1C1E),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                entry.description,
                                modifier = Modifier.padding(12.dp),
                                fontSize = 13.sp,
                                color = AiriTheme.onBackground.copy(alpha = 0.68f),
                                lineHeight = 19.sp
                            )
                        }
                    }
                    // technicalDetailCard grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RefDetailCard(
                            label = "RAM",
                            value = "${entry.ramRequiredMb} MB",
                            modifier = Modifier.weight(1f),
                            warn = unsupportedByRam
                        )
                        RefDetailCard(
                            label = "Context",
                            value = entry.contextSize.contextLabel(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RefDetailCard(
                            label = "Size",
                            value = entry.sizeBytes.toReadableSize(),
                            modifier = Modifier.weight(1f)
                        )
                        RefDetailCard(
                            label = "Quant",
                            value = entry.quantization,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

// ─── technicalDetailCard: bg surface, borderRadius 16, padding 12 ─────────────

@Composable
private fun RefDetailCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    warn: Boolean = false
) {
    Surface(
        color = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = AiriTheme.onBackground.copy(alpha = 0.40f),
                modifier = Modifier.padding(bottom = 3.dp)
            )
            Text(
                value,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (warn) Color(0xFFFF3B30) else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─── Color helper ─────────────────────────────────────────────────────────────

private fun modelTypeColor(type: ModelType): Color = when (type) {
    ModelType.GEMMA -> Color(0xFFFF9500)
    ModelType.QWEN  -> Color(0xFF007AFF)
    else            -> Color(0xFF34C759)
}

// ─── Legacy composables kept for backward compatibility ───────────────────────

@Composable
private fun ModelStoreHero(totalRamMb: Int, storageBytes: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF007AFF).copy(alpha = 0.34f), Color(0xFF121212), Color.Black)
                )
            )
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "اكتشف عقل AIRI الجديد",
                    color = AiriTheme.onBackground,
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "نماذج محلية مختارة حسب ذاكرة الجهاز والتخزين والمعالج.",
                    color = AiriTheme.onBackground.copy(alpha = 0.68f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = AiriTheme.onBackground.copy(alpha = 0.54f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Search models • RAM ${totalRamMb}MB • ${storageBytes.toReadableSize()} free",
                    color = AiriTheme.onBackground.copy(alpha = 0.54f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CategoryChips(categories: List<String>, selected: String, onSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { category ->
            val active = category == selected
            Surface(
                onClick = { onSelected(category) },
                modifier = Modifier.height(36.dp),
                shape = CircleShape,
                color = if (active) Color(0xFF007AFF) else Color(0xFF121212),
                contentColor = Color.White
            ) {
                Box(
                    modifier = Modifier
                        .border(0.5.dp, Color.White.copy(alpha = if (active) 0.0f else 0.10f), CircleShape)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(category, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
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
                        state.isModelReady   -> stringResource(R.string.active)
                        else                 -> stringResource(R.string.not_active)
                    },
                    tone = when {
                        state.isModelLoading -> ChipTone.WARNING
                        state.isModelReady   -> ChipTone.SUCCESS
                        else                 -> ChipTone.NEUTRAL
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
    hasFailed: Boolean = false,
    showRecommendedBadge: Boolean,
    onDownload: () -> Unit,
    onActivate: () -> Unit
) {
    val context = LocalContext.current
    val profile = remember { DeviceProfiler.profile(context) }
    val unsupportedByRam = profile.totalRamMb < entry.ramRequiredMb
    val unsupportedGemma = entry.type == ModelType.GEMMA && unsupportedByRam
    var expanded by remember { mutableStateOf(false) }
    var downloadStarted by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(isDownloaded, hasFailed) {
        if (isDownloaded || hasFailed) downloadStarted = false
    }
    val isDownloading = downloadStarted && !isDownloaded && !hasFailed

    Surface(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .border(
                width = 0.5.dp,
                color = AiriTheme.onBackground.copy(alpha = 0.10f),
                shape = RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF121212),
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ManufacturerIcon(type = entry.type)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (showRecommendedBadge) {
                            Spacer(Modifier.width(8.dp))
                            StoreChip(stringResource(R.string.recommended), Color(0xFF007AFF))
                        }
                    }
                    Text(
                        if (unsupportedGemma) stringResource(R.string.model_not_supported_device) else entry.description,
                        color = if (unsupportedGemma) Color(0xFFFF3B30) else Color.White.copy(alpha = 0.64f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                StoreDownloadButton(
                    isActive = isActive,
                    isDownloaded = isDownloaded,
                    isDownloading = isDownloading || isLoadingThisEntry,
                    hasFailed = hasFailed,
                    enabled = !isAnyLoading && !unsupportedGemma,
                    onDownload = {
                        downloadStarted = true
                        onDownload()
                    },
                    onOpen = onActivate
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StoreChip(entry.sizeBytes.toReadableSize(), Color.White.copy(alpha = 0.12f))
                StoreChip(entry.quantization, Color.White.copy(alpha = 0.12f), monospace = true)
                StoreChip("RAM ${entry.ramRequiredMb} MB", if (unsupportedByRam) Color(0xFFFF3B30) else Color.White.copy(alpha = 0.12f))
                if (isActive) StoreChip("Open", Color(0xFF34C759))
            }

            if (isLoadingThisEntry) {
                if (loadProgress in 0..100) {
                    LinearProgressIndicator(progress = loadProgress / 100f, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Divider(color = AiriTheme.onBackground.copy(alpha = 0.08f))
                    DetailRow("parameters", "CTX ${entry.contextSize.contextLabel()} • ${entry.quantization}")
                    DetailRow("last update", "Catalog verified")
                    DetailRow("architecture", entry.type.label)
                    if (unsupportedGemma) {
                        Text(stringResource(R.string.model_not_supported_device), color = Color(0xFFFF3B30), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ManufacturerIcon(type: ModelType) {
    val bg = when (type) {
        ModelType.GEMMA -> Color(0xFFFF9500)
        ModelType.QWEN  -> Color(0xFF007AFF)
        else            -> Color(0xFF34C759)
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(bg.copy(alpha = 0.18f))
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(type.label.first().uppercase(), color = bg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StoreChip(label: String, color: Color, monospace: Boolean = false) {
    Surface(shape = CircleShape, color = color, contentColor = AiriTheme.onBackground) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 11.sp,
            maxLines = 1,
            fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StoreDownloadButton(
    isActive: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    hasFailed: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
    onOpen: () -> Unit
) {
    val label = when {
        isDownloading          -> ""
        isDownloaded || isActive -> "Open"
        hasFailed              -> "Retry"
        else                   -> "Download"
    }
    Button(
        onClick = { if (isDownloaded || isActive) onOpen() else onDownload() },
        enabled = enabled && !isDownloading,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isDownloaded || isActive) Color(0xFF34C759) else if (hasFailed) Color(0xFFFF9500) else Color(0xFF007AFF),
            contentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.08f),
            disabledContentColor   = Color.White.copy(alpha = 0.35f)
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.height(42.dp).widthIn(min = 88.dp)
    ) {
        if (isDownloading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AiriTheme.onBackground)
        } else {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AiriTheme.onBackground.copy(alpha = 0.45f), fontSize = 12.sp)
        Text(value, color = AiriTheme.onBackground.copy(alpha = 0.78f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onDelete: () -> Unit,
    onSettings: (() -> Unit)? = null
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
            isActive           -> stringResource(R.string.active)
            else               -> stringResource(R.string.downloaded)
        },
        error = if (isActive || isLoadingThisModel) state.loadError else null,
        errorType = if (isActive || isLoadingThisModel) state.loadErrorType else LoadErrorType.NONE,
        loadProgress = if (isLoadingThisModel) state.loadProgress else -1,
        isLoadingThis = isLoadingThisModel,
        actionText = when {
            isActive           -> stringResource(R.string.active)
            isLoadingThisModel -> stringResource(R.string.loading_ellipsis)
            else               -> stringResource(R.string.activate)
        },
        actionEnabled = !isActive && !state.isModelLoading,
        extraLabel = if (isScanned) stringResource(R.string.detected_automatically) else null,
        onAction = onActivate,
        onDelete = onDelete,
        onSettings = onSettings
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
    onDelete: (() -> Unit)?,
    onSettings: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = CosmicAccent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF0E1629),
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
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
                if (onSettings != null) {
                    IconButton(onClick = onSettings, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.model_settings_icon),
                            modifier = Modifier.size(18.dp),
                            tint = AiriTheme.onBackground.copy(alpha = 0.7f)
                        )
                    }
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                        modifier = Modifier.size(18.dp),
                        tint = AiriTheme.onSurfaceVariant
                    )
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

            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Divider(color = AiriTheme.outline.copy(alpha = 0.35f))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = AiriTheme.onSurfaceVariant
                    )
                    metadata.forEach { tag ->
                        Text(
                            "· $tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = AiriTheme.onSurfaceVariant
                        )
                    }
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var topK             by remember { mutableStateOf(prefs.getInt  ("gen_top_k",             40)) }
    var topP             by remember { mutableStateOf(prefs.getFloat("gen_top_p",             0.9f)) }
    var repeatPenalty    by remember { mutableStateOf(prefs.getFloat("gen_repeat_penalty",    1.1f)) }
    var penaltyLastN     by remember { mutableStateOf(prefs.getInt  ("gen_penalty_last_n",    64)) }
    var minP             by remember { mutableStateOf(prefs.getFloat("gen_min_p",             0.05f)) }
    var presencePenalty  by remember { mutableStateOf(prefs.getFloat("gen_presence_penalty",  0.0f)) }
    var frequencyPenalty by remember { mutableStateOf(prefs.getFloat("gen_frequency_penalty", 0.0f)) }

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

                SettingSlider(
                    title = stringResource(R.string.repeat_penalty),
                    valueLabel = "%.2f".format(repeatPenalty),
                    value = repeatPenalty,
                    valueRange = 1.0f..1.5f,
                    steps = 9,
                    onValueChange = {
                        repeatPenalty = it
                        prefs.edit().putFloat("gen_repeat_penalty", it).apply()
                    }
                )

                // Repeat-penalty look-back window. Maps directly to
                // llama_sampler_init_penalties(penalty_last_n, …). Step size of
                // 16 tokens keeps the slider manageable (0..256 = 17 stops).
                SettingSlider(
                    title = stringResource(R.string.penalty_last_n),
                    valueLabel = if (penaltyLastN == 0) "off" else "$penaltyLastN",
                    value = penaltyLastN.toFloat(),
                    valueRange = 0f..256f,
                    steps = 15,
                    onValueChange = {
                        penaltyLastN = (it / 16f).toInt() * 16
                        prefs.edit().putInt("gen_penalty_last_n", penaltyLastN).apply()
                    }
                )

                SettingSlider(
                    title = stringResource(R.string.min_p),
                    valueLabel = "%.2f".format(minP),
                    value = minP,
                    valueRange = 0.0f..0.5f,
                    steps = 9,
                    onValueChange = {
                        minP = it
                        prefs.edit().putFloat("gen_min_p", it).apply()
                    }
                )

                SettingSlider(
                    title = stringResource(R.string.presence_penalty),
                    valueLabel = "%.2f".format(presencePenalty),
                    value = presencePenalty,
                    valueRange = 0.0f..2.0f,
                    steps = 7,
                    onValueChange = {
                        presencePenalty = it
                        prefs.edit().putFloat("gen_presence_penalty", it).apply()
                    }
                )

                SettingSlider(
                    title = stringResource(R.string.frequency_penalty),
                    valueLabel = "%.2f".format(frequencyPenalty),
                    value = frequencyPenalty,
                    valueRange = 0.0f..2.0f,
                    steps = 7,
                    onValueChange = {
                        frequencyPenalty = it
                        prefs.edit().putFloat("gen_frequency_penalty", it).apply()
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
        ChipTone.INFO    -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
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
    val prefs  = context.getSharedPreferences("airi_ui_state", Context.MODE_PRIVATE)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPerCardSettingsDialog(
    model: ModelInfo,
    context: Context,
    onDismiss: () -> Unit
) {
    val configManager = remember { ModelConfigManager(context) }
    var config        by remember { mutableStateOf(configManager.getConfig(model.id)) }
    var stopWordInput by remember { mutableStateOf("") }
    var saved         by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.model_settings),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value         = config.displayName.ifBlank { model.name },
                    onValueChange = { config = config.copy(displayName = it) },
                    label         = { Text(stringResource(R.string.model_name_label)) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.bos_token), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked         = config.bosEnabled,
                        onCheckedChange = { config = config.copy(bosEnabled = it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.eos_token), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked         = config.eosEnabled,
                        onCheckedChange = { config = config.copy(eosEnabled = it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.add_generation_prompt), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked         = config.generationPromptEnabled,
                        onCheckedChange = { config = config.copy(generationPromptEnabled = it) }
                    )
                }

                OutlinedTextField(
                    value         = config.systemPrompt,
                    onValueChange = { config = config.copy(systemPrompt = it) },
                    label         = { Text(stringResource(R.string.system_prompt)) },
                    minLines      = 2,
                    maxLines      = 5,
                    modifier      = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value         = config.template,
                    onValueChange = { config = config.copy(template = it) },
                    label         = { Text(stringResource(R.string.template_editor)) },
                    placeholder   = { Text(stringResource(R.string.leave_blank_for_default), style = MaterialTheme.typography.labelSmall) },
                    minLines      = 2,
                    maxLines      = 4,
                    modifier      = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.stop_words), style = MaterialTheme.typography.labelLarge)
                    if (config.stopWords.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            config.stopWords.forEach { word ->
                                InputChip(
                                    selected  = false,
                                    onClick   = {
                                        config = config.copy(stopWords = config.stopWords.filter { it != word })
                                    },
                                    label     = { Text(word) },
                                    trailingIcon = {
                                        Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                                    }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value         = stopWordInput,
                            onValueChange = { stopWordInput = it },
                            label         = { Text(stringResource(R.string.add_stop_word)) },
                            singleLine    = true,
                            modifier      = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val w = stopWordInput.trim()
                                if (w.isNotBlank() && !config.stopWords.contains(w)) {
                                    config = config.copy(stopWords = config.stopWords + w)
                                    stopWordInput = ""
                                }
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                        }
                    }
                }

                if (saved) {
                    Text(
                        stringResource(R.string.settings_saved),
                        color = CosmicAccent,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                configManager.saveConfig(config)
                saved = true
                AnalyticsService.featureDiscovered("model_per_card_settings_saved")
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddModelBottomSheet(
    onDismiss: () -> Unit,
    onPickLocal: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var showRemote by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.add_model),
                fontWeight = FontWeight.Bold,
                style      = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(4.dp))

            if (!showRemote) {
                Surface(
                    onClick  = onPickLocal,
                    shape    = RoundedCornerShape(14.dp),
                    color    = Color(0xFF0E1629),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = CosmicAccent)
                        Column {
                            Text(stringResource(R.string.add_local_model), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.import_gguf_from_storage),
                                style = MaterialTheme.typography.bodySmall,
                                color = AiriTheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Surface(
                    onClick  = { showRemote = true },
                    shape    = RoundedCornerShape(14.dp),
                    color    = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(Icons.Outlined.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Column {
                            Text(stringResource(R.string.add_remote_model), fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.add_openai_compatible_server),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            } else {
                AddRemoteModelContent(
                    scope   = scope,
                    onSaved = { onDismiss() },
                    onBack  = { showRemote = false }
                )
            }
        }
    }
}

@Composable
private fun AddRemoteModelContent(
    scope: kotlinx.coroutines.CoroutineScope,
    onSaved: () -> Unit,
    onBack: () -> Unit
) {
    var modelName   by remember { mutableStateOf("") }
    var serverUrl   by remember { mutableStateOf("") }
    var apiKey      by remember { mutableStateOf("") }
    var testStatus  by remember { mutableStateOf<String?>(null) }
    var isTesting   by remember { mutableStateOf(false) }
    val executor    = remember { RemoteModelExecutor() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color    = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
            shape    = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.remote_model_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        OutlinedTextField(
            value         = modelName,
            onValueChange = { modelName = it },
            label         = { Text(stringResource(R.string.model_name_label)) },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value         = serverUrl,
            onValueChange = { serverUrl = it; testStatus = null },
            label         = { Text(stringResource(R.string.server_url)) },
            placeholder   = { Text("http://your-server:8080") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value         = apiKey,
            onValueChange = { apiKey = it },
            label         = { Text(stringResource(R.string.api_key_optional)) },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth()
        )

        val connectionOk   = stringResource(R.string.connection_success)
        val connectionFail = stringResource(R.string.connection_failed)
        val enterUrlFirst  = stringResource(R.string.enter_server_url_first)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (serverUrl.isBlank()) { testStatus = enterUrlFirst; return@OutlinedButton }
                    isTesting = true; testStatus = null
                    scope.launch {
                        val ok = executor.testConnection(RemoteModel(id = "test", name = "test", serverUrl = serverUrl, apiKey = apiKey))
                        isTesting  = false
                        testStatus = if (ok) connectionOk else connectionFail
                    }
                },
                enabled = !isTesting && serverUrl.isNotBlank()
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.test_connection))
            }
        }

        testStatus?.let { msg ->
            Text(
                msg,
                color = if (msg.contains("success", true) || msg.contains("ناجح", true)) CosmicAccent else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.back)) }
            Button(
                onClick = {
                    if (serverUrl.isBlank()) return@Button
                    val remoteModel = RemoteModel(
                        id        = UUID.randomUUID().toString(),
                        name      = modelName.ifBlank { "Remote Model" },
                        serverUrl = serverUrl.trim(),
                        apiKey    = apiKey.trim()
                    )
                    RemoteModelRegistry.add(remoteModel)
                    AnalyticsService.featureDiscovered("remote_model_added")
                    onSaved()
                },
                enabled  = serverUrl.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

// ── B-23: EmbeddingModelSection ───────────────────────────────────────────────
// Allows users to select an embedding GGUF file. Enables semantic memory search
// in memory_recall tool. Without this, recall falls back to chronological order.
@Composable
private fun EmbeddingModelSection(
    viewModel:       ChatViewModel,
    embeddingPicker: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val embeddingReady by viewModel.embeddingModelReady.collectAsState()
    val embeddingPath  by viewModel.embeddingModelPath.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionHeader(
            title    = "Embedding Model",
            subtitle = "Enables semantic memory search. Recommended: bge-small-en-v1.5.Q8_0.gguf (~130 MB)"
        )

        Surface(
            shape  = RoundedCornerShape(14.dp),
            color  = Color(0xFF1C1C1E),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (embeddingReady) Color(0xFF30D158) else Color(0xFF636366))
                        )
                        Text(
                            if (embeddingReady) "Loaded" else "Not loaded",
                            fontSize   = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = AiriTheme.onBackground
                        )
                    }
                    Text(
                        embeddingPath?.substringAfterLast("/") ?: "No embedding model selected",
                        fontSize  = 11.sp,
                        color     = AiriTheme.onBackground.copy(alpha = 0.5f),
                        maxLines  = 1,
                        overflow  = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier  = Modifier.padding(start = 14.dp)
                    )
                }
                TextButton(
                    onClick = { embeddingPicker.launch(arrayOf("application/octet-stream", "*/*")) }
                ) {
                    Text(
                        if (embeddingReady) "Change" else "Select GGUF",
                        color    = Color(0xFF0A84FF),
                        fontSize = 13.sp
                    )
                }
            }
        }

        Text(
            "Choose a GGUF embedding model (bge, e5, gte, nomic-embed, etc.). " +
            "Enables AIRI to find relevant memories based on meaning, not just recency.",
            fontSize   = 11.sp,
            color      = AiriTheme.onBackground.copy(alpha = 0.45f),
            lineHeight = 16.sp
        )
    }
}
