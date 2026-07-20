package com.airi.assistant.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.WindowInsets
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.DisposableEffect
import com.airi.assistant.voice.VoskEngine
import com.airi.assistant.voice.VoskModelManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.airi.assistant.R
import com.airi.assistant.WakeWordDispatcher
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.core.VoiceManager
import com.airi.assistant.domain.retention.RetentionManager
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.theme.*
import com.airi.assistant.util.ChatExporter
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.AgentMode
import com.airi.assistant.domain.ChatAttachment
import com.airi.assistant.ui.viewmodel.ChatMessage
import com.airi.assistant.ui.viewmodel.ChatViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.airi.assistant.ui.viewmodel.ModelUiState
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.auth.identity.BiometricGatekeeper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.airi.assistant.ui.util.MarkdownText
import androidx.compose.runtime.snapshotFlow

enum class VoiceSessionState { IDLE, LISTENING, PROCESSING, SPEAKING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onChatActiveChanged: (Boolean) -> Unit = {},
    onNavigate: (String) -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context       = LocalContext.current
    val scope         = rememberCoroutineScope()
    val messages      by viewModel.messages.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val agentState    by viewModel.agentState.collectAsState()
    val modelState    by viewModel.modelState.collectAsState()
    val agentMode     by viewModel.agentMode.collectAsState()
    val smartReplies  by viewModel.smartReplies.collectAsState()
    val todayTokens            by viewModel.todayTokens.collectAsState()
    val dailyCreditsRemaining  by viewModel.dailyCreditsRemaining.collectAsState()
    val isOnline      by viewModel.isOnline.collectAsState()
    val voiceModeActive    by viewModel.voiceModeActive.collectAsState()
    val voicePipelineState by viewModel.voicePipelineState.collectAsState()
    val snackbarHost  = remember { SnackbarHostState() }
    val paywallTrigger        by viewModel.paywallTrigger.collectAsState()
    val upgradePrompt         by viewModel.upgradePrompt.collectAsState()
    val systemIntegrityFailed by viewModel.systemIntegrityFailed.collectAsState()
    val contextResetWarning   by viewModel.contextResetWarning.collectAsState()
    val isSummarizing         by viewModel.isSummarizing.collectAsState()

    val chatIsActive = messages.isNotEmpty() || streamingText.isNotEmpty() || agentState.isWorking
    LaunchedEffect(chatIsActive) { onChatActiveChanged(chatIsActive) }
    
    val activity = context as? FragmentActivity
    LaunchedEffect(Unit) {
        if (activity == null) return@LaunchedEffect
        viewModel.biometricRequest.collect { request ->
            val availability = BiometricGatekeeper.checkAvailability(activity)
            if (availability == BiometricGatekeeper.Availability.NOT_ENROLLED) {
                snackbarHost.showSnackbar("Add a fingerprint or screen lock in Settings to enable this mode.")
                return@collect
            }
            val passed = BiometricGatekeeper.authenticate(
                activity = activity,
                title    = "Confirm Mode Change",
                subtitle = "AIRI needs to verify your identity to enable autonomous agent mode."
            )
            if (passed) viewModel.onBiometricSuccess(request)
        }
    }

    LaunchedEffect(paywallTrigger) {
        if (paywallTrigger) {
            viewModel.clearPaywallTrigger()
            onNavigate(AiriRoute.PAYWALL)
        }
    }

    LaunchedEffect(upgradePrompt) {
        val prompt = upgradePrompt ?: return@LaunchedEffect
        val result = snackbarHost.showSnackbar(
            message = prompt.message,
            actionLabel = "Unlock",
            withDismissAction = true,
            duration = SnackbarDuration.Short
        )
        viewModel.clearUpgradePrompt()
        if (result == SnackbarResult.ActionPerformed) {
            AnalyticsService.upgradeClick()
            onNavigate(AiriRoute.PAYWALL)
        }
    }

