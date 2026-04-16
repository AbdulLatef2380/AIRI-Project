package com.airi.assistant.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.util.ChatExporter
import com.airi.assistant.ui.theme.InputBarBackground
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.AgentMode
import com.airi.assistant.ui.viewmodel.ChatMessage
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.ui.viewmodel.ModelUiState
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigate: (String) -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context       = LocalContext.current
    val drawerState   = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope         = rememberCoroutineScope()
    val messages      by viewModel.messages.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val agentState    by viewModel.agentState.collectAsState()
    val modelState    by viewModel.modelState.collectAsState()
    val agentMode     by viewModel.agentMode.collectAsState()
    val snackbarHost  = remember { SnackbarHostState() }

    var showMenu            by remember { mutableStateOf(false) }
    var showAttachSheet     by remember { mutableStateOf(false) }
    var showGenSettings     by remember { mutableStateOf(false) }
    var voiceInput          by remember { mutableStateOf("") }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) voiceInput = spoken
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.speak_to_airi))
            }
            speechLauncher.launch(intent)
        } else {
            scope.launch { snackbarHost.showSnackbar(context.getString(R.string.microphone_permission_required)) }
        }
    }

    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { _: Uri? -> }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { _: Uri? -> }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            capturedBitmap = bitmap
            scope.launch { snackbarHost.showSnackbar(context.getString(R.string.photo_captured)) }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraLauncher.launch(null)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AiriDrawer(
                modelState = modelState,
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    onNavigate(route)
                },
                onNewChat = {
                    viewModel.clearMessages()
                    scope.launch { drawerState.close() }
                },
                onLogout = {
                    scope.launch { drawerState.close() }
                    onLogout()
                }
            )
        }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                ChatTopBar(
                    modelState  = modelState,
                    agentState  = agentState,
                    agentMode   = agentMode,
                    showMenu    = showMenu,
                    onMenuOpen  = { scope.launch { drawerState.open() } },
                    onNewChat   = { viewModel.clearMessages() },
                    onToggleDropdown = { showMenu = !showMenu },
                    onDismissDropdown = { showMenu = false },
                    onGenSettings = { showMenu = false; showGenSettings = true },
                    onModeSelected = { viewModel.setAgentMode(it) },
                    onSwitchModel = { showMenu = false; onNavigate(AiriRoute.MODELS) },
                    onExportChat  = {
                        showMenu = false
                        scope.launch {
                            val success = ChatExporter.exportToJson(context, messages)
                            snackbarHost.showSnackbar(
                                if (success) context.getString(R.string.export_success)
                                else context.getString(R.string.export_failed)
                            )
                        }
                    }
                )
            },
            bottomBar = {
                ChatInputBar(
                    modelState      = modelState,
                    isGenerating    = agentState.isWorking,
                    voiceInput      = voiceInput,
                    onSend          = { text -> viewModel.sendMessage(text) },
                    onAttachClick   = { showAttachSheet = true },
                    onMicClick      = {
                        when {
                            !SpeechRecognizer.isRecognitionAvailable(context) -> {
                                scope.launch { snackbarHost.showSnackbar(context.getString(R.string.speech_recognition_unavailable)) }
                            }
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.speak_to_airi))
                                }
                                speechLauncher.launch(intent)
                            }
                            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onVoiceConsumed = { voiceInput = "" },
                    onOpenModels    = { onNavigate(AiriRoute.MODELS) }
                )
            }
        ) { padding ->
            ChatMessageList(
                messages      = messages,
                streamingText = streamingText,
                isGenerating  = agentState.isWorking,
                isModelReady  = modelState.isModelReady,
                modifier      = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }

    // ── Attach bottom sheet ──────────────────────────────────────────────────
    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            containerColor   = Color(0xFF12162E),
            contentColor     = Color.White,
            shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.attach), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                Spacer(Modifier.height(4.dp))
                AttachOption(icon = Icons.Outlined.Image, label = stringResource(R.string.pick_image)) {
                    showAttachSheet = false
                    imagePicker.launch("image/*")
                }
                AttachOption(icon = Icons.Outlined.AttachFile, label = stringResource(R.string.pick_file)) {
                    showAttachSheet = false
                    filePicker.launch("*/*")
                }
                AttachOption(icon = Icons.Outlined.CameraAlt, label = stringResource(R.string.take_photo)) {
                    showAttachSheet = false
                    when {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ->
                            cameraLauncher.launch(null)
                        else ->
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ── Generation settings dialog ───────────────────────────────────────────
    if (showGenSettings) {
        GenerationSettingsDialog(
            viewModel = viewModel,
            onDismiss = { showGenSettings = false }
        )
    }

    modelState.loadError?.let { error ->
        ModelErrorDialog(
            error = error,
            errorType = modelState.loadErrorType.name,
            onDismiss = { viewModel.clearModelError() }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TOP BAR
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    modelState: ModelUiState,
    agentState: AgentState,
    agentMode: AgentMode,
    showMenu: Boolean,
    onMenuOpen: () -> Unit,
    onNewChat: () -> Unit,
    onToggleDropdown: () -> Unit,
    onDismissDropdown: () -> Unit,
    onGenSettings: () -> Unit,
    onModeSelected: (AgentMode) -> Unit,
    onSwitchModel: () -> Unit,
    onExportChat: () -> Unit
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black.copy(alpha = 0.65f)
        ),
        navigationIcon = {
            IconButton(onClick = onMenuOpen) {
                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu), tint = Color.White)
            }
        },
        title = {
            Column {
                Text(
                    stringResource(R.string.app_agent_mode_title, agentMode.label),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
                Text(
                    text = when {
                        agentState.isWorking         -> stringResource(R.string.generating)
                        modelState.isModelReady      -> modelState.selectedModelName
                        modelState.isModelLoading    -> stringResource(R.string.loading_model)
                        else                         -> stringResource(R.string.no_model_active)
                    },
                    fontSize = 11.sp,
                    color = when {
                        agentState.isWorking      -> CosmicAccent
                        modelState.isModelReady   -> CosmicAccent.copy(alpha = 0.85f)
                        else                      -> Color.White.copy(alpha = 0.45f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        actions = {
            IconButton(onClick = onNewChat) {
                Icon(Icons.Outlined.AddComment, contentDescription = stringResource(R.string.new_chat), tint = Color.White.copy(alpha = 0.8f))
            }
            Box {
                IconButton(onClick = onToggleDropdown) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options), tint = Color.White.copy(alpha = 0.8f))
                }
                DropdownMenu(
                    expanded  = showMenu,
                    onDismissRequest = onDismissDropdown,
                    modifier = Modifier.background(Color(0xFF1A1F3A))
                ) {
                    DropdownMenuItem(
                        text  = { Text(stringResource(R.string.generation_settings), color = Color.White) },
                        leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null, tint = CosmicAccent) },
                        onClick = onGenSettings
                    )
                    AgentMode.values().forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label, color = if (mode == agentMode) CosmicAccent else Color.White) },
                            leadingIcon = { Icon(Icons.Outlined.Psychology, contentDescription = null, tint = CosmicAccent) },
                            onClick = {
                                onModeSelected(mode)
                                onDismissDropdown()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text  = { Text(stringResource(R.string.switch_model), color = Color.White) },
                        leadingIcon = { Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = CosmicAccent) },
                        onClick = onSwitchModel
                    )
                    Divider(color = Color.White.copy(alpha = 0.1f))
                    DropdownMenuItem(
                        text  = { Text(stringResource(R.string.export_chat), color = Color.White) },
                        leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, tint = Color.White.copy(alpha = 0.6f)) },
                        onClick = onExportChat
                    )
                }
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// MESSAGES
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    streamingText: String,
    isGenerating: Boolean,
    isModelReady: Boolean = false,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    LaunchedEffect(messages.size, streamingText.length) {
        if (messages.isNotEmpty() || streamingText.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }

    if (messages.isEmpty() && streamingText.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = CosmicAccent.copy(alpha = 0.35f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.airi_ready),
                    color = Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isModelReady) stringResource(R.string.ask_anything_model_active)
                    else stringResource(R.string.activate_model_gallery_first),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 13.sp
                )
            }
        }
    } else {
        LazyColumn(
            state            = listState,
            modifier         = modifier,
            reverseLayout    = true,
            contentPadding   = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (streamingText.isNotEmpty() && isGenerating) {
                item(key = "streaming") { AiStreamingBubble(text = streamingText) }
            }
            items(messages.reversed(), key = { "${it.hashCode()}_${it.isUser}" }) { msg ->
                if (msg.isUser) UserBubble(msg.text) else AiBubble(msg.text, msg.agentTag, msg.traceId)
            }
        }
    }
}

