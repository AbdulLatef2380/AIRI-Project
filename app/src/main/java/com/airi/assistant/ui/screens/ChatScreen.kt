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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.airi.assistant.R
import com.airi.assistant.WakeWordDispatcher
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.core.VoiceManager
import com.airi.assistant.domain.retention.RetentionManager
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.CosmicAccentDark
import com.airi.assistant.ui.theme.CosmicBlack
import com.airi.assistant.ui.theme.DividerColor
import com.airi.assistant.ui.theme.GlassPurple
import com.airi.assistant.ui.theme.GlassPurpleBorder
import com.airi.assistant.ui.theme.ModelPillBg
import com.airi.assistant.ui.theme.ModelPillBorder
import com.airi.assistant.ui.theme.SurfaceCard
import com.airi.assistant.ui.theme.SurfaceRaised
import com.airi.assistant.util.ChatExporter
import com.airi.assistant.ui.theme.InputBarBackground
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.AgentMode
import com.airi.assistant.ui.viewmodel.ChatMessage
import com.airi.assistant.ui.viewmodel.ChatViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.airi.assistant.ui.viewmodel.ModelUiState
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.airi.assistant.ui.theme.AiBubbleSurface
import com.airi.assistant.ui.theme.AiBubbleBorder
import com.airi.assistant.ui.theme.UserBubbleSurface
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
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
    val drawerState   = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope         = rememberCoroutineScope()
    val messages      by viewModel.messages.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val agentState    by viewModel.agentState.collectAsState()
    val modelState    by viewModel.modelState.collectAsState()
    val agentMode     by viewModel.agentMode.collectAsState()
    val smartReplies  by viewModel.smartReplies.collectAsState()
    val todayTokens   by viewModel.todayTokens.collectAsState()
    val snackbarHost  = remember { SnackbarHostState() }
    val paywallTrigger        by viewModel.paywallTrigger.collectAsState()
    val upgradePrompt         by viewModel.upgradePrompt.collectAsState()
    val systemIntegrityFailed by viewModel.systemIntegrityFailed.collectAsState()

    // Chat is "active" when there are messages or the AI is responding
    val chatIsActive = messages.isNotEmpty() || streamingText.isNotEmpty() || agentState.isWorking
    LaunchedEffect(chatIsActive) { onChatActiveChanged(chatIsActive) }

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
    var voiceInput          by remember { mutableStateOf("") }
    var voiceChatInput      by remember { mutableStateOf("") }
    var voiceState          by remember { mutableStateOf(VoiceSessionState.IDLE) }

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
            // Route to Voice Settings so user can download a model in one tap
            // instead of hitting a dead-end snackbar with no action path.
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
            voiceState == VoiceSessionState.IDLE) {
            startInAppStt(autoSend = true)
        }
    }

    val voiceStateRef = remember { mutableStateOf(VoiceSessionState.IDLE) }
    val liveChatActiveRef = remember { mutableStateOf(false) }
    val voiceLoopRearmTick = remember { mutableStateOf(0) }
    val vadInterruptedTick = remember { mutableStateOf(0) }
    val isVadInterrupting  = remember { mutableStateOf(false) }

    val voiceManager = remember {
        VoiceManager(context, object : VoiceManager.VoiceListener {
            override fun onWakeWordDetected() {}
            override fun onSpeechResult(text: String) {}
            override fun onError(error: String) {
                scope.launch { snackbarHost.showSnackbar("Voice error: $error") }
                if (liveChatActiveRef.value) liveChatActiveRef.value = false
            }
            override fun onSpeakingStarted() { voiceStateRef.value = VoiceSessionState.SPEAKING }
            override fun onSpeakingDone() {
                voiceStateRef.value = VoiceSessionState.IDLE
                if (liveChatActiveRef.value) voiceLoopRearmTick.value = voiceLoopRearmTick.value + 1
            }
            override fun onVadInterrupted() {
                isVadInterrupting.value = true
                voiceStateRef.value = VoiceSessionState.LISTENING
                vadInterruptedTick.value = vadInterruptedTick.value + 1
            }
        })
    }
    DisposableEffect(Unit) { onDispose { voiceManager.destroy() } }

    var speakNextResponse  by rememberSaveable { mutableStateOf(false) }
    var lastSpokenMsgId    by rememberSaveable { mutableStateOf(-1L) }
    var ttsStreamingActive by rememberSaveable { mutableStateOf(false) }
    var lastTtsStreamLen   by rememberSaveable { mutableStateOf(0) }

    @Suppress("DEPRECATION")
    val _lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(_lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                voiceManager.stopVadIfRunning()
                stopInAppStt()
                isVadInterrupting.value = false
                voiceStateRef.value = VoiceSessionState.IDLE
                voiceState = VoiceSessionState.IDLE
                if (liveChatActiveRef.value) liveChatActiveRef.value = false
            }
        }
        _lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { _lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(vadInterruptedTick.value) {
        if (vadInterruptedTick.value > 0) {
            voiceState = VoiceSessionState.LISTENING
            val autoSend = liveChatActiveRef.value || speakNextResponse
            speakNextResponse = false
            ttsStreamingActive = false
            lastTtsStreamLen = 0
            if (!agentState.isWorking) {
                isVadInterrupting.value = false
                startInAppStt(autoSend = autoSend)
            } else {
                viewModel.cancelGeneration()
                kotlinx.coroutines.delay(100)
                isVadInterrupting.value = false
                if (!agentState.isWorking) startInAppStt(autoSend = autoSend)
            }
        }
    }

    LaunchedEffect(voiceLoopRearmTick.value) {
        if (voiceLoopRearmTick.value > 0 && liveChatActiveRef.value &&
            (modelState.isModelReady || modelState.isCloudReady) && !agentState.isWorking) {
            kotlinx.coroutines.delay(350)
            if (liveChatActiveRef.value && !agentState.isWorking) startInAppStt(autoSend = true)
        }
    }

    LaunchedEffect(voiceStateRef.value) {
        val ttsState = voiceStateRef.value
        if (ttsState == VoiceSessionState.SPEAKING || ttsState == VoiceSessionState.IDLE) {
            if (voiceState != VoiceSessionState.LISTENING && voiceState != VoiceSessionState.PROCESSING) {
                voiceState = ttsState
            }
        }
    }

    LaunchedEffect(voiceState) {
        if (voiceState == VoiceSessionState.LISTENING) {
            kotlinx.coroutines.delay(7_000L)
            if (voiceState == VoiceSessionState.LISTENING) {
                stopInAppStt()
                voiceState = VoiceSessionState.IDLE
            }
        }
    }

    val voicePrefs = remember { context.getSharedPreferences("airi_voice", android.content.Context.MODE_PRIVATE) }
    LaunchedEffect(voiceChatInput) {
        val input = voiceChatInput
        if (input.isNotBlank() && (modelState.isModelReady || modelState.isCloudReady) && !agentState.isWorking) {
            voiceChatInput = ""
            voiceState = VoiceSessionState.IDLE
            viewModel.sendMessage(input)
            if (liveChatActiveRef.value || voicePrefs.getBoolean("voice_enabled", false)) {
                speakNextResponse = true
            }
        }
    }

    LaunchedEffect(agentState.isWorking) {
        if (speakNextResponse && !agentState.isWorking) {
            val lastMsg = messages.lastOrNull { !it.isUser }
            if (lastMsg != null && lastMsg.id != lastSpokenMsgId) {
                lastSpokenMsgId = lastMsg.id
                speakNextResponse = false
                voiceState = VoiceSessionState.SPEAKING
                if (ttsStreamingActive) {
                    voiceManager.ttsStreamFlush()
                    ttsStreamingActive = false
                } else {
                    voiceManager.speak(lastMsg.text)
                }
            }
        }
    }

    LaunchedEffect(speakNextResponse, agentState.isWorking) {
        snapshotFlow { streamingText }.collect { current ->
            if (!speakNextResponse) {
                if (ttsStreamingActive) { voiceManager.ttsStreamFlush(); ttsStreamingActive = false }
                lastTtsStreamLen = 0; return@collect
            }
            val isPlaceholder = current.isBlank() || current == "Thinking..." || current == "Analyzing image..."
            if (isPlaceholder) {
                if (ttsStreamingActive) { voiceManager.ttsStreamFlush(); ttsStreamingActive = false }
                lastTtsStreamLen = 0; return@collect
            }
            if (current.length < lastTtsStreamLen) { voiceManager.ttsStreamReset(); ttsStreamingActive = true; lastTtsStreamLen = 0 }
            if (!ttsStreamingActive) { voiceManager.ttsStreamReset(); ttsStreamingActive = true }
            if (current.length > lastTtsStreamLen) {
                val delta = current.substring(lastTtsStreamLen)
                voiceManager.ttsStreamAppend(delta)
                lastTtsStreamLen = current.length
            }
        }
    }

    var pendingAttachments by remember {
        mutableStateOf<List<com.airi.assistant.domain.ChatAttachment>>(emptyList())
    }

    fun addAttachment(att: com.airi.assistant.domain.ChatAttachment) {
        if (pendingAttachments.size >= 6) {
            scope.launch { snackbarHost.showSnackbar("Maximum 6 attachments per message") }
            return
        }
        pendingAttachments = pendingAttachments + att
    }
    fun removeAttachment(id: String) {
        pendingAttachments = pendingAttachments.filterNot { it.id == id }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val fileName = uri.lastPathSegment ?: uri.toString()
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it >= 0 }
            addAttachment(com.airi.assistant.domain.ChatAttachment(
                kind = com.airi.assistant.domain.ChatAttachment.Kind.FILE, uri = uri,
                displayName = fileName, mimeType = mime, sizeBytes = size
            ))
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment ?: "image_${System.currentTimeMillis()}"
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            addAttachment(com.airi.assistant.domain.ChatAttachment(
                kind = com.airi.assistant.domain.ChatAttachment.Kind.IMAGE, uri = uri,
                displayName = name, mimeType = mime
            ))
        }
    }
    val mmprojPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) viewModel.loadMmproj(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            addAttachment(com.airi.assistant.domain.ChatAttachment(
                kind = com.airi.assistant.domain.ChatAttachment.Kind.CAMERA, bitmap = bitmap,
                displayName = "camera_${System.currentTimeMillis()}.jpg", mimeType = "image/jpeg"
            ))
            scope.launch { snackbarHost.showSnackbar(context.getString(R.string.photo_captured)) }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraLauncher.launch(null)
    }

    val exportChatLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        scope.launch {
            val success = uri != null && ChatExporter.exportToUri(context, uri, messages)
            snackbarHost.showSnackbar(if (success) context.getString(R.string.export_success) else context.getString(R.string.export_failed))
        }
    }

    // ── Plus menu picker bridge ───────────────────────────────────────────────
    val plusPickerRequest by viewModel.pendingPlusPickerRequest.collectAsState()
    LaunchedEffect(plusPickerRequest) {
        when (plusPickerRequest) {
            ChatViewModel.PlusPickerRequest.IMAGE     -> { imagePicker.launch("image/*");          viewModel.consumePlusPickerRequest() }
            ChatViewModel.PlusPickerRequest.CAMERA    -> { cameraLauncher.launch(null);             viewModel.consumePlusPickerRequest() }
            ChatViewModel.PlusPickerRequest.FILE      -> { filePicker.launch("*/*");                viewModel.consumePlusPickerRequest() }
            ChatViewModel.PlusPickerRequest.SKILLS    -> { onNavigate(AiriRoute.SKILL_MANAGER);     viewModel.consumePlusPickerRequest() }
            ChatViewModel.PlusPickerRequest.SANDBOX   -> { onNavigate(AiriRoute.SANDBOX_WORKSPACE); viewModel.consumePlusPickerRequest() }
            ChatViewModel.PlusPickerRequest.WORKSPACE -> { onNavigate(AiriRoute.WORKSPACE);         viewModel.consumePlusPickerRequest() }
            ChatViewModel.PlusPickerRequest.TERMINAL  -> { onNavigate(AiriRoute.TERMINAL);          viewModel.consumePlusPickerRequest() }
            null -> { /* no-op */ }
        }
    }

    // ── Edit-message prefill bridge ───────────────────────────────────────────
    // When the user taps "Edit" in the user bubble contextual menu, prefillInput()
    // sets pendingPrefill which is observed here and forwarded to AiriChatInputBar
    // via a shared mutableState key (externalInputText).
    val pendingPrefill by viewModel.pendingPrefill.collectAsState()
    var externalInputText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingPrefill) {
        val text = pendingPrefill
        if (text != null) {
            externalInputText = text
            viewModel.consumePrefill()
        }
    }

    // History panel state (replaces drawer for RTL history side panel)
    var showHistoryPanel by remember { mutableStateOf(false) }

    Scaffold(
        modifier             = Modifier.fillMaxSize(),
        containerColor       = CosmicBlack,
        // Disable Scaffold's automatic WindowInsets.ime padding — the bottomBar
        // Column owns .imePadding() exclusively, preventing double application
        // that caused the input bar to jump too far up on keyboard open.
        contentWindowInsets  = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            AiriChatTopBar(
                modelState        = modelState,
                agentState        = agentState,
                agentMode         = agentMode,
                showMenu          = showMenu,
                todayTokens       = todayTokens,
                onHistoryOpen     = { showHistoryPanel = true },
                onModelPickerOpen = { showModelPicker = true },
                onToggleDropdown  = { showMenu = !showMenu },
                onDismissDropdown = { showMenu = false },
                onGenSettings     = { showMenu = false; showGenSettings = true },
                onModeSelected    = { viewModel.setAgentMode(it) },
                onSwitchModel     = { showMenu = false; onNavigate(AiriRoute.MODELS) },
                onLongPressTitle  = { onNavigate(AiriRoute.DEBUG_SCREEN) },
                onExportChat      = { showMenu = false; exportChatLauncher.launch(ChatExporter.buildFileName()) },
                onNewChat         = { viewModel.clearMessages() },
                onMuteToggle      = {}
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().imePadding()) {
                // ── Activity Feed (Phase 3) ────────────────────────────────────
                com.airi.assistant.ui.activity.ActivityFeedComposable(
                    modifier        = Modifier.fillMaxWidth(),
                    compactMaxItems = 3
                )
                // ── Agent Plan Overlay (Phase 2) ──────────────────────────────
                com.airi.assistant.ui.plan.AgentPlanOverlay(
                    modifier = Modifier.fillMaxWidth()
                )
                AnimatedVisibility(
                    visible = pendingAttachments.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit  = fadeOut() + shrinkVertically()
                ) {
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(pendingAttachments, key = { it.id }) { att ->
                            AttachmentChip(attachment = att, onRemove = { removeAttachment(att.id) })
                        }
                    }
                }
                AiriChatInputBar(
                    modelState    = modelState,
                    isGenerating  = agentState.isWorking,
                    voiceInput    = voiceInput,
                    smartReplies  = smartReplies,
                    onSend        = { text ->
                        val toSend = pendingAttachments
                        if (toSend.isNotEmpty()) {
                            viewModel.sendMessageWithAttachments(text, toSend)
                            pendingAttachments = emptyList()
                        } else {
                            viewModel.sendMessage(text)
                        }
                    },
                    onCancel      = { viewModel.cancelGeneration() },
                    onSmartReply  = { reply -> viewModel.clearSmartReplies(); viewModel.sendMessage(reply) },
                    onPickImage   = { imagePicker.launch("image/*") },
                    onPickFile    = { filePicker.launch("*/*") },
                    onPickMmproj  = { mmprojPicker.launch("*/*") },
                    onTakePhoto   = {
                        when {
                            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ->
                                cameraLauncher.launch(null)
                            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    voiceState        = voiceState,
                    isVadInterrupting = isVadInterrupting.value,
                    onMicClick        = mic@{
                        if (voiceState == VoiceSessionState.SPEAKING) {
                            voiceManager.stopVadIfRunning(); voiceManager.stopSpeaking()
                            isVadInterrupting.value = false; voiceStateRef.value = VoiceSessionState.IDLE
                            voiceState = VoiceSessionState.IDLE; return@mic
                        }
                        if (voiceState == VoiceSessionState.LISTENING) { stopInAppStt(); return@mic }
                        when {
                            !VoskModelManager.isReady(context) -> onNavigate(AiriRoute.VOICE_SETTINGS)
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startInAppStt(autoSend = false)
                            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onVoiceChatClick  = vc@{
                        if (voiceState == VoiceSessionState.SPEAKING) {
                            voiceManager.stopVadIfRunning(); voiceManager.stopSpeaking()
                            isVadInterrupting.value = false; voiceStateRef.value = VoiceSessionState.IDLE
                            voiceState = VoiceSessionState.IDLE
                            if (liveChatActiveRef.value) liveChatActiveRef.value = false; return@vc
                        }
                        if (voiceState == VoiceSessionState.LISTENING) {
                            if (liveChatActiveRef.value) liveChatActiveRef.value = false
                            stopInAppStt(); return@vc
                        }
                        when {
                            !VoskModelManager.isReady(context) -> onNavigate(AiriRoute.VOICE_SETTINGS)
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                                liveChatActiveRef.value = true; startInAppStt(autoSend = true)
                            }
                            else -> voiceChatPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onVoiceConsumed      = { voiceInput = ""; voiceState = VoiceSessionState.IDLE },
                    onOpenModels         = { onNavigate(AiriRoute.MODELS) },
                    onNavigate           = onNavigate,
                    externalInputText    = externalInputText,
                    onExternalInputConsumed = { externalInputText = null },
                    onUserStartedTyping  = {
                        if (voiceState == VoiceSessionState.SPEAKING) {
                            voiceManager.stopVadIfRunning(); voiceManager.stopSpeaking()
                            isVadInterrupting.value = false; voiceState = VoiceSessionState.IDLE
                        }
                        if (liveChatActiveRef.value) liveChatActiveRef.value = false
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            ChatMessageList(
                messages      = messages,
                streamingText = streamingText,
                isGenerating  = agentState.isWorking,
                isModelReady  = modelState.isModelReady,
                onOpenModels  = { onNavigate(AiriRoute.MODELS) },
                onShareAiResponse = { response -> shareAiResponse(context, response) },
                onSpeak = { text ->
                    voiceManager.stopVadIfRunning(); voiceManager.stopSpeaking()
                    isVadInterrupting.value = false
                    voiceState = VoiceSessionState.SPEAKING
                    voiceStateRef.value = VoiceSessionState.SPEAKING
                    voiceManager.speak(text)
                },
                onSuggestionClick  = { suggestion -> viewModel.sendMessage(suggestion) },
                onEditMessage      = { text -> viewModel.prefillInput(text) },
                onDeleteMessage    = { uid  -> viewModel.deleteMessage(uid) },
                modifier = Modifier.fillMaxSize()
            )

            // ── Live Voice Overlay (Phase 7) ──────────────────────────────
            // Shown when the user is in live/duplex voice mode
            if (liveChatActiveRef.value || voiceState != VoiceSessionState.IDLE) {
                com.airi.assistant.ui.components.VoiceLiveOverlay(
                    voiceState = voiceState,
                    caption    = if (voiceState == VoiceSessionState.PROCESSING) "…" else "",
                    onStop     = {
                        voiceManager.stopVadIfRunning()
                        voiceManager.stopSpeaking()
                        stopInAppStt()
                        liveChatActiveRef.value = false
                        isVadInterrupting.value = false
                        voiceStateRef.value = VoiceSessionState.IDLE
                        voiceState = VoiceSessionState.IDLE
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            }
            if (com.airi.assistant.BuildConfig.DEBUG) {
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) {
                    com.airi.assistant.ui.debug.DebugOverlay()
                }
            }
            AnimatedVisibility(
                visible  = systemIntegrityFailed,
                enter    = slideInVertically { -it } + fadeIn(),
                exit     = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Surface(color = Color(0xFFFF4444), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("System Integrity Failed", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        TextButton(onClick = { viewModel.clearSystemIntegrityFailed() }) {
                            Text("Dismiss", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // History panel — slides from start side
    if (showHistoryPanel) {
        AiriHistoryPanel(
            viewModel = viewModel,
            onDismiss = { showHistoryPanel = false },
            onSessionSelected = {
                showHistoryPanel = false
                onNavigate(AiriRoute.CHAT)
            },
            onNewChat = {
                viewModel.clearMessages()
                showHistoryPanel = false
            }
        )
    }

    // Model picker bottom sheet
    if (showModelPicker) {
        AiriModelPickerSheet(
            modelState = modelState,
            viewModel  = viewModel,
            onDismiss  = { showModelPicker = false },
            onNavigateToModels = { showModelPicker = false; onNavigate(AiriRoute.MODELS) }
        )
    }

    if (showGenSettings) {
        GenerationSettingsDialog(viewModel = viewModel, onDismiss = { showGenSettings = false })
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
// NEW TOP BAR — matching reference design
// Left: back arrow + token badge
// Center: model selector pill (dropdown chevron + model name + cloud icon)
// Right: mute icon + history clock icon
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiriChatTopBar(
    modelState: ModelUiState,
    agentState: AgentState,
    agentMode: AgentMode,
    showMenu: Boolean,
    todayTokens: Long = 0L,
    onHistoryOpen: () -> Unit,
    onModelPickerOpen: () -> Unit,
    onToggleDropdown: () -> Unit,
    onDismissDropdown: () -> Unit,
    onGenSettings: () -> Unit,
    onModeSelected: (AgentMode) -> Unit,
    onSwitchModel: () -> Unit,
    onLongPressTitle: () -> Unit = {},
    onExportChat: () -> Unit,
    onNewChat: () -> Unit,
    onMuteToggle: () -> Unit
) {
    // Token count formatted for compact display (e.g. 1.2k, 45k)
    val tokenDisplay = when {
        todayTokens >= 1_000_000 -> "%.1fM".format(todayTokens / 1_000_000.0)
        todayTokens >= 1_000     -> "%.1fk".format(todayTokens / 1_000.0)
        else                     -> todayTokens.toString()
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = CosmicBlack.copy(alpha = 0.92f)
        ),
        navigationIcon = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                // Token count badge — shows real cumulative tokens used today
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(CosmicAccent.copy(alpha = 0.18f))
                        .border(1.dp, CosmicAccent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .clickable { onToggleDropdown() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = tokenDisplay,
                            color = CosmicAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = CosmicAccent,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        },
        title = {
            // Center model selector pill
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(ModelPillBg)
                        .border(1.dp, ModelPillBorder, RoundedCornerShape(20.dp))
                        .clickable { onModelPickerOpen() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPressTitle() }) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = when {
                            agentState.isWorking      -> stringResource(R.string.generating)
                            modelState.isModelReady   -> modelState.selectedModelName
                            modelState.isCloudReady   -> modelState.cloudModelName.ifBlank { "Airi Cloud" }
                            modelState.isModelLoading -> stringResource(R.string.loading_model)
                            else                      -> "Airi Cloud"
                        },
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Cloud/local icon
                    Icon(
                        if (modelState.isModelReady) Icons.Outlined.Memory else Icons.Outlined.Cloud,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        },
        actions = {
            // History / clock
            IconButton(onClick = onHistoryOpen) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = "History",
                    tint = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.size(20.dp)
                )
            }
            // Overflow menu
            Box {
                if (showMenu) {
                    DropdownMenu(
                        expanded  = true,
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
                                onClick = { onModeSelected(mode); onDismissDropdown() }
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
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// MODEL PICKER BOTTOM SHEET
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiriModelPickerSheet(
    modelState: ModelUiState,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onNavigateToModels: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope      = rememberCoroutineScope()
    val context    = LocalContext.current

    // Build a real model list: local models + cloud models from EmbeddedProviderConfig
    val localModels  = modelState.availableModels
    val builtinCloud = remember { com.airi.assistant.execution.cloud.EmbeddedProviderConfig.catalog }
    val activeProv   = remember { mutableStateOf(
        com.airi.assistant.execution.cloud.EmbeddedProviderConfig.getActiveProvider(context)
    ) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111525),
        dragHandle = {
            Box(modifier = Modifier.padding(vertical = 10.dp)) {
                Box(
                    modifier = Modifier
                        .width(36.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.25f))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "اختر النموذج",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            // ── Local models ──────────────────────────────────────────────
            if (localModels.isNotEmpty()) {
                Text(
                    "على الجهاز",
                    color = Color.White.copy(0.45f), fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    textAlign = TextAlign.End
                )
                localModels.forEach { model ->
                    val isSelected = modelState.isModelReady &&
                        modelState.selectedModelId == model.id
                    ModelPickerRow(
                        name      = model.name,
                        subtitle  = "محلي — خصوصية تامة",
                        icon      = Icons.Outlined.Memory,
                        isSelected = isSelected,
                        onClick   = {
                            scope.launch {
                                viewModel.selectModel(model.id)
                                onDismiss()
                            }
                        }
                    )
                    Divider(color = Color.White.copy(alpha = 0.06f))
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Cloud models (free built-in providers) ────────────────────
            Text(
                "سحابي",
                color = Color.White.copy(0.45f), fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = TextAlign.End
            )
            builtinCloud.forEach { prov ->
                val isSelected = modelState.isCloudReady &&
                    activeProv.value?.id == prov.id
                ModelPickerRow(
                    name      = prov.displayName,
                    subtitle  = prov.description,
                    icon      = Icons.Outlined.Cloud,
                    isSelected = isSelected,
                    onClick   = {
                        scope.launch {
                            viewModel.activateBuiltinProvider(prov)
                            activeProv.value = prov
                            onDismiss()
                        }
                    }
                )
                Divider(color = Color.White.copy(alpha = 0.06f))
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onNavigateToModels, modifier = Modifier.fillMaxWidth()) {
                Text("المزيد من النماذج", color = CosmicAccent, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ModelPickerRow(
    name: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(CosmicAccent),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        } else {
            Spacer(Modifier.size(22.dp))
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.White.copy(0.45f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(CosmicAccent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HISTORY PANEL — slides from start side, shows chat sessions
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiriHistoryPanel(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onSessionSelected: () -> Unit,
    onNewChat: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()

    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF0D1124),
        drawerContentColor   = Color.White,
        modifier = Modifier.fillMaxWidth(0.88f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.7f))
                }
                Text(
                    "السجل",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Divider(color = DividerColor)

            // New conversation button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CosmicAccent.copy(alpha = 0.12f))
                    .border(1.dp, CosmicAccent.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                    .clickable { onNewChat() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CosmicAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Text(
                    "محادثة جديدة",
                    color = CosmicAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Forum,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.25f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "لا توجد محادثات سابقة",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(sessions) { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewModel.loadSession(session.id); onSessionSelected() }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(session.updatedAt)),
                                color = Color.White.copy(alpha = 0.40f),
                                fontSize = 11.sp
                            )
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    session.title.ifBlank { "محادثة" },
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    session.lastMessage.orEmpty().ifBlank { "..." },
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Divider(color = DividerColor)
                    }
                }
            }
        }
    }
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
    onOpenModels: () -> Unit = {},
    onShareAiResponse: (String) -> Unit = {},
    onSpeak: (String) -> Unit = {},
    onSuggestionClick: (String) -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()
    val reversedMessages = remember(messages) { messages.reversed() }
    val isPinnedToBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    var lastScrolledStreamLen by remember { mutableStateOf(0) }

    LaunchedEffect(messages.size) {
        if (isPinnedToBottom && (messages.isNotEmpty() || streamingText.isNotEmpty())) {
            scope.launch { listState.animateScrollToItem(0) }
        }
        lastScrolledStreamLen = 0
    }

    LaunchedEffect(Unit) {
        snapshotFlow { streamingText.length }.collect { len ->
            if (!isPinnedToBottom) return@collect
            if (len == 0) { lastScrolledStreamLen = 0; return@collect }
            val grew = len - lastScrolledStreamLen
            if (grew >= 24 || (grew in 1..23 && len < 60)) {
                lastScrolledStreamLen = len
                listState.scrollToItem(0)
            }
        }
    }

    if (messages.isEmpty() && streamingText.isEmpty()) {
        // Empty state — centered avatar + greeting
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "idle_pulse")
                val idleAlpha by infinite.animateFloat(
                    initialValue = 0.12f,
                    targetValue  = 0.30f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation  = androidx.compose.animation.core.tween(1800),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "idle_alpha"
                )
                // Outer glow ring
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    CosmicAccent.copy(alpha = idleAlpha),
                                    CosmicAccent.copy(alpha = idleAlpha * 0.4f),
                                    Color.Transparent
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(CosmicAccent.copy(alpha = 0.22f), CosmicAccent.copy(alpha = 0.08f))
                                )
                            )
                            .border(1.5.dp, CosmicAccent.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.SmartToy,
                            contentDescription = null,
                            tint = CosmicAccent,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "كيف يمكنني مساعدتك؟",
                    color = Color.White.copy(alpha = 0.88f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
                if (!isModelReady) {
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onOpenModels,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.White)
                    ) {
                        Icon(Icons.Outlined.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.model_gallery), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    } else {
        Box(modifier = modifier) {
            LazyColumn(
                state               = listState,
                modifier            = Modifier.fillMaxSize(),
                reverseLayout       = true,
                contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (streamingText.isNotEmpty() && isGenerating) {
                    item(key = "streaming") { AiStreamingBubble(text = streamingText) }
                }
                itemsIndexed(reversedMessages, key = { _, msg -> msg.uid }) { index, msg ->
                    val prevMsg = reversedMessages.getOrNull(index + 1)
                    val hideAvatar = !msg.isUser && prevMsg != null && !prevMsg.isUser
                    if (msg.isUser) {
                        UserBubble(
                            text     = msg.text,
                            imageUri = msg.imageUri,
                            onEdit   = { onEditMessage(msg.text) },
                            onDelete = { onDeleteMessage(msg.uid) }
                        )
                    } else {
                        AiBubble(
                            text       = msg.text,
                            agentTag   = msg.agentTag,
                            traceId    = msg.traceId,
                            hideAvatar = hideAvatar,
                            onShare    = onShareAiResponse,
                            onSpeak    = onSpeak,
                            execOrigin = msg.execOrigin
                        )
                    }
                }
            }
            ScrollToBottomFab(
                visible  = !isPinnedToBottom,
                onClick  = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserBubble(
    text: String,
    imageUri: String? = null,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    val displayText = remember(text, imageUri) {
        if (imageUri != null) text.replace(Regex("""\s*\n*\[image:[^\]]*\]\s*$"""), "").trim()
        else text
    }
    val context = LocalContext.current
    val haptic  = LocalHapticFeedback.current

    val transition = remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }

    // Contextual menu state — shown on long-press (not on immediate tap)
    var showContextMenu by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visibleState = transition,
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(200)) +
                slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(220)) { it / 5 }
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box {
                Column(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                        .background(UserBubbleSurface)
                        .combinedClickable(
                            onClick    = { /* tap does nothing — no auto-copy */ },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showContextMenu = true
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    if (imageUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(imageUri).crossfade(true).build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.25f)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                        if (displayText.isNotBlank()) Spacer(Modifier.height(8.dp))
                    }
                    if (displayText.isNotBlank() || imageUri == null) {
                        Text(text = displayText, color = Color.White, fontSize = 15.sp, lineHeight = 23.sp)
                    }
                }

                // Contextual menu (long-press)
                DropdownMenu(
                    expanded         = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                    modifier         = Modifier.background(Color(0xFF1A1F35))
                ) {
                    DropdownMenuItem(
                        text         = { Text("نسخ", color = Color.White, fontSize = 14.sp) },
                        leadingIcon  = { Icon(Icons.Outlined.ContentCopy, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(16.dp)) },
                        onClick      = {
                            showContextMenu = false
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AIRI", displayText))
                        }
                    )
                    DropdownMenuItem(
                        text         = { Text("تعديل", color = Color.White, fontSize = 14.sp) },
                        leadingIcon  = { Icon(Icons.Outlined.Edit, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(16.dp)) },
                        onClick      = { showContextMenu = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text         = { Text("تحديد نص", color = Color.White, fontSize = 14.sp) },
                        leadingIcon  = { Icon(Icons.Outlined.TextFields, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(16.dp)) },
                        onClick      = { showContextMenu = false /* text selection handled by system */ }
                    )
                    DropdownMenuItem(
                        text         = { Text("مشاركة", color = Color.White, fontSize = 14.sp) },
                        leadingIcon  = { Icon(Icons.Outlined.Share, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(16.dp)) },
                        onClick      = { showContextMenu = false; shareAiResponse(context, displayText) }
                    )
                    Divider(color = Color.White.copy(alpha = 0.08f))
                    DropdownMenuItem(
                        text         = { Text("حذف", color = SemanticError, fontSize = 14.sp) },
                        leadingIcon  = { Icon(Icons.Outlined.Delete, null, tint = SemanticError.copy(0.7f), modifier = Modifier.size(16.dp)) },
                        onClick      = { showContextMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
fun AiBubble(
    text: String,
    agentTag: String? = null,
    traceId: String? = null,
    hideAvatar: Boolean = false,
    onShare: (String) -> Unit = {},
    onSpeak: (String) -> Unit = {},
    execOrigin: com.airi.assistant.execution.ExecOrigin = com.airi.assistant.execution.ExecOrigin.NONE
) {
    val context   = LocalContext.current
    val haptic    = LocalHapticFeedback.current
    val allTraces by com.airi.assistant.ai.agent.trace.AgentTraceManager.instance.traces.collectAsState()
    val trace = remember(traceId, allTraces) {
        if (traceId != null) allTraces.find { it.id == traceId } else null
    }
    var traceExpanded by remember { mutableStateOf(false) }

    val transition = remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = transition,
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(240)) +
                slideInVertically(animationSpec = androidx.compose.animation.core.tween(240)) { it / 5 }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 44.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!hideAvatar) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(CosmicAccent.copy(alpha = 0.22f), CosmicAccent.copy(alpha = 0.06f))))
                        .border(1.dp, CosmicAccent.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("A", color = CosmicAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.width(36.dp))
            }

            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                        .background(AiBubbleSurface)
                        .border(1.dp, AiBubbleBorder, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    MarkdownText(rawText = text, modifier = Modifier.fillMaxWidth(), baseFontSp = 15f, lineHeightSp = 23f)
                }

                // Action row
                Row(modifier = Modifier.padding(start = 2.dp, top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Speak
                    IconButton(onClick = { onSpeak(text) }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(14.dp))
                    }
                    // Copy
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AIRI", text))
                    }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(14.dp))
                    }
                    // Dislike / Like — local toggle with haptic; persisted to MemoryManager
                    // when a feedback API is added in a future pass.
                    var liked    by remember { mutableStateOf(false) }
                    var disliked by remember { mutableStateOf(false) }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        disliked = !disliked; if (disliked) liked = false
                    }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.ThumbDown, contentDescription = null,
                            tint = if (disliked) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(14.dp))
                    }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        liked = !liked; if (liked) disliked = false
                    }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.ThumbUp, contentDescription = null,
                            tint = if (liked) CosmicAccent else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(14.dp))
                    }
                }

                // Agent trace card
                if (trace != null) {
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CosmicAccent.copy(alpha = 0.07f))
                            .border(0.5.dp, if (trace.hasErrors) Color(0xFFFF5252).copy(0.35f) else CosmicAccent.copy(0.3f), RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { traceExpanded = !traceExpanded }.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "⚙ ${agentTag ?: "Agent"} · ${trace.stepCount} ${if (traceExpanded) "▲" else "▼"}",
                                    color = CosmicAccent.copy(0.85f), fontSize = 10.sp, fontWeight = FontWeight.Medium
                                )
                            }
                            Icon(
                                if (trace.success) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                                contentDescription = null,
                                tint = if (trace.success) Color(0xFF00C853) else Color(0xFFFF5252),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        if (traceExpanded) {
                            Divider(color = Color.White.copy(0.05f))
                            Column(modifier = Modifier.padding(10.dp)) {
                                trace.steps.forEachIndexed { i, step ->
                                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                        Text("${i+1}.", color = CosmicAccent.copy(0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(step.displayName, color = Color.White.copy(0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                            val detail = step.error ?: step.outputSummary.take(80)
                                            if (detail.isNotBlank()) Text(detail, color = if (step.error != null) Color(0xFFFF5252).copy(0.8f) else Color.White.copy(0.4f), fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (agentTag != null) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(CosmicAccent.copy(0.12f))
                            .border(0.5.dp, CosmicAccent.copy(0.35f), RoundedCornerShape(20.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("⚙ $agentTag", color = CosmicAccent.copy(0.85f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (execOrigin.isVisible) {
                    Spacer(Modifier.height(3.dp))
                    ExecOriginBadge(origin = execOrigin)
                }
            }
        }
    }
}

@Composable
fun AiStreamingBubble(text: String) {
    val isThinkingStage = text in setOf("Thinking...", "Analyzing...", "Planning...", "Generating...", "Preparing...", "Reasoning...")
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 44.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(CosmicAccent.copy(0.22f), CosmicAccent.copy(0.06f))))
                .border(1.dp, CosmicAccent.copy(0.55f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = CosmicAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(AiBubbleSurface)
                .border(1.dp, CosmicAccent.copy(0.22f), RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text       = text,
                    color      = Color.White.copy(alpha = if (isThinkingStage) 0.50f else 0.93f),
                    fontSize   = 15.sp, lineHeight = 23.sp,
                    fontStyle  = if (isThinkingStage) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                    modifier   = Modifier.weight(1f, fill = false)
                )
                if (!isThinkingStage) BlinkingCursor()
            }
            if (isThinkingStage) { Spacer(Modifier.height(10.dp)); AiriThinkingPulse() }
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: com.airi.assistant.domain.ChatAttachment,
    onRemove: () -> Unit
) {
    val accent   = CosmicAccent
    val subtitle = when (attachment.kind) {
        com.airi.assistant.domain.ChatAttachment.Kind.IMAGE,
        com.airi.assistant.domain.ChatAttachment.Kind.CAMERA -> attachment.mimeType ?: "image"
        com.airi.assistant.domain.ChatAttachment.Kind.FILE   -> attachment.mimeType ?: "file"
    }
    Row(
        modifier = Modifier.widthIn(min = 140.dp, max = 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A).copy(0.55f))
            .border(1.dp, accent.copy(0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(0.18f)), contentAlignment = Alignment.Center) {
            val fallback = when (attachment.kind) {
                com.airi.assistant.domain.ChatAttachment.Kind.IMAGE,
                com.airi.assistant.domain.ChatAttachment.Kind.CAMERA -> Icons.Default.Image
                com.airi.assistant.domain.ChatAttachment.Kind.FILE   -> Icons.Default.AttachFile
            }
            Icon(fallback, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            val thumbModel: Any? = attachment.uri ?: attachment.bitmap
            if (attachment.isVisualImage && thumbModel != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(thumbModel).crossfade(true).build(),
                    contentDescription = attachment.displayName,
                    modifier = Modifier.matchParentSize().clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(attachment.displayName, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.White.copy(0.55f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White.copy(0.7f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BlinkingCursor() {
    var cursorOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500L); cursorOn = !cursorOn } }
    AnimatedContent(
        targetState = cursorOn,
        transitionSpec = { fadeIn(animationSpec = androidx.compose.animation.core.tween(80)) togetherWith fadeOut(animationSpec = androidx.compose.animation.core.tween(80)) },
        label = "cursor_blink"
    ) { on -> Text(if (on) "▍" else " ", color = CosmicAccent.copy(0.85f), fontSize = 15.sp, lineHeight = 23.sp) }
}

@Composable
private fun AiriThinkingPulse(modifier: Modifier = Modifier, dotSize: Dp = 7.dp, color: Color = CosmicAccent) {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "airi_pulse")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val alphaPct by infinite.animateFloat(
                initialValue = 0.20f, targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(600),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(i * 160)
                ), label = "p$i"
            )
            val scale by infinite.animateFloat(
                initialValue = 0.70f, targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(600),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(i * 160)
                ), label = "s$i"
            )
            Box(
                modifier = Modifier
                    .padding(end = if (i < 2) 6.dp else 0.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = alphaPct }
                    .size(dotSize).clip(CircleShape).background(color)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NEW INPUT BAR — matches reference design
// Layout: [send/livechat circle] [+] [mic] [waveform] [connector badges] [text field] [expand ^]
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AiriChatInputBar(
    modelState: ModelUiState,
    isGenerating: Boolean,
    voiceInput: String,
    voiceState: VoiceSessionState = VoiceSessionState.IDLE,
    isVadInterrupting: Boolean = false,
    smartReplies: List<String> = emptyList(),
    onSend: (String) -> Unit,
    onCancel: () -> Unit = {},
    onSmartReply: (String) -> Unit = {},
    onPickImage: () -> Unit = {},
    onPickMmproj: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onMicClick: () -> Unit,
    onVoiceChatClick: () -> Unit,
    onVoiceConsumed: () -> Unit,
    onOpenModels: () -> Unit,
    onNavigate: (String) -> Unit = {},
    /** When non-null, pre-fills the text field (e.g. for Edit message). */
    externalInputText: String? = null,
    onExternalInputConsumed: () -> Unit = {},
    onUserStartedTyping: () -> Unit = {}
) {
    var showAttachPopup by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(false) }
    val isInferenceReady = modelState.isModelReady || modelState.isCloudReady
    val canSend = text.isNotBlank() && isInferenceReady && !modelState.isModelLoading && !isGenerating
    val isTyping = text.isNotBlank()

    // Apply external pre-fill (e.g. from Edit bubble action)
    LaunchedEffect(externalInputText) {
        val prefill = externalInputText
        if (prefill != null) {
            text = prefill
            onExternalInputConsumed()
        }
    }
    val showSend = isTyping || isGenerating

    val micPulse = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(voiceState) {
        when (voiceState) {
            VoiceSessionState.LISTENING   -> while (true) { micPulse.animateTo(1.30f, animationSpec = androidx.compose.animation.core.tween(450)); micPulse.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(450)) }
            VoiceSessionState.PROCESSING  -> while (true) { micPulse.animateTo(1.18f, animationSpec = androidx.compose.animation.core.tween(600)); micPulse.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(600)) }
            VoiceSessionState.SPEAKING    -> while (true) { micPulse.animateTo(1.22f, animationSpec = androidx.compose.animation.core.tween(700)); micPulse.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(700)) }
            else -> micPulse.snapTo(1f)
        }
    }

    LaunchedEffect(voiceInput) {
        if (voiceInput.isNotBlank()) {
            text = listOf(text, voiceInput).filter { it.isNotBlank() }.joinToString(" ")
            onVoiceConsumed()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {

        // Smart reply chips
        AnimatedVisibility(visible = smartReplies.isNotEmpty() && !isGenerating, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                smartReplies.forEach { reply ->
                    Surface(
                        onClick = { onSmartReply(reply) }, shape = RoundedCornerShape(20.dp),
                        color = CosmicAccent.copy(0.12f),
                        modifier = Modifier.border(1.dp, CosmicAccent.copy(0.4f), RoundedCornerShape(20.dp))
                    ) {
                        Text(reply, color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }

        // Voice state banner
        AnimatedVisibility(visible = voiceState != VoiceSessionState.IDLE, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            val waveColor = when {
                isVadInterrupting                           -> Color(0xFFFFB347)
                voiceState == VoiceSessionState.LISTENING  -> Color(0xFFFF6B6B)
                voiceState == VoiceSessionState.PROCESSING -> CosmicAccent
                voiceState == VoiceSessionState.SPEAKING   -> Color(0xFF4FC3F7)
                else -> CosmicAccent
            }
            val label = when {
                isVadInterrupting                          -> "Interrupting…"
                voiceState == VoiceSessionState.LISTENING  -> "Listening…"
                voiceState == VoiceSessionState.PROCESSING -> "Processing…"
                voiceState == VoiceSessionState.SPEAKING   -> "Speaking…"
                else -> ""
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                VoiceWaveformBars(active = voiceState == VoiceSessionState.LISTENING || isVadInterrupting, color = waveColor)
                Spacer(Modifier.width(8.dp))
                Text(label, color = waveColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }

        // No model warning
        if (!isInferenceReady && !modelState.isModelLoading) {
            TextButton(onClick = onOpenModels, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                Icon(Icons.Outlined.Warning, null, tint = Color(0xFFFFCC00), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.no_model_tap_select), color = Color(0xFFFFCC00), fontSize = 12.sp)
            }
        }

        // ── Main pill container ────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF131728).copy(alpha = 0.96f))
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(20.dp))
        ) {

            // ── Multi-line text field ──────────────────────────────────────
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { newValue ->
                        if (text.isEmpty() && newValue.isNotEmpty()) onUserStartedTyping()
                        text = newValue
                    },
                    enabled = isInferenceReady && !isGenerating,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = if (isExpanded) 180.dp else 60.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White, fontSize = 15.sp,
                        textAlign = TextAlign.End   // RTL default
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(CosmicAccent),
                    maxLines = if (isExpanded) 8 else 3,
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = when {
                                        isGenerating              -> stringResource(R.string.generating)
                                        modelState.isModelLoading -> stringResource(R.string.model_is_loading)
                                        else                      -> "قم بتعيين مهمة أو اسأل أي شيء"
                                    },
                                    color = Color.White.copy(0.35f),
                                    fontSize = 15.sp,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.End
                                )
                            }
                            inner()
                        }
                    }
                )
            }

            // ── Bottom toolbar row ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Send / LiveChat / Stop circle button
                val mainScale = if (!showSend && voiceState != VoiceSessionState.IDLE) micPulse.value else 1f
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer { scaleX = mainScale; scaleY = mainScale }
                        .shadow(if (isInferenceReady) 10.dp else 0.dp, CircleShape, ambientColor = CosmicAccent, spotColor = CosmicAccent)
                        .clip(CircleShape)
                        .background(when {
                            isGenerating -> Color(0xFFFF6B6B)
                            isInferenceReady || showSend -> CosmicAccent
                            else -> CosmicAccent.copy(0.30f)
                        })
                        .clickable(enabled = isInferenceReady || isGenerating) {
                            when {
                                isGenerating -> onCancel()
                                showSend && canSend -> { onSend(text); text = "" }
                                !showSend -> onVoiceChatClick()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = when {
                            isGenerating -> "stop"
                            showSend     -> "send"
                            else         -> "live"
                        },
                        transitionSpec = {
                            (fadeIn(animationSpec = androidx.compose.animation.core.tween(180)) + scaleIn(initialScale = 0.7f)) togetherWith
                            (fadeOut(animationSpec = androidx.compose.animation.core.tween(120)) + scaleOut(targetScale = 0.7f))
                        },
                        label = "main_btn"
                    ) { state ->
                        when (state) {
                            "stop" -> Icon(Icons.Default.Stop, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            "send" -> Icon(Icons.Default.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            else   -> Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                // Attach + button
                Box(
                    modifier = Modifier
                        .size(36.dp).clip(CircleShape)
                        .clickable(enabled = !isGenerating) { showAttachPopup = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, null, tint = Color.White.copy(if (!isGenerating) 0.7f else 0.3f), modifier = Modifier.size(20.dp))
                }

                // Mic button
                AnimatedVisibility(visible = !isTyping && !isGenerating, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).clickable(enabled = isInferenceReady) { onMicClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (voiceState != VoiceSessionState.IDLE) {
                            Box(modifier = Modifier.size((28 * micPulse.value).dp).clip(CircleShape).background(CosmicAccent.copy(0.18f)))
                        }
                        Icon(Icons.Outlined.Mic, null,
                            tint = when (voiceState) {
                                VoiceSessionState.IDLE -> if (isInferenceReady) Color.White.copy(0.70f) else Color.White.copy(0.30f)
                                else -> CosmicAccent
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Connector badge — tapping opens the real Connectors screen
                if (!isTyping && !isGenerating) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1E2438))
                            .border(1.dp, Color.White.copy(0.12f), RoundedCornerShape(20.dp))
                            .clickable { onNavigate(AiriRoute.CONNECTORS) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Outlined.Hub, null, tint = CosmicAccent, modifier = Modifier.size(14.dp))
                            Icon(Icons.Outlined.ChevronRight, null, tint = Color.White.copy(0.45f), modifier = Modifier.size(12.dp))
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // Expand / collapse toggle
                IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        null,
                        tint = Color.White.copy(0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── Attach popup bubble ────────────────────────────────────────────
        if (showAttachPopup) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val gapPx   = with(density) { 12.dp.roundToPx() }
            val provider = remember {
                object : androidx.compose.ui.window.PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: androidx.compose.ui.unit.IntRect,
                        windowSize: androidx.compose.ui.unit.IntSize,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        popupContentSize: androidx.compose.ui.unit.IntSize
                    ): androidx.compose.ui.unit.IntOffset {
                        val x = (anchorBounds.left).coerceAtLeast(8)
                        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(8)
                        return androidx.compose.ui.unit.IntOffset(x, y)
                    }
                }
            }
            androidx.compose.ui.window.Popup(
                popupPositionProvider = provider,
                onDismissRequest = { showAttachPopup = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1A1F35),
                    shadowElevation = 12.dp,
                    modifier = Modifier.border(1.dp, CosmicAccent.copy(0.25f), RoundedCornerShape(20.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Top row: image, camera, file
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AttachBubble(Icons.Outlined.Image,     "صورة")         { showAttachPopup = false; onPickImage() }
                            AttachBubble(Icons.Outlined.CameraAlt, "الكاميرا")     { showAttachPopup = false; onTakePhoto() }
                            AttachBubble(Icons.Outlined.AttachFile,"إضافة ملفات") { showAttachPopup = false; onPickFile() }
                        }
                        Spacer(Modifier.height(10.dp))
                        // Bottom row: spreadsheet, task, voice chat, edit photo
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AttachBubble(Icons.Outlined.Storage,   "جدول بيانات") {
                                showAttachPopup = false
                                // Route to a sandbox code-workspace session for spreadsheet work
                                text = text + if (text.isBlank()) "Create a spreadsheet" else "\nCreate a spreadsheet"
                            }
                            AttachBubble(Icons.Outlined.History, "مهام مجدولة") {
                                showAttachPopup = false
                                onNavigate(AiriRoute.AGENT_TASKS)
                            }
                            AttachBubble(Icons.Outlined.Mic, "وضع المحادثة") {
                                showAttachPopup = false
                                onVoiceChatClick()
                            }
                            AttachBubble(Icons.Outlined.Edit, "تحرير صورة") {
                                showAttachPopup = false
                                text = text + if (text.isBlank()) "Edit this image:" else "\nEdit this image:"
                                onPickImage()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachBubble(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp)) {
        Box(
            modifier = Modifier
                .size(52.dp).shadow(6.dp, RoundedCornerShape(14.dp), ambientColor = CosmicAccent, spotColor = CosmicAccent)
                .clip(RoundedCornerShape(14.dp))
                .background(CosmicAccent.copy(0.15f))
                .border(1.dp, CosmicAccent.copy(0.40f), RoundedCornerShape(14.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = CosmicAccent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White.copy(0.7f), fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ModelErrorDialog(error: String, errorType: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF12162E),
        titleContentColor = Color.White, textContentColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.model_error), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error)
                Text(errorType, color = Color.White.copy(0.45f), fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.White)) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}

@Composable
fun AiriDrawer(
    modelState: ModelUiState,
    onNavigate: (String) -> Unit,
    onNewChat: () -> Unit,
    onLogout: () -> Unit
) {
    val user    = remember { FirebaseAuth.getInstance().currentUser }
    val email   = user?.email ?: "guest@airi.ai"
    val initial = email.firstOrNull()?.uppercaseChar()?.toString() ?: "A"

    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF0D1124),
        drawerContentColor   = Color.White,
        modifier = Modifier.width(300.dp)
    ) {
        Box(modifier = Modifier.fillMaxHeight()) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 112.dp).verticalScroll(rememberScrollState())) {
                Box(modifier = Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(CosmicAccent.copy(0.15f), Color.Transparent)))
                    .clickable { onNavigate(AiriRoute.PROFILE) }.padding(20.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(44.dp).clip(CircleShape).background(CosmicAccent.copy(0.2f)).border(1.5.dp, CosmicAccent.copy(0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Text(initial, color = CosmicAccent, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(stringResource(R.string.app_agent_title), fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                                Text(email, color = Color.White.copy(0.5f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Divider(color = Color.White.copy(0.06f))
                Spacer(Modifier.height(8.dp))
                DrawerActionItem(icon = Icons.Outlined.AddComment, label = stringResource(R.string.new_chat), onClick = onNewChat)
                DrawerNavItem(icon = Icons.Outlined.Forum, label = stringResource(R.string.chats), route = AiriRoute.HISTORY, onNavigate = onNavigate)
                DrawerNavItem(icon = Icons.Outlined.Psychology, label = stringResource(R.string.memory), route = AiriRoute.MEMORY, onNavigate = onNavigate)
                DrawerNavItem(icon = Icons.Outlined.Extension, label = stringResource(R.string.integrations), route = AiriRoute.INTEGRATIONS, onNavigate = onNavigate)
                DrawerNavItem(icon = Icons.Outlined.BuildCircle, label = stringResource(R.string.custom_skills), route = AiriRoute.SKILL_MANAGER, onNavigate = onNavigate)
                DrawerNavItem(icon = Icons.Outlined.Share, label = stringResource(R.string.invite_friends), route = AiriRoute.REFERRALS, onNavigate = onNavigate)
                Spacer(Modifier.height(4.dp))
                Divider(color = Color.White.copy(0.06f))
                Spacer(Modifier.height(4.dp))
                DrawerNavItem(icon = Icons.Outlined.ManageHistory, label = stringResource(R.string.agent_logs), route = AiriRoute.AGENT_LOGS, onNavigate = onNavigate)
                DrawerNavItem(icon = Icons.Outlined.Tune, label = stringResource(R.string.agent_control), route = AiriRoute.AGENT_CONTROL, onNavigate = onNavigate)
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(32.dp).align(Alignment.BottomCenter).offset(y = (-112).dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0D1124))))
            )
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xFF0D1124))) {
                Divider(color = Color.White.copy(0.08f))
                DrawerNavItem(icon = Icons.Outlined.Settings, label = stringResource(R.string.settings), route = AiriRoute.SETTINGS, onNavigate = onNavigate)
                DrawerActionItem(icon = Icons.Outlined.Logout, label = stringResource(R.string.sign_out), tint = Color(0xFFFF6B6B), onClick = onLogout)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun shareAiResponse(context: android.content.Context, response: String) {
    val shareText = "${response.trim()}\n\nGenerated by AIRI"
    AnalyticsService.shareableOutputShared("android_share")
    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) }
    context.startActivity(Intent.createChooser(intent, "Share AIRI response"))
}

@Composable
private fun DrawerNavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, route: String, onNavigate: (String) -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, null, tint = Color.White.copy(0.7f)) },
        label = { Text(label, color = Color.White) },
        selected = false,
        onClick = { onNavigate(route) },
        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun DrawerActionItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White.copy(0.7f), onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, null, tint = tint) },
        label = { Text(label, color = tint) },
        selected = false, onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun GenerationSettingsDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val temperature  by viewModel.temperature.collectAsState()
    val maxTokens    by viewModel.maxTokens.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF12162E), titleContentColor = Color.White, textContentColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.generation_settings), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.generation_settings_description), color = Color.White.copy(0.5f), fontSize = 12.sp)
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.temperature), fontSize = 13.sp)
                        Text("%.1f".format(temperature), color = CosmicAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = temperature, onValueChange = { viewModel.setTemperature(it) }, valueRange = 0.1f..2.0f,
                        colors = SliderDefaults.colors(thumbColor = CosmicAccent, activeTrackColor = CosmicAccent))
                    Text(stringResource(R.string.temperature_hint), color = Color.White.copy(0.35f), fontSize = 11.sp)
                }
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.max_tokens), fontSize = 13.sp)
                        Text("$maxTokens", color = CosmicAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = maxTokens.toFloat(), onValueChange = { viewModel.setMaxTokens(it.toInt()) }, valueRange = 64f..2048f, steps = 15,
                        colors = SliderDefaults.colors(thumbColor = CosmicAccent, activeTrackColor = CosmicAccent))
                }
                Column {
                    Text(stringResource(R.string.system_prompt_override), fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = systemPrompt, onValueChange = { viewModel.setSystemPrompt(it) },
                        placeholder = { Text(stringResource(R.string.leave_empty_default), color = Color.White.copy(0.3f), fontSize = 12.sp) },
                        minLines = 2, maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent, unfocusedBorderColor = Color.White.copy(0.15f), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.White)) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Color.White.copy(0.6f)) } }
    )
}

@Composable
private fun VoiceWaveformBars(active: Boolean, color: Color, barCount: Int = 5, modifier: Modifier = Modifier) {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "voice_waveform")
    val barAlpha = if (active) 0.88f else 0.40f
    Row(modifier = modifier.height(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until barCount) {
            val maxH = when (i % 3) { 0 -> 14f; 1 -> 18f; else -> 10f }
            val barH by infinite.animateFloat(
                initialValue = 3f, targetValue = if (active) maxH else 4f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(280 + i * 70),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(i * 75)
                ), label = "bar$i"
            )
            Box(modifier = Modifier.width(3.dp).height(barH.dp).clip(RoundedCornerShape(2.dp)).graphicsLayer { alpha = barAlpha }.background(color))
        }
    }
}

@Composable
private fun ScrollToBottomFab(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(200)) + scaleIn(animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy), initialScale = 0.55f),
        exit  = fadeOut(animationSpec = androidx.compose.animation.core.tween(140)) + scaleOut(targetScale = 0.55f),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(CosmicAccent.copy(0.90f)).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