    LaunchedEffect(contextResetWarning) {
        val warning = contextResetWarning ?: return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.airi.assistant.core.ServiceLocator.auditRepository.info(
                    "CONTEXT_RESET",
                    "KV cache overflow — context window compressed: $warning"
                )
            }
        }
        if (com.airi.assistant.BuildConfig.DEBUG || viewModel.isDebugModeEnabled()) {
            snackbarHost.showSnackbar(
                message  = "Context compressed (debug)",
                duration = androidx.compose.material3.SnackbarDuration.Short
            )
        }
        viewModel.acknowledgeContextReset()
    }

    LaunchedEffect(Unit) {
        if (RetentionManager.shouldShowReEngagement()) {
            snackbarHost.showSnackbar(
                message  = RetentionManager.getReEngagementMessage(),
                duration = SnackbarDuration.Short
            )
        }
    }

    var showMenu            by remember { mutableStateOf(false) }
    var showGenSettings     by remember { mutableStateOf(false) }
    var showModelPicker     by remember { mutableStateOf(false) }
    val isPlanModeActive    by viewModel.isPlanModeActive.collectAsState()
    val activeSkillCount    by viewModel.activeSkillCount.collectAsState()
    var voiceInput          by remember { mutableStateOf("") }
    var voiceChatInput      by remember { mutableStateOf("") }
    var voiceState          by remember { mutableStateOf(VoiceSessionState.IDLE) }

    val agentPlanViewModel: com.airi.assistant.ui.plan.AgentPlanViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    val isPanelVisible by agentPlanViewModel.isVisible.collectAsState()
    val showPanel      by agentPlanViewModel.showPanel.collectAsState()
    val planSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(voiceState) {
        viewModel.updateVoiceState(voiceState.name)
    }

    val wakeCounter by WakeWordDispatcher.counter

    val voskEngineHolder = remember { mutableStateOf<VoskEngine?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            voskEngineHolder.value?.release()
            voskEngineHolder.value = null
        }
    }

    fun stopInAppStt() {
        voskEngineHolder.value?.stop()
    }

    fun startInAppStt(autoSend: Boolean) {
        if (!VoskModelManager.isReady(context)) {
            voiceState = VoiceSessionState.IDLE
            onNavigate(AiriRoute.VOICE_SETTINGS)
            return
        }
        voskEngineHolder.value?.release()
        voskEngineHolder.value = null
        scope.launch {
            val model = VoskModelManager.loadActiveModel(context)
            if (model == null) {
                voiceState = VoiceSessionState.IDLE
                snackbarHost.showSnackbar(context.getString(R.string.voice_model_load_failed))
                return@launch
            }
            val engine = VoskEngine(context, model)
            voskEngineHolder.value = engine
            voiceState = VoiceSessionState.LISTENING
            engine.start(
                scope     = this,
                onPartial = {},
                onFinal   = { spoken ->
                    voskEngineHolder.value?.release()
                    voskEngineHolder.value = null
                    if (spoken.isNotBlank()) {
                        if (autoSend) {
                            voiceState     = VoiceSessionState.PROCESSING
                            voiceChatInput = spoken
                        } else {
                            voiceState = VoiceSessionState.IDLE
                            voiceInput = spoken
                        }
                    } else {
                        voiceState = VoiceSessionState.IDLE
                    }
                },
                onError = { err ->
                    voskEngineHolder.value?.release()
                    voskEngineHolder.value = null
                    voiceState = VoiceSessionState.IDLE
                    scope.launch { snackbarHost.showSnackbar(context.getString(R.string.speech_recognition_unavailable)) }
                }
            )
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startInAppStt(autoSend = false)
        else {
            voiceState = VoiceSessionState.IDLE
            val isPermanentlyDenied = context is Activity &&
                !context.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            scope.launch {
                snackbarHost.showSnackbar(
                    if (isPermanentlyDenied) context.getString(R.string.mic_blocked_settings)
                    else context.getString(R.string.microphone_permission_required)
                )
            }
        }
    }

    val voiceChatPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startInAppStt(autoSend = true)
        else {
            voiceState = VoiceSessionState.IDLE
            scope.launch { snackbarHost.showSnackbar(context.getString(R.string.microphone_permission_required)) }
        }
    }

    LaunchedEffect(wakeCounter) {
        if (wakeCounter > 0 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            VoskModelManager.isReady(context) &&
            voiceState == VoiceSessionState.IDLE
        ) {
            startInAppStt(autoSend = true)
        }
    }

    LaunchedEffect(voiceChatInput) {
        if (voiceChatInput.isNotBlank()) {
            viewModel.sendMessage(voiceChatInput)
            voiceChatInput = ""
            voiceState = VoiceSessionState.IDLE
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = AiriTheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            ChatTopBar(
                agentState = agentState,
                modelState = modelState,
                agentMode = agentMode,
                activeSkillCount = activeSkillCount,
                onMenuClick = { showMenu = true },
                onModelClick = { showModelPicker = true },
                onNavigate = onNavigate
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (messages.isEmpty() && streamingText.isEmpty()) {
                    EmptyChatState(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    ChatList(
                        messages = messages,
                        streamingText = streamingText,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        listState = listState
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                ChatInputBar(
                    input = voiceInput,
                    isListening = voiceState == VoiceSessionState.LISTENING,
                    onSend = { text ->
                        viewModel.sendMessage(text)
                        voiceInput = ""
                    },
                    onVoiceClick = {
                        if (voiceState == VoiceSessionState.LISTENING) {
                            stopInAppStt()
                        } else {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startInAppStt(autoSend = false)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    agentState: AgentState,
    modelState: ModelUiState,
    agentMode: AgentMode,
    activeSkillCount: Int,
    onMenuClick: () -> Unit,
    onModelClick: () -> Unit,
    onNavigate: (String) -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AIRI", fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
                Spacer(Modifier.width(8.dp))
                ModelPill(modelState, onClick = onModelClick)
            }
        },
        actions = {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = AiriTheme.onBackground)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

@Composable
fun ModelPill(state: ModelUiState, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.height(28.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(state.selectedModelName, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = AiriTheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyChatState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Assistant, contentDescription = null, modifier = Modifier.size(64.dp), tint = CosmicAccent.copy(alpha = 0.2f))
        Spacer(Modifier.height(16.dp))
        Text("How can I help you today?", color = AiriTheme.onSurfaceVariant, fontSize = 16.sp)
    }
}

@Composable
fun ChatList(
    messages: List<ChatMessage>,
    streamingText: String,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(messages) { message ->
            MessageBubble(message)
        }
        if (streamingText.isNotEmpty()) {
            item {
                MessageBubble(ChatMessage(text = streamingText, isUser = false))
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) CosmicAccent else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                MarkdownText(rawText = message.text, textColor = if (isUser) Color.White else AiriTheme.onSurface)
            }
        }
    }
}

@Composable
fun ChatInputBar(
    input: String,
    isListening: Boolean,
    onSend: (String) -> Unit,
    onVoiceClick: () -> Unit
) {
    var text by remember(input) { mutableStateOf(input) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(color = AiriTheme.onSurface, fontSize = 15.sp),
                    decorationBox = { innerTextField ->
                        if (text.isEmpty()) {
                            Text("Ask AIRI...", color = AiriTheme.onSurfaceVariant, fontSize = 15.sp)
                        }
                        innerTextField()
                    }
                )
                IconButton(onClick = onVoiceClick) {
                    Icon(
                        if (isListening) Icons.Default.Mic else Icons.Default.MicNone,
                        contentDescription = "Voice",
                        tint = if (isListening) CosmicAccent else AiriTheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        FloatingActionButton(
            onClick = { if (text.isNotBlank()) onSend(text) },
            containerColor = CosmicAccent,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Default.Send, contentDescription = "Send")
        }
    }
}