@Composable
fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                .background(
                    Brush.linearGradient(
                        listOf(CosmicAccent.copy(alpha = 0.25f), CosmicAccent.copy(alpha = 0.12f))
                    )
                )
                .border(1.dp, CosmicAccent.copy(alpha = 0.35f), RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
fun AiBubble(text: String, agentTag: String? = null, traceId: String? = null) {
    val allTraces by com.airi.assistant.ai.agent.trace.AgentTraceManager.instance.traces.collectAsState()
    val trace = remember(traceId, allTraces) {
        if (traceId != null) allTraces.find { it.id == traceId } else null
    }
    var traceExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(CosmicAccent.copy(alpha = 0.15f))
                .border(1.dp, CosmicAccent.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = CosmicAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(text, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, lineHeight = 21.sp)
            }

            // ── Agent Trace Card ───────────────────────────────────────────
            if (trace != null) {
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CosmicAccent.copy(alpha = 0.07f))
                        .border(
                            0.5.dp,
                            if (trace.hasErrors) Color(0xFFFF5252).copy(alpha = 0.35f)
                            else CosmicAccent.copy(alpha = 0.3f),
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    // Header row — always visible, tap to expand/collapse
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { traceExpanded = !traceExpanded }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = CosmicAccent,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = if (agentTag != null) "⚙ $agentTag · ${trace.stepCount} step${if (trace.stepCount != 1) "s" else ""}  ${if (traceExpanded) "▲" else "▼"}"
                                       else "⚙ Agent Action · ${trace.stepCount} step${if (trace.stepCount != 1) "s" else ""}  ${if (traceExpanded) "▲" else "▼"}",
                                color = CosmicAccent.copy(alpha = 0.85f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Icon(
                            if (trace.success) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                            contentDescription = null,
                            tint = if (trace.success) Color(0xFF00C853) else Color(0xFFFF5252),
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    // Expanded step list
                    if (traceExpanded) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            trace.steps.forEachIndexed { i, step ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier.padding(vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "${i + 1}.",
                                        color = CosmicAccent.copy(alpha = 0.6f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = step.displayName,
                                                color = Color.White.copy(alpha = 0.85f),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Icon(
                                                if (step.success) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                                                contentDescription = null,
                                                tint = if (step.success) Color(0xFF00C853) else Color(0xFFFF5252),
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                        val detail = step.error ?: step.outputSummary.take(80).let {
                                            if (step.outputSummary.length > 80) "$it…" else it
                                        }
                                        if (detail.isNotBlank()) {
                                            Text(
                                                text = detail,
                                                color = if (step.error != null) Color(0xFFFF5252).copy(alpha = 0.8f)
                                                        else Color.White.copy(alpha = 0.4f),
                                                fontSize = 10.sp,
                                                lineHeight = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (agentTag != null) {
                // Fallback minimal badge (no trace stored)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(CosmicAccent.copy(alpha = 0.12f))
                        .border(0.5.dp, CosmicAccent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "⚙ $agentTag",
                        color = CosmicAccent.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun AiStreamingBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(CosmicAccent.copy(alpha = 0.2f))
                .border(1.dp, CosmicAccent.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = CosmicAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, CosmicAccent.copy(alpha = 0.25f), RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color    = CosmicAccent.copy(alpha = 0.7f),
                trackColor = Color.White.copy(alpha = 0.08f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// INPUT BAR
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ChatInputBar(
    modelState: ModelUiState,
    isGenerating: Boolean,
    voiceInput: String,
    onSend: (String) -> Unit,
    onAttachClick: () -> Unit,
    onMicClick: () -> Unit,
    onVoiceConsumed: () -> Unit,
    onOpenModels: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    val canSend = text.isNotBlank() && modelState.isModelReady && !modelState.isModelLoading && !isGenerating

    LaunchedEffect(voiceInput) {
        if (voiceInput.isNotBlank()) {
            text = listOf(text, voiceInput).filter { it.isNotBlank() }.joinToString(" ")
            onVoiceConsumed()
        }
    }

    Surface(
        color = InputBarBackground,
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {

            // Model status chip
            if (!modelState.isModelReady && !modelState.isModelLoading) {
                TextButton(onClick = onOpenModels, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = Color(0xFFFFCC00), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.no_model_tap_select), color = Color(0xFFFFCC00), fontSize = 12.sp)
                }
            } else if (modelState.isModelLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = CosmicAccent)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.loading_model_name, modelState.selectedModelName), color = CosmicAccent.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                // + Attach
                IconButton(
                    onClick  = onAttachClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.attach), tint = Color.White.copy(alpha = 0.6f))
                }

                // TextField
                TextField(
                    value         = text,
                    onValueChange = { text = it },
                    modifier      = Modifier.weight(1f),
                    enabled       = modelState.isModelReady && !isGenerating,
                    placeholder   = {
                        Text(
                            when {
                                isGenerating              -> stringResource(R.string.generating)
                                modelState.isModelLoading -> stringResource(R.string.model_is_loading)
                                modelState.isModelReady   -> stringResource(R.string.message_airi)
                                else                      -> stringResource(R.string.activate_model_first)
                            },
                            color = Color.White.copy(alpha = 0.3f),
                            fontSize = 14.sp
                        )
                    },
                    minLines = 1,
                    maxLines = 6,
                    shape    = RoundedCornerShape(20.dp),
                    colors   = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.White.copy(alpha = 0.08f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                        disabledContainerColor  = Color.White.copy(alpha = 0.03f),
                        focusedTextColor        = Color.White,
                        unfocusedTextColor      = Color.White,
                        disabledTextColor       = Color.White.copy(alpha = 0.3f),
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor  = Color.Transparent,
                        cursorColor             = CosmicAccent
                    )
                )

                // Mic
                IconButton(
                    onClick  = onMicClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = stringResource(R.string.voice_input),
                        tint = Color.White.copy(alpha = 0.5f)
                    )
                }

                // Send
                IconButton(
                    onClick  = { if (canSend) { onSend(text); text = "" } },
                    enabled  = canSend,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) CosmicAccent.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = stringResource(R.string.send),
                        tint = if (canSend) CosmicAccent else Color.White.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelErrorDialog(
    error: String,
    errorType: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF12162E),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.model_error), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error)
                Text(errorType, color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.Black)
            ) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// DRAWER
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AiriDrawer(
    modelState: ModelUiState,
    onNavigate: (String) -> Unit,
    onNewChat: () -> Unit,
    onLogout: () -> Unit
) {
    val user  = remember { FirebaseAuth.getInstance().currentUser }
    val email = user?.email ?: "guest@airi.ai"
    val initial = email.firstOrNull()?.uppercaseChar()?.toString() ?: "A"

    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF0D1124),
        drawerContentColor   = Color.White,
        modifier = Modifier.width(300.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(CosmicAccent.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
                .clickable { onNavigate(AiriRoute.PROFILE) }
                .padding(20.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(CosmicAccent.copy(alpha = 0.2f))
                            .border(1.5.dp, CosmicAccent.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initial, color = CosmicAccent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.app_agent_title), fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        Text(email, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (modelState.isModelReady) CosmicAccent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.07f)
                ) {
                    Text(
                        text = when {
                            modelState.isModelReady   -> stringResource(R.string.model_ready_status, modelState.selectedModelName)
                            modelState.isModelLoading -> stringResource(R.string.model_loading_status)
                            else                      -> stringResource(R.string.model_none_status)
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        color = if (modelState.isModelReady) CosmicAccent else Color.White.copy(alpha = 0.4f)
                    )
                }
            }
        }

        Divider(color = Color.White.copy(alpha = 0.06f))
        Spacer(Modifier.height(8.dp))

        // ── Actions ───────────────────────────────────────────────────────
        DrawerActionItem(icon = Icons.Outlined.AddComment,  label = stringResource(R.string.new_chat),      onClick = onNewChat)
        DrawerNavItem(icon = Icons.Outlined.Forum,          label = stringResource(R.string.chats),          route = AiriRoute.HISTORY,      onNavigate = onNavigate)
        DrawerNavItem(icon = Icons.Outlined.SmartToy,       label = stringResource(R.string.model_gallery),  route = AiriRoute.MODELS,       onNavigate = onNavigate)
        DrawerNavItem(icon = Icons.Outlined.Psychology,     label = stringResource(R.string.memory),         route = AiriRoute.MEMORY,       onNavigate = onNavigate)
        DrawerNavItem(icon = Icons.Outlined.Extension,      label = stringResource(R.string.integrations),   route = AiriRoute.INTEGRATIONS, onNavigate = onNavigate)

        Spacer(Modifier.height(4.dp))
        Divider(color = Color.White.copy(alpha = 0.06f))
        Spacer(Modifier.height(4.dp))

        DrawerNavItem(icon = Icons.Outlined.ManageHistory,  label = "Agent Logs",                            route = AiriRoute.AGENT_LOGS,   onNavigate = onNavigate)
        DrawerNavItem(icon = Icons.Outlined.Tune,           label = "Agent Control",                         route = AiriRoute.AGENT_CONTROL,onNavigate = onNavigate)

        Spacer(Modifier.height(4.dp))
        Divider(color = Color.White.copy(alpha = 0.06f))
        Spacer(Modifier.height(4.dp))

        DrawerNavItem(icon = Icons.Outlined.Settings,       label = stringResource(R.string.settings),       route = AiriRoute.SETTINGS,     onNavigate = onNavigate)

        Spacer(Modifier.weight(1f))
        Divider(color = Color.White.copy(alpha = 0.06f))

        DrawerActionItem(
            icon    = Icons.Outlined.Logout,
            label   = stringResource(R.string.sign_out),
            tint    = Color(0xFFFF6B6B),
            onClick = onLogout
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DrawerNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    route: String,
    onNavigate: (String) -> Unit
) {
    NavigationDrawerItem(
        icon   = { Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.7f)) },
        label  = { Text(label, color = Color.White) },
        selected = false,
        onClick = { onNavigate(route) },
        colors  = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = Color.Transparent,
            selectedContainerColor   = CosmicAccent.copy(alpha = 0.12f)
        ),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun DrawerActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White.copy(alpha = 0.7f),
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        icon     = { Icon(icon, contentDescription = null, tint = tint) },
        label    = { Text(label, color = tint) },
        selected = false,
        onClick  = onClick,
        colors   = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// ATTACH OPTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick  = onClick,
        enabled  = enabled,
        shape    = RoundedCornerShape(14.dp),
        color    = Color.White.copy(alpha = if (enabled) 0.07f else 0.03f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) CosmicAccent else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), fontSize = 15.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GENERATION SETTINGS DIALOG
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GenerationSettingsDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val temperature  by viewModel.temperature.collectAsState()
    val maxTokens    by viewModel.maxTokens.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()

    AlertDialog(
        onDismissRequest  = onDismiss,
        containerColor    = Color(0xFF12162E),
        titleContentColor = Color.White,
        textContentColor  = Color.White,
        shape             = RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.generation_settings), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.generation_settings_description), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)

                // Temperature
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.temperature), fontSize = 13.sp)
                        Text("%.1f".format(temperature), color = CosmicAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = temperature,
                        onValueChange = { viewModel.setTemperature(it) },
                        valueRange = 0.1f..2.0f,
                        colors = SliderDefaults.colors(thumbColor = CosmicAccent, activeTrackColor = CosmicAccent)
                    )
                    Text(stringResource(R.string.temperature_hint), color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp)
                }

                // Max tokens
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.max_tokens), fontSize = 13.sp)
                        Text("$maxTokens", color = CosmicAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = maxTokens.toFloat(),
                        onValueChange = { viewModel.setMaxTokens(it.toInt()) },
                        valueRange = 64f..2048f,
                        steps = 15,
                        colors = SliderDefaults.colors(thumbColor = CosmicAccent, activeTrackColor = CosmicAccent)
                    )
                }

                // System prompt
                Column {
                    Text(stringResource(R.string.system_prompt_override), fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { viewModel.setSystemPrompt(it) },
                        placeholder = { Text(stringResource(R.string.leave_empty_default), color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp) },
                        minLines = 2,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = CosmicAccent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            focusedTextColor     = Color.White,
                            unfocusedTextColor   = Color.White
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors  = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.Black)
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Color.White.copy(alpha = 0.6f)) }
        }
    )
}
