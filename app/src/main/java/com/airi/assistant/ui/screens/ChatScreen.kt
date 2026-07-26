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
import androidx.compose.material3.Divider
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.core.content.ContextCompat
import com.airi.assistant.R
import com.airi.assistant.WakeWordDispatcher
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.core.VoiceManager
import com.airi.assistant.domain.retention.RetentionManager
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.theme.*
import com.airi.assistant.domain.ChatAttachment
import androidx.compose.foundation.lazy.LazyRow
import com.airi.assistant.util.ChatExporter
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.AgentMode
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
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.airi.assistant.ui.util.MarkdownText
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.lazy.LazyListState

enum class VoiceSessionState { IDLE, LISTENING, PROCESSING, SPEAKING }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val todayTokens            by viewModel.todayTokens.collectAsState()
    val dailyCreditsRemaining  by viewModel.dailyCreditsRemaining.collectAsState()
    // : real-time network state — drives offline banner
    val isOnline      by viewModel.isOnline.collectAsState()
    // LiveVoiceService — voice mode state
    val voiceModeActive    by viewModel.voiceModeActive.collectAsState()
    val voicePipelineState by viewModel.voicePipelineState.collectAsState()
    val snackbarHost  = remember { SnackbarHostState() }
    val paywallTrigger        by viewModel.paywallTrigger.collectAsState()
    val upgradePrompt         by viewModel.upgradePrompt.collectAsState()
    val systemIntegrityFailed by viewModel.systemIntegrityFailed.collectAsState()
    val contextResetWarning   by viewModel.contextResetWarning.collectAsState()
    val isSummarizing         by viewModel.isSummarizing.collectAsState()
    val pendingSummary        by viewModel.pendingSummary.collectAsState()
    val currentSessionId      by viewModel.currentSessionId.collectAsState()

    // Chat is "active" when there are messages or the AI is responding
    val chatIsActive = messages.isNotEmpty() || streamingText.isNotEmpty() || agentState.isWorking
    LaunchedEffect(chatIsActive) { onChatActiveChanged(chatIsActive) }
    // ChatScreen is the correct collection site because it has access to
    // FragmentActivity via LocalContext — ViewModels must never hold Activity refs.
    val activity = context as? FragmentActivity
    LaunchedEffect(Unit) {
        if (activity == null) return@LaunchedEffect
        viewModel.biometricRequest.collect { request ->
            val availability = BiometricGatekeeper.checkAvailability(activity)
            if (availability == BiometricGatekeeper.Availability.NOT_ENROLLED) {
                // Device has no biometric enrolled — gate cannot proceed.
                // Show snackbar prompting the user to enrol in Settings.
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
        // : Context-reset is an implementation detail — removed from user-facing snackbar.
        // Log to AuditRepository for DeveloperCenter visibility. Debug builds retain a subtle chip.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.airi.assistant.core.ServiceLocator.auditRepository.info(
                    "CONTEXT_RESET",
                    "KV cache overflow — context window compressed: $warning"
                )
            }
        }
        // In debug builds only: show a brief non-intrusive snackbar so devs can still see it
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

    // /C04: AgentPlanViewModel for ModalBottomSheet control
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

    val exportChatLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        uri?.let { ChatExporter.exportToUri(context, it, messages, "text/markdown") }
    }

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

    val exportJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        scope.launch {
            val success = uri != null && ChatExporter.exportToUri(context, uri, messages, "application/json")
            snackbarHost.showSnackbar(if (success) context.getString(R.string.export_success) else context.getString(R.string.export_failed))
        }
    }
    val exportMdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        scope.launch {
            val success = uri != null && ChatExporter.exportToUri(context, uri, messages, "text/markdown")
            snackbarHost.showSnackbar(if (success) context.getString(R.string.export_success) else context.getString(R.string.export_failed))
        }
    }
    val exportPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        scope.launch {
            val success = uri != null && ChatExporter.exportToUri(context, uri, messages, "application/pdf")
            snackbarHost.showSnackbar(if (success) context.getString(R.string.export_success) else context.getString(R.string.export_failed))
        }
    }
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
        containerColor       = AiriTheme.background,
        // Disable Scaffold's automatic WindowInsets.ime padding — the bottomBar
        // Column owns .imePadding() exclusively, preventing double application
        // that caused the input bar to jump too far up on keyboard open.
        contentWindowInsets  = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            AiriChatTopBar(
                modelState             = modelState,
                agentState             = agentState,
                agentMode              = agentMode,
                showMenu               = showMenu,
                todayTokens            = todayTokens,
                dailyCreditsRemaining  = dailyCreditsRemaining,
                onHistoryOpen     = { showHistoryPanel = true },
                onModelPickerOpen = { showModelPicker = true },
                onToggleDropdown  = { showMenu = !showMenu },
                onDismissDropdown = { showMenu = false },
                onGenSettings     = { showMenu = false; showGenSettings = true },
                onModeSelected    = { viewModel.setAgentMode(it) },
                onSwitchModel     = { showMenu = false; onNavigate(AiriRoute.MODELS) },
                onLongPressTitle  = { onNavigate(AiriRoute.DEBUG_SCREEN) },
                onExportChat      = { showMenu = false; exportChatLauncher.launch(ChatExporter.buildFileName("md")) },
                onNewChat         = { viewModel.clearMessages() },
                onMuteToggle      = {},
                onPointsClick     = { onNavigate(AiriRoute.CREDITS) },
                onNavigate        = onNavigate
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                // Activity feed only visible while agent is executing
                AnimatedVisibility(
                    visible = agentState.isWorking,
                    enter   = fadeIn() + expandVertically(),
                    exit    = fadeOut() + shrinkVertically()
                ) {
                    com.airi.assistant.ui.activity.ActivityFeedComposable(
                        modifier        = Modifier.fillMaxWidth(),
                        compactMaxItems = 3
                    )
                }
                // : AgentPlanOverlay replaced with ModalBottomSheet (see below Box scope).
                // A compact AgentStatusChip is shown here for 1–2 step executions.
                // Attachment chips are now rendered inside the input pill (AiriChatInputBar).
                // : "Compressing history…" chip shown while ConversationSummarizer runs.
                // Non-blocking: chat remains usable. Chip auto-dismisses when done.
                AnimatedVisibility(
                    visible = isSummarizing,
                    enter   = fadeIn() + slideInVertically { it },
                    exit    = fadeOut() + slideOutVertically { it }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = AIRIShapes.xl,
                            color = AiriTheme.surfaceVariant,
                            modifier = Modifier
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = CosmicAccent,
                                    strokeWidth = 1.5.dp
                                )
                                Text(
                                    "Compressing history…",
                                    fontSize = 11.sp,
                                    color = AiriTheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Memory acceptance banner
                AnimatedVisibility(
                    visible = pendingSummary != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    pendingSummary?.let { summary ->
                        MemoryAcceptanceBanner(
                            summary = summary,
                            onAccept = { viewModel.acceptSummary(currentSessionId, summary) },
                            onReject = { viewModel.rejectSummary() }
                        )
                    }
                }

                AdvancedChatInputBar(
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
                        // Toggle LiveVoiceService (full-duplex) when Vosk model is available
                        // Check if cloud realtime provider is selected
                        val voicePrefs = context.getSharedPreferences("airi_voice", android.content.Context.MODE_PRIVATE)
                        val cloudVoiceProvider = voicePrefs.getString("cloud_voice_provider", "LOCAL") ?: "LOCAL"
                        when {
                            cloudVoiceProvider != "LOCAL" -> {
                                // Cloud realtime voice path — route through LiveVoiceService with cloud provider
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                    == PackageManager.PERMISSION_GRANTED) {
                                    viewModel.toggleVoiceMode()
                                    liveChatActiveRef.value = !voiceModeActive
                                    if (!voiceModeActive) {
                                        // Store selected cloud provider so LiveVoiceService picks it up
                                        android.util.Log.i("AIRI_VOICE", "Cloud voice provider: $cloudVoiceProvider")
                                        startInAppStt(autoSend = true)
                                    } else {
                                        stopInAppStt()
                                    }
                                } else {
                                    voiceChatPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            !VoskModelManager.isReady(context) -> onNavigate(AiriRoute.VOICE_SETTINGS)
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED -> {
                                // Use LiveVoiceService for full-duplex; fallback to in-app STT
                                viewModel.toggleVoiceMode()
                                if (!voiceModeActive) {
                                    // Also start in-app STT as visual feedback
                                    liveChatActiveRef.value = true
                                    startInAppStt(autoSend = true)
                                } else {
                                    liveChatActiveRef.value = false
                                    stopInAppStt()
                                }
                            }
                            else -> voiceChatPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onVoiceConsumed      = { voiceInput = ""; voiceState = VoiceSessionState.IDLE },
                    onOpenModels         = { onNavigate(AiriRoute.MODELS) },
                    onNavigate           = onNavigate,
                    // : stage converted file as attachment
                    onStageFile          = { uri -> viewModel.stageAttachmentUri(uri) },
                    externalInputText    = externalInputText,
                    onExternalInputConsumed = { externalInputText = null },
                    onUserStartedTyping  = {
                        if (voiceState == VoiceSessionState.SPEAKING) {
                            voiceManager.stopVadIfRunning(); voiceManager.stopSpeaking()
                            isVadInterrupting.value = false; voiceState = VoiceSessionState.IDLE
                        }
                        if (liveChatActiveRef.value) liveChatActiveRef.value = false
                    },
                    isPlanModeActive  = isPlanModeActive,
                    onPlanModeToggle  = { viewModel.togglePlanMode() },
                    onOpenToolPicker  = { onNavigate(AiriRoute.CONNECTORS) },
                    onOpenSkillPicker = { onNavigate(AiriRoute.SKILL_MANAGER) },
                    activeToolCount   = com.airi.assistant.agent.loop.tool.BuiltinTools.ALL.size,
                    activeSkillCount  = activeSkillCount,
                    onWebClick        = { viewModel.prefillInput("/web ") },
                    onCodeClick       = { viewModel.prefillInput("/code ") },
                    onCalcClick       = { viewModel.prefillInput("/calc ") },
                    // Pass attachments so they render inside the pill
                    attachments         = pendingAttachments,
                    onRemoveAttachment  = { uid -> pendingAttachments = pendingAttachments.filterNot { it.id == uid || it.uid == uid } }
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
                onExportPdf        = { exportPdfLauncher.launch(ChatExporter.buildFileName("pdf")) },
                onExportMarkdown   = { exportMdLauncher.launch(ChatExporter.buildFileName("md")) },
                onFeedback         = { uid, liked -> viewModel.submitFeedback(uid, liked) },
                modifier = Modifier.fillMaxSize()
            )

            // : Thinking animation — shown between send and first streaming token.
            // Replaces the frozen-UI gap that users see during local LLM inference (2–15 s).
            // Condition: agent is working BUT no streamed text yet (first token hasn't arrived).
            AnimatedVisibility(
                visible = agentState.isWorking && streamingText.isEmpty(),
                enter   = fadeIn(),
                exit    = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 60.dp, bottom = 8.dp)
            ) {
                Surface(
                    shape = AIRIShapes.md,
                    color = AiBubbleSurface,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, AiBubbleBorder),
                ) {
                    com.airi.assistant.ui.components.ThinkingAnimation(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                        stageText = agentState.currentAction.takeIf { it.isNotBlank() }
                    )
                }
            }
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
            // : Gate DebugOverlay — only visible in debug builds OR when developer
            // debug mode is explicitly enabled via AgentControlScreen toggle.
            // Production builds with debugMode = false show nothing here.
            val isDebugVisible = com.airi.assistant.BuildConfig.DEBUG ||
                viewModel.isDebugModeEnabled()
            if (isDebugVisible) {
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
                        Text(stringResource(R.string.system_integrity_failed), color = AiriTheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        TextButton(onClick = { viewModel.clearSystemIntegrityFailed() }) {
                            Text(stringResource(R.string.dismiss), color = AiriTheme.onBackground, fontSize = 12.sp)
                        }
                    }
                }
            }

            // : Offline mode banner — shown when device has no internet.
            // Informs user that cloud models are unavailable and local model is active.
            AnimatedVisibility(
                visible  = !isOnline,
                enter    = slideInVertically { -it } + fadeIn(),
                exit     = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = if (systemIntegrityFailed) 48.dp else 0.dp)
            ) {
                Surface(
                    color    = Color(0xFF1A1A2E),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector        = androidx.compose.material.icons.Icons.Outlined.WifiOff,
                            contentDescription = null,
                            tint               = AiriTheme.onSurfaceVariant,
                            modifier           = Modifier.size(16.dp)
                        )
                        Text(
                            text     = "Offline — using local model only",
                            color    = AiriTheme.onBackground.copy(alpha = 0.75f),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Context Reset Warning Banner — only shown in debug/developer mode.
            // In production builds this is an implementation detail logged to audit log only.
            val showContextResetBanner = (com.airi.assistant.BuildConfig.DEBUG || viewModel.isDebugModeEnabled()) &&
                contextResetWarning != null
            val topOffset = when {
                systemIntegrityFailed && !isOnline -> 96.dp
                systemIntegrityFailed              -> 48.dp
                !isOnline                          -> 48.dp
                else                               -> 0.dp
            }
            AnimatedVisibility(
                visible  = showContextResetBanner,
                enter    = slideInVertically { -it } + fadeIn(),
                exit     = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topOffset)
            ) {
                Surface(
                    color    = Color(0xFFB45309),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            modifier              = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector        = androidx.compose.material.icons.Icons.Outlined.Warning,
                                contentDescription = null,
                                tint               = Color.White,
                                modifier           = Modifier.size(16.dp)
                            )
                            Text(
                                text     = "Context reset — conversation history cleared",
                                color    = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        TextButton(onClick = { viewModel.acknowledgeContextReset() }) {
                            Text(stringResource(R.string.ok), color = Color.White, fontSize = 12.sp)
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
    // Shown when AndroidAgent requests confirmation for a destructive action
    // (send message, post content, share, delete).
    // Suspends the agent until the user responds. Times out after 30 s → cancel.
    agentState.confirmationRequest?.let { req ->
        AlertDialog(
            onDismissRequest = { viewModel.confirmAccessibilityAction(false) },
            containerColor   = Color(0xFF1A1F35),
            shape            = AIRIShapes.xl,
            icon = {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint     = Color(0xFFFFB300),
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    stringResource(R.string.chat_confirm_action_title),
                    color      = AiriTheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.chat_airi_about_to_execute),
                        color    = AiriTheme.onBackground.copy(0.7f),
                        fontSize = 14.sp
                    )
                    Surface(
                        shape = AIRIShapes.sm,
                        color = Color(0xFF252B42)
                    ) {
                        Text(
                            req.actionDisplayName,
                            color      = Color(0xFFFFB300),
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 16.sp,
                            modifier   = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                    Text(
                        req.actionDescription,
                        color    = AiriTheme.onBackground.copy(0.55f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Text(
                        stringResource(R.string.chat_action_device_warning),
                        color    = Color(0xFFFF6B6B).copy(0.8f),
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmAccessibilityAction(true) },
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFB300),
                        contentColor   = Color.Black
                    ),
                    shape = AIRIShapes.md
                ) {
                    Text(stringResource(R.string.confirm), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.confirmAccessibilityAction(false) },
                    border  = BorderStroke(1.dp, Color.White.copy(0.3f)),
                    shape   = AIRIShapes.md
                ) {
                    Text(stringResource(R.string.cancel), color = AiriTheme.onBackground.copy(0.8f))
                }
            }
        )
    }

    modelState.loadError?.let { error ->
        ModelErrorDialog(
            error = error,
            errorType = modelState.loadErrorType.name,
            onDismiss = { viewModel.clearModelError() }
        )
    }

    // /C04: Agent Plan ModalBottomSheet — non-blocking; chat stays readable during execution.
    // Only shown for complex tasks (≥3 steps) OR when plan mode is explicitly active.
    if (isPanelVisible && (showPanel || isPlanModeActive)) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { agentPlanViewModel.collapse() },
            sheetState       = planSheetState,
            dragHandle       = { androidx.compose.material3.BottomSheetDefaults.DragHandle() },
            containerColor   = androidx.compose.ui.graphics.Color(0xFF0D1117)
        ) {
            com.airi.assistant.ui.plan.AgentPlanContent(
                viewModel = agentPlanViewModel,
                modifier  = Modifier.fillMaxWidth().navigationBarsPadding()
            )
        }
    }
}
// Chat top bar — credits badge | model pill | history | overflow
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryAcceptanceBanner(
    summary: String,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = AIRIShapes.md,
        color = CosmicAccent.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, CosmicAccent.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = CosmicAccent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.memory_new_knowledge),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = AiriTheme.onBackground
                )
            }
            
            Text(
                text = summary,
                fontSize = 13.sp,
                color = AiriTheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onReject) {
                    Text(stringResource(R.string.memory_reject), color = AiriTheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                    shape = AIRIShapes.sm,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                ) {
                    Text(stringResource(R.string.memory_accept), color = Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiriChatTopBar(
    modelState: ModelUiState,
    agentState: AgentState,
    agentMode: AgentMode,
    showMenu: Boolean,
    todayTokens: Long = 0L,
    dailyCreditsRemaining: Int = 200,
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
    onMuteToggle: () -> Unit,
    onPointsClick: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    // Show credits remaining (correct source) instead of raw token count
    val tokenDisplay = dailyCreditsRemaining.toString()

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = AiriTheme.background.copy(alpha = 0.92f)
        ),
        navigationIcon = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                // Credits badge — tapping opens Credits/Usage screen
                Box(
                    modifier = Modifier
                        .clip(AIRIShapes.pill)
                        .background(CosmicAccent.copy(alpha = 0.12f))
                        .border(0.5.dp, CosmicAccent.copy(alpha = 0.40f), AIRIShapes.pill)
                        .clickable { onPointsClick() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(
                            Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = CosmicAccent,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = tokenDisplay,
                            color = CosmicAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp
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
                        .clip(AIRIShapes.pill)
                        .background(ModelPillBg)
                        .border(0.5.dp, ModelPillBorder, AIRIShapes.pill)
                        .clickable { onModelPickerOpen() }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPressTitle() }) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // Local/Cloud indicator dot
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    agentState.isWorking     -> CosmicAccent
                                    modelState.isModelReady  -> SemanticSuccess
                                    modelState.isCloudReady  -> Color(0xFF4FC3F7)
                                    modelState.isModelLoading -> SemanticWarn
                                    else                     -> AiriTheme.outline
                                }
                            )
                    )
                    Text(
                        text = when {
                            agentState.isWorking      -> stringResource(R.string.generating)
                            modelState.isModelReady   -> modelState.selectedModelName
                            modelState.isCloudReady   -> modelState.cloudModelName.ifBlank { "Airi Cloud" }
                            modelState.isModelLoading -> stringResource(R.string.loading_model)
                            else                      -> stringResource(R.string.no_model_active)
                        },
                        color = AiriTheme.onBackground.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = AiriTheme.onBackground.copy(alpha = 0.50f),
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
                    contentDescription = stringResource(R.string.cd_history),
                    tint = AiriTheme.onBackground.copy(alpha = 0.65f),
                    modifier = Modifier.size(20.dp)
                )
            }
            // Overflow menu
            Box {
                if (showMenu) {
                    DropdownMenu(
                        expanded  = true,
                        onDismissRequest = onDismissDropdown,
                        modifier = Modifier.background(AiriTheme.surfaceVariant)
                    ) {
                        DropdownMenuItem(
                            text  = { Text(stringResource(R.string.generation_settings), color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null, tint = CosmicAccent) },
                            onClick = onGenSettings
                        )
                        AgentMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label, color = if (mode == agentMode) CosmicAccent else AiriTheme.onBackground) },
                                leadingIcon = { Icon(Icons.Outlined.Psychology, contentDescription = null, tint = CosmicAccent) },
                                onClick = { onModeSelected(mode); onDismissDropdown() }
                            )
                        }
                        DropdownMenuItem(
                            text  = { Text(stringResource(R.string.switch_model), color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.Memory, contentDescription = null, tint = CosmicAccent) },
                            onClick = onSwitchModel
                        )
                        Divider(color = AiriTheme.outline.copy(alpha = 0.35f))
                        DropdownMenuItem(
                            text  = { Text(stringResource(R.string.export_chat), color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, tint = AiriTheme.onSurfaceVariant) },
                            onClick = onExportChat
                        )
                        // : Templates entry — was unreachable; now wired to AiriRoute.TEMPLATES
                        DropdownMenuItem(
                            text  = { Text(stringResource(R.string.chat_templates_title), color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = CosmicAccent) },
                            onClick = {
                                onDismissDropdown()
                                onNavigate(AiriRoute.TEMPLATES)
                            }
                        )
                    }
                }
            }
        }
    )
}
// Model picker bottom sheet
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
        containerColor = AiriTheme.surface,
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
                text = stringResource(R.string.chat_select_model_title),
                color = AiriTheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )
            if (localModels.isNotEmpty()) {
                Text(
                    stringResource(R.string.chat_on_device_label),
                    color = AiriTheme.onBackground.copy(0.45f), fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    textAlign = TextAlign.End
                )
                localModels.forEach { model ->
                    val isSelected = modelState.isModelReady &&
                        modelState.selectedModelId == model.id
                    ModelPickerRow(
                        name      = model.name,
                        subtitle  = stringResource(R.string.chat_local_privacy),
                        icon      = Icons.Outlined.Memory,
                        isSelected = isSelected,
                        onClick   = {
                            scope.launch {
                                viewModel.selectModel(model.id)
                                onDismiss()
                            }
                        }
                    )
                    Divider(color = AiriTheme.outline.copy(alpha = 0.3f))
                }
                Spacer(Modifier.height(12.dp))
            }
            Text(
                stringResource(R.string.chat_cloud_label),
                color = AiriTheme.onBackground.copy(0.45f), fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = TextAlign.End
            )
            builtinCloud.forEach { prov ->
                val isSelected = modelState.isCloudReady &&
                    activeProv.value?.id == prov.id
                ModelPickerRow(
                    name      = prov.displayLabel,
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
                Divider(color = AiriTheme.outline.copy(alpha = 0.3f))
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onNavigateToModels, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chat_more_models), color = CosmicAccent, fontSize = 14.sp)
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
                Icon(Icons.Default.Check, null, tint = AiriTheme.onBackground, modifier = Modifier.size(14.dp))
            }
        } else {
            Spacer(Modifier.size(22.dp))
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(name, color = AiriTheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = AiriTheme.onBackground.copy(0.45f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            modifier = Modifier.size(36.dp).clip(AIRIShapes.sm)
                .background(CosmicAccent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
        }
    }
}
// History panel — slides from start edge, shows session list
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
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.history_title),
                    color = AiriTheme.onBackground,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(AiriTheme.onBackground.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        tint = AiriTheme.onBackground.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Divider(color = AiriTheme.outline)

            // New conversation button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(AIRIShapes.md)
                    .background(CosmicAccent.copy(alpha = 0.12f))
                    .border(1.dp, CosmicAccent.copy(alpha = 0.30f), AIRIShapes.md)
                    .clickable { onNewChat() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(AIRIShapes.xs)
                        .background(CosmicAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = AiriTheme.onBackground, modifier = Modifier.size(16.dp))
                }
                Text(
                    stringResource(R.string.new_conversation),
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
                            tint = AiriTheme.onBackground.copy(alpha = 0.25f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.history_no_sessions),
                            color = AiriTheme.outline,
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
                                .clip(AIRIShapes.sm)
                                .clickable { viewModel.loadSession(session.id); onSessionSelected() }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(session.updatedAt)),
                                color = AiriTheme.onBackground.copy(alpha = 0.40f),
                                fontSize = 11.sp
                            )
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    session.title.ifBlank { stringResource(R.string.session_untitled) },
                                    color = AiriTheme.onBackground,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    session.lastMessage.orEmpty().ifBlank { "..." },
                                    color = AiriTheme.onBackground.copy(alpha = 0.45f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Divider(color = AiriTheme.outline)
                    }
                }
            }
        }
    }

}
// Message list
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
    onExportPdf: (String) -> Unit = {},
    onExportMarkdown: (String) -> Unit = {},
    onFeedback: (uid: String, liked: Boolean) -> Unit = { _, _ -> },
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
        // Premium empty state — cosmic orb + greeting + suggestion chips
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "idle_pulse")
                val orbAlpha by infinite.animateFloat(
                    initialValue = 0.14f,
                    targetValue  = 0.32f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation  = androidx.compose.animation.core.tween(2200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "idle_alpha"
                )
                val orbScale by infinite.animateFloat(
                    initialValue = 0.95f,
                    targetValue  = 1.05f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation  = androidx.compose.animation.core.tween(2800, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "idle_scale"
                )
                Spacer(Modifier.height(24.dp))
                // Layered orb with outer halo
                Box(
                    modifier = Modifier.size(110.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer halo
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .graphicsLayer { scaleX = orbScale; scaleY = orbScale }
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        CosmicAccent.copy(alpha = orbAlpha * 0.7f),
                                        CosmicAccentAlt.copy(alpha = orbAlpha * 0.4f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    // Middle ring
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        CosmicAccent.copy(alpha = 0.18f),
                                        SurfaceFloating.copy(alpha = 0.9f)
                                    )
                                )
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.sweepGradient(
                                    listOf(
                                        CosmicAccent.copy(alpha = 0.60f),
                                        CosmicAccentAlt.copy(alpha = 0.30f),
                                        CosmicAccent.copy(alpha = 0.60f)
                                    )
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Inner core — "A" for AIRI
                        Text(
                            text = "A",
                            color = CosmicAccent,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1).sp
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    text = stringResource(R.string.chat_how_can_help),
                    color = AiriTheme.onBackground.copy(alpha = 0.92f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 30.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Your on-device AI assistant — private by default",
                    color = AiriTheme.onSurfaceVariant.copy(alpha = 0.60f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                if (!isModelReady) {
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onOpenModels,
                        shape = AIRIShapes.xl,
                        colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.model_gallery), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
                // Suggestion chips — quick starter prompts
                Spacer(Modifier.height(28.dp))
                val suggestions = listOf(
                    "✍️  Draft an email" to "Help me write a professional email",
                    "📊  Analyze data" to "Analyze this data and explain the trends",
                    "💡  Brainstorm ideas" to "Give me 10 creative ideas for",
                    "🔍  Research topic" to "Research and summarize the topic:"
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    suggestions.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { (label, prompt) ->
                                Surface(
                                    onClick = { onSuggestionClick(prompt) },
                                    modifier = Modifier.weight(1f),
                                    shape = AIRIShapes.md,
                                    color = SurfaceRaised,
                                    border = androidx.compose.foundation.BorderStroke(
                                        0.5.dp, AiriTheme.outline.copy(alpha = 0.50f)
                                    )
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        color = AiriTheme.onSurface.copy(alpha = 0.80f),
                                        fontWeight = FontWeight.Medium,
                                        lineHeight = 17.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    } else {
        Box(modifier = modifier) {
            LazyColumn(
                state               = listState,
                modifier            = Modifier
                    .fillMaxSize()
                    .verticalScrollbar(listState),
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
                            text               = msg.text,
                            imageUri           = msg.imageUri,
                            voiceRecordingPath = msg.voiceRecordingPath,
                            voiceDurationMs    = msg.voiceDurationMs,
                            onEdit             = { onEditMessage(msg.text) },
                            onDelete           = { onDeleteMessage(msg.uid) }
                        )
                    } else {
                        AiBubble(
                            text            = msg.text,
                            agentTag        = msg.agentTag,
                            traceId         = msg.traceId,
                            hideAvatar      = hideAvatar,
                            onShare         = onShareAiResponse,
                            onSpeak         = onSpeak,
                            execOrigin      = msg.execOrigin,
                            onFeedback      = { liked -> onFeedback(msg.uid, liked) },
                            onExportPdf     = onExportPdf,
                            onExportMarkdown = onExportMarkdown,
                            initialFeedback = msg.feedback
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

fun Modifier.verticalScrollbar(
    state: LazyListState,
    color: Color = Color.White.copy(alpha = 0.2f),
    width: Dp = 4.dp
): Modifier = this.drawWithContent {
    drawContent()
    
    val firstVisibleElementIndex = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index
    val totalItemsCount = state.layoutInfo.totalItemsCount
    
    if (firstVisibleElementIndex != null && totalItemsCount > 0) {
        val elementHeight = size.height / totalItemsCount
        val scrollbarHeight = state.layoutInfo.visibleItemsInfo.size * elementHeight
        val scrollbarOffsetY = firstVisibleElementIndex * elementHeight
        
        drawRect(
            color = color,
            topLeft = Offset(size.width - width.toPx(), scrollbarOffsetY),
            size = Size(width.toPx(), scrollbarHeight)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserBubble(
    text: String,
    imageUri: String? = null,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    voiceRecordingPath: String? = null,
    voiceDurationMs: Long = 0L
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
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.FAST)) +
                slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.NORMAL)) { it / 5 }
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box {
                Column(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(AIRIShapes.userBubble)
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
                    // Voice message display
                    if (voiceRecordingPath != null && voiceDurationMs > 0) {
                        com.airi.assistant.ui.components.VoiceMessageBubble(
                            durationMs  = voiceDurationMs,
                            isPlaying   = false,
                            progress    = 0f,
                            onPlayPause = { /* playback handled by parent */ },
                            modifier    = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    if (imageUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(imageUri).crossfade(true).build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                .clip(AIRIShapes.md).background(Color.Black.copy(alpha = 0.25f)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                        if (displayText.isNotBlank()) Spacer(Modifier.height(8.dp))
                    }
                    if (displayText.isNotBlank() || imageUri == null) {
                        Text(text = displayText, color = AiriTheme.onBackground, fontSize = 15.sp, lineHeight = 23.sp)
                    }
                }

                // Contextual menu (long-press)
                DropdownMenu(
                    expanded         = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                    modifier         = Modifier.background(AiriTheme.surfaceVariant)
                ) {
                    DropdownMenuItem(
                        text         = { Text(stringResource(R.string.copy), color = AiriTheme.onBackground, fontSize = 14.sp) },
                        leadingIcon  = { Icon(Icons.Outlined.ContentCopy, null, tint = AiriTheme.onBackground.copy(0.7f), modifier = Modifier.size(16.dp)) },
                        onClick      = {
                            showContextMenu = false
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AIRI", displayText))
                        }
                    )
                    DropdownMenuItem(
                        text         = { Text(stringResource(R.string.edit), color = AiriTheme.onBackground, fontSize = 14.sp) },
                        leadingIcon  = { Icon(Icons.Outlined.Edit, null, tint = AiriTheme.onBackground.copy(0.7f), modifier = Modifier.size(16.dp)) },
                        onClick      = { showContextMenu = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text         = { Text(stringResource(R.string.select_text), color = AiriTheme.onBackground, fontSize = 14.sp) },
                        leadingIcon  = { Icon(Icons.Outlined.TextFields, null, tint = AiriTheme.onBackground.copy(0.7f), modifier = Modifier.size(16.dp)) },
                        onClick      = { showContextMenu = false /* text selection handled by system */ }
                    )
                    DropdownMenuItem(
                        text         = { Text(stringResource(R.string.share), color = AiriTheme.onBackground, fontSize = 14.sp) },
                        leadingIcon  = { Icon(Icons.Outlined.Share, null, tint = AiriTheme.onBackground.copy(0.7f), modifier = Modifier.size(16.dp)) },
                        onClick      = { showContextMenu = false; shareAiResponse(context, displayText) }
                    )
                    Divider(color = AiriTheme.onBackground.copy(alpha = 0.08f))
                    DropdownMenuItem(
                        text         = { Text(stringResource(R.string.delete), color = SemanticError, fontSize = 14.sp) },
                        leadingIcon  = { Icon(Icons.Outlined.Delete, null, tint = SemanticError.copy(0.7f), modifier = Modifier.size(16.dp)) },
                        onClick      = { showContextMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AiBubble(
    text: String,
    agentTag: String? = null,
    traceId: String? = null,
    hideAvatar: Boolean = false,
    onShare: (String) -> Unit = {},
    onSpeak: (String) -> Unit = {},
    execOrigin: com.airi.assistant.execution.ExecOrigin = com.airi.assistant.execution.ExecOrigin.NONE,
    
    initialFeedback: Int = 0,
    
    onFeedback: (liked: Boolean) -> Unit = {},
    onExportPdf: (String) -> Unit = {},
    onExportMarkdown: (String) -> Unit = {}
) {
    val context   = LocalContext.current
    val haptic    = LocalHapticFeedback.current
    val allTraces by com.airi.assistant.ai.agent.trace.AgentTraceManager.instance.traces.collectAsState()
    val trace = remember(traceId, allTraces) {
        if (traceId != null) allTraces.find { it.id == traceId } else null
    }
    var traceExpanded by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }

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
                Box {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                            .background(AiBubbleSurface)
                            .border(1.dp, AiBubbleBorder, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showContextMenu = true
                                }
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        MarkdownText(rawText = text, modifier = Modifier.fillMaxWidth(), baseFontSp = 15f, lineHeightSp = 23f)
                    }

                    DropdownMenu(
                        expanded = showContextMenu,
                        onDismissRequest = { showContextMenu = false },
                        modifier = Modifier.background(AiriTheme.surfaceVariant)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Export as PDF", color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null, tint = AiriTheme.onBackground.copy(0.7f), modifier = Modifier.size(16.dp)) },
                            onClick = { showContextMenu = false; onExportPdf(text) }
                        )
                        DropdownMenuItem(
                            text = { Text("Export as Markdown", color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.Description, null, tint = AiriTheme.onBackground.copy(0.7f), modifier = Modifier.size(16.dp)) },
                            onClick = { showContextMenu = false; onExportMarkdown(text) }
                        )
                    }
                }

                // Action row
                Row(modifier = Modifier.padding(start = 2.dp, top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Speak
                    IconButton(onClick = { onSpeak(text) }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = null, tint = AiriTheme.outline, modifier = Modifier.size(14.dp))
                    }
                    // Copy
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AIRI", text))
                    }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = AiriTheme.outline, modifier = Modifier.size(14.dp))
                    }
                    // Persisted thumbs up/down — initialized from DB feedback column.
                    var liked    by remember { mutableStateOf(initialFeedback == 1) }
                    var disliked by remember { mutableStateOf(initialFeedback == -1) }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val newDisliked = !disliked
                        disliked = newDisliked; if (newDisliked) liked = false
                        onFeedback(false)
                    }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.ThumbDown, contentDescription = null,
                            tint = if (disliked) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(14.dp))
                    }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val newLiked = !liked
                        liked = newLiked; if (newLiked) disliked = false
                        onFeedback(true)
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
                            .clip(AIRIShapes.sm)
                            .background(CosmicAccent.copy(alpha = 0.07f))
                            .border(0.5.dp, if (trace.hasErrors) Color(0xFFFF5252).copy(0.35f) else CosmicAccent.copy(0.3f), AIRIShapes.sm)
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
                            Divider(color = AiriTheme.onBackground.copy(0.05f))
                            Column(modifier = Modifier.padding(10.dp)) {
                                trace.steps.forEachIndexed { i, step ->
                                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                        Text("${i+1}.", color = CosmicAccent.copy(0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(step.displayName, color = AiriTheme.onBackground.copy(0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
                        modifier = Modifier.clip(AIRIShapes.xl).background(CosmicAccent.copy(0.12f))
                            .border(0.5.dp, CosmicAccent.copy(0.35f), AIRIShapes.xl).padding(horizontal = 8.dp, vertical = 3.dp)
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
                .size(30.dp).clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            CosmicAccent.copy(alpha = 0.28f),
                            SurfaceFloating
                        )
                    )
                )
                .border(0.5.dp, CosmicAccent.copy(alpha = 0.50f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "A",
                color = CosmicAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(AIRIShapes.aiBubble)
                .background(AiBubbleSurface)
                .border(0.5.dp, AiBubbleBorder, AIRIShapes.aiBubble)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text       = text,
                    color      = AiriTheme.onBackground.copy(alpha = if (isThinkingStage) 0.50f else 0.93f),
                    fontSize   = 15.sp, lineHeight = 23.sp,
                    fontStyle  = if (isThinkingStage) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                    modifier   = Modifier.weight(1f, fill = false)
                )
                if (!isThinkingStage) BlinkingCursor()
            }
            // AiriThinkingPulse removed — ThinkingAnimation bubble is the single indicator
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
            .clip(AIRIShapes.md)
            .background(AiriTheme.surface.copy(0.55f))
            .border(1.dp, accent.copy(0.35f), AIRIShapes.md)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(36.dp).clip(AIRIShapes.xs).background(accent.copy(0.18f)), contentAlignment = Alignment.Center) {
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
                    modifier = Modifier.matchParentSize().clip(AIRIShapes.xs),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(attachment.displayName, color = AiriTheme.onBackground, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = AiriTheme.onBackground.copy(0.55f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = null, tint = AiriTheme.onBackground.copy(0.7f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BlinkingCursor() {
    var cursorOn by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500L); cursorOn = !cursorOn } }
    AnimatedContent(
        targetState = cursorOn,
        transitionSpec = { fadeIn(animationSpec = androidx.compose.animation.core.tween(80)) togetherWith fadeOut(animationSpec = androidx.compose.animation.core.tween(80)) },
        label = "cursor_blink"
    ) { on -> Text(if (on) "▍" else " ", color = CosmicAccent.copy(0.85f), fontSize = 15.sp, lineHeight = 23.sp) }
}

// Input bar
@OptIn(ExperimentalMaterial3Api::class)
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
    // : called when user converts large prompt to attached file
    onStageFile: (android.net.Uri) -> Unit = {},
    // When non-null, pre-fills the text field
    externalInputText: String? = null,
    onExternalInputConsumed: () -> Unit = {},
    onUserStartedTyping: () -> Unit = {},
    
    onFocusChanged: (Boolean) -> Unit = {},
    
    attachments: List<ChatAttachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {}
) {
    val context          = LocalContext.current
    var showAttachPopup by remember { mutableStateOf(false) }
    val attachSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by rememberSaveable { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(false) }
    val isInferenceReady = modelState.isModelReady || modelState.isCloudReady
    val canSend = text.isNotBlank() && isInferenceReady && !modelState.isModelLoading && !isGenerating
    val isTyping = text.isNotBlank()

    // : Large prompt detection
    val showWarningBanner = text.length in 2001..2999
    val showLimitBottomSheet = text.length >= 3000
    var hasDismissedBottomSheet by remember(text.length < 3000) { mutableStateOf(false) }

    val limitSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            VoiceSessionState.LISTENING   -> while (true) { micPulse.animateTo(1.30f, animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.FAST)); micPulse.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.FAST)) }
            VoiceSessionState.PROCESSING  -> while (true) { micPulse.animateTo(1.18f, animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.SLOWER)); micPulse.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.SLOWER)) }
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

    if (showLimitBottomSheet && !hasDismissedBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { hasDismissedBottomSheet = true },
            sheetState = limitSheetState,
            containerColor = AiriTheme.surface,
            scrimColor = Color.Black.copy(alpha = 0.32f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Outlined.WarningAmber, null,
                    tint = Color(0xFFFFB347),
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    stringResource(R.string.char_limit_reached_title),
                    style = AiriTheme.typography.headlineSmall,
                    color = AiriTheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.char_limit_reached_desc),
                    style = AiriTheme.typography.bodyMedium,
                    color = AiriTheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = {
                        val uri = runCatching {
                            val dir = java.io.File(context.cacheDir, "chat_attachments").apply { mkdirs() }
                            val file = java.io.File(dir, "prompt_${System.currentTimeMillis()}.txt")
                            file.writeText(text)
                            androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                        }.getOrNull()
                        if (uri != null) {
                            onStageFile(uri)
                            text = ""
                            hasDismissedBottomSheet = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                    shape = AIRIShapes.md
                ) {
                    Text(stringResource(R.string.auto_convert), color = Color.White)
                }
                TextButton(
                    onClick = { hasDismissedBottomSheet = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.chat_keep), color = AiriTheme.onSurfaceVariant)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {

        // : Warning banner for 2000-3000 chars
        AnimatedVisibility(
            visible = showWarningBanner,
            enter   = fadeIn() + expandVertically(),
            exit    = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFB347).copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.Info, null,
                    tint     = Color(0xFFFFB347),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    stringResource(R.string.char_limit_warning),
                    fontSize = 12.sp,
                    color    = Color(0xFFFFB347),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Smart reply chips
        AnimatedVisibility(visible = smartReplies.isNotEmpty() && !isGenerating, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                smartReplies.forEach { reply ->
                    Surface(
                        onClick = { onSmartReply(reply) }, shape = AIRIShapes.xl,
                        color = CosmicAccent.copy(0.12f),
                        modifier = Modifier.border(1.dp, CosmicAccent.copy(0.4f), AIRIShapes.xl)
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
                isVadInterrupting                          -> stringResource(R.string.voice_interrupting)
                voiceState == VoiceSessionState.LISTENING  -> stringResource(R.string.voice_listening)
                voiceState == VoiceSessionState.PROCESSING -> stringResource(R.string.voice_processing)
                voiceState == VoiceSessionState.SPEAKING   -> stringResource(R.string.voice_speaking)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clip(AIRIShapes.xl)
                .background(AiriTheme.surface.copy(alpha = 0.97f))
                .border(0.5.dp, Color.White.copy(alpha = 0.08f), AIRIShapes.xl)
        ) {
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(attachments, key = { it.uid }) { attachment ->
                        AttachmentChip(
                            attachment = attachment,
                            onRemove   = { onRemoveAttachment(attachment.uid) }
                        )
                    }
                }
                Divider(color = AiriTheme.outline, thickness = 0.5.dp)
            }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp, max = if (isExpanded) 180.dp else 60.dp)
                        .onFocusChanged { state ->
                            // Propagate focus change upward so toolbar collapses
                            onFocusChanged(state.isFocused)
                        },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = AiriTheme.onBackground, fontSize = 15.sp,
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
                                        else                      -> stringResource(R.string.chat_assign_task_hint)
                                    },
                                    color = AiriTheme.onBackground.copy(0.35f),
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
                        .shadow(if (isInferenceReady) 12.dp else 0.dp, CircleShape, ambientColor = CosmicAccent.copy(0.5f), spotColor = CosmicAccent.copy(0.6f))
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
                            (fadeIn(animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.FAST)) + scaleIn(initialScale = 0.7f)) togetherWith
                            (fadeOut(animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.FAST)) + scaleOut(targetScale = 0.7f))
                        },
                        label = "main_btn"
                    ) { state ->
                        when (state) {
                            "stop" -> Icon(Icons.Default.Stop, null, tint = AiriTheme.onBackground, modifier = Modifier.size(20.dp))
                            "send" -> Icon(Icons.Default.ArrowUpward, null, tint = AiriTheme.onBackground, modifier = Modifier.size(20.dp))
                            else   -> Icon(Icons.Default.GraphicEq, null, tint = AiriTheme.onBackground, modifier = Modifier.size(20.dp))
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
                    Icon(Icons.Default.Add, null, tint = AiriTheme.onBackground.copy(if (!isGenerating) 0.7f else 0.3f), modifier = Modifier.size(20.dp))
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
                            .clip(AIRIShapes.xl)
                            .background(AiriTheme.surfaceVariant)
                            .border(1.dp, Color.White.copy(0.12f), AIRIShapes.xl)
                            .clickable { onNavigate(AiriRoute.CONNECTORS) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Outlined.Hub, null, tint = CosmicAccent, modifier = Modifier.size(14.dp))
                            Icon(Icons.Outlined.ChevronRight, null, tint = AiriTheme.onBackground.copy(0.45f), modifier = Modifier.size(12.dp))
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // Expand / collapse toggle
                IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        null,
                        tint = AiriTheme.onBackground.copy(0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

    }
    if (showAttachPopup) {
        ModalBottomSheet(
            onDismissRequest = { showAttachPopup = false },
            sheetState = attachSheetState,
            containerColor = AiriTheme.surfaceVariant,
            contentColor = AiriTheme.onBackground,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            dragHandle = {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 32.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(AiriTheme.onBackground.copy(0.25f))
                    )
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Text(
                    text = stringResource(R.string.attach_section_media),
                    color = AiriTheme.onBackground.copy(0.45f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AttachCard(
                        icon = Icons.Outlined.Image,
                        label = stringResource(R.string.attach_image),
                        modifier = Modifier.weight(1f)
                    ) { showAttachPopup = false; onPickImage() }
                    AttachCard(
                        icon = Icons.Outlined.CameraAlt,
                        label = stringResource(R.string.attach_camera),
                        modifier = Modifier.weight(1f)
                    ) { showAttachPopup = false; onTakePhoto() }
                    AttachCard(
                        icon = Icons.Outlined.AttachFile,
                        label = stringResource(R.string.attach_files),
                        modifier = Modifier.weight(1f)
                    ) { showAttachPopup = false; onPickFile() }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.attach_section_actions),
                    color = AiriTheme.onBackground.copy(0.45f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                AttachListRow(
                    icon = Icons.Outlined.Storage,
                    label = stringResource(R.string.attach_spreadsheet)
                ) {
                    showAttachPopup = false
                    text = text + if (text.isBlank()) context.getString(R.string.chat_create_spreadsheet_prefix) else "\n${context.getString(R.string.chat_create_spreadsheet_prefix)}"
                }
                AttachListRow(
                    icon = Icons.Outlined.History,
                    label = stringResource(R.string.attach_scheduled_tasks)
                ) {
                    showAttachPopup = false
                    onNavigate(AiriRoute.AGENT_TASKS)
                }
                AttachListRow(
                    icon = Icons.Outlined.Mic,
                    label = stringResource(R.string.attach_conversation_mode)
                ) {
                    showAttachPopup = false
                    onVoiceChatClick()
                }
                AttachListRow(
                    icon = Icons.Outlined.Edit,
                    label = stringResource(R.string.attach_edit_image)
                ) {
                    showAttachPopup = false
                    text = text + if (text.isBlank()) context.getString(R.string.chat_edit_image_prefix) else "\n${context.getString(R.string.chat_edit_image_prefix)}"
                    onPickImage()
                }
            }
        }
    }
}

@Composable
private fun AttachCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(AIRIShapes.md)
            .background(CosmicAccent.copy(0.12f))
            .border(1.dp, CosmicAccent.copy(0.35f), AIRIShapes.md)
            .clickable { onClick() }
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = CosmicAccent, modifier = Modifier.size(26.dp))
        Text(
            text = label,
            color = AiriTheme.onBackground.copy(0.85f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun AttachListRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(AIRIShapes.md)
                .background(CosmicAccent.copy(0.12f))
                .border(1.dp, CosmicAccent.copy(0.28f), AIRIShapes.md),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = CosmicAccent, modifier = Modifier.size(20.dp))
        }
        Text(
            text = label,
            color = AiriTheme.onBackground.copy(0.85f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun ModelErrorDialog(error: String, errorType: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AiriTheme.surface,
        titleContentColor = Color.White, textContentColor = Color.White,
        shape = AIRIShapes.xl,
        title = { Text(stringResource(R.string.model_error), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error)
                Text(errorType, color = AiriTheme.onBackground.copy(0.45f), fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = AiriTheme.onBackground)) {
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
    
    val user    = remember { ServiceLocator.authService.currentUser() }
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
                                Text(stringResource(R.string.app_agent_title), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground, fontSize = 15.sp)
                                Text(email, color = AiriTheme.onBackground.copy(0.5f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Divider(color = AiriTheme.onBackground.copy(0.06f))
                Spacer(Modifier.height(8.dp))
                DrawerActionItem(icon = Icons.Outlined.AddComment, label = stringResource(R.string.new_chat), onClick = onNewChat)
                DrawerNavItem(icon = Icons.Outlined.Forum, label = stringResource(R.string.chats), route = AiriRoute.HISTORY, onNavigate = onNavigate)
                DrawerNavItem(icon = Icons.Outlined.Psychology, label = stringResource(R.string.memory), route = AiriRoute.MEMORY, onNavigate = onNavigate)
                DrawerNavItem(icon = Icons.Outlined.Extension, label = stringResource(R.string.integrations), route = AiriRoute.INTEGRATIONS, onNavigate = onNavigate)
                DrawerNavItem(icon = Icons.Outlined.BuildCircle, label = stringResource(R.string.custom_skills), route = AiriRoute.SKILL_MANAGER, onNavigate = onNavigate)
                DrawerNavItem(icon = Icons.Outlined.Share, label = stringResource(R.string.invite_friends), route = AiriRoute.REFERRALS, onNavigate = onNavigate)
                Spacer(Modifier.height(4.dp))
                Divider(color = AiriTheme.onBackground.copy(0.06f))
                Spacer(Modifier.height(4.dp))
                DrawerNavItem(icon = Icons.Outlined.ManageHistory, label = stringResource(R.string.agent_logs), route = AiriRoute.AGENT_LOGS, onNavigate = onNavigate)
                DrawerNavItem(icon = Icons.Outlined.Tune, label = stringResource(R.string.agent_control), route = AiriRoute.AGENT_CONTROL, onNavigate = onNavigate)
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(32.dp).align(Alignment.BottomCenter).offset(y = (-112).dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0D1124))))
            )
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(AiriTheme.surface)) {
                Divider(color = AiriTheme.onBackground.copy(0.08f))
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
        icon = { Icon(icon, null, tint = AiriTheme.onBackground.copy(0.7f)) },
        label = { Text(label, color = AiriTheme.onBackground) },
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
        containerColor = AiriTheme.surface, titleContentColor = Color.White, textContentColor = Color.White,
        shape = AIRIShapes.xl,
        title = { Text(stringResource(R.string.generation_settings), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.generation_settings_description), color = AiriTheme.onBackground.copy(0.5f), fontSize = 12.sp)
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.temperature), fontSize = 13.sp)
                        Text("%.1f".format(temperature), color = CosmicAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = temperature, onValueChange = { viewModel.setTemperature(it) }, valueRange = 0.1f..2.0f,
                        colors = SliderDefaults.colors(thumbColor = CosmicAccent, activeTrackColor = CosmicAccent))
                    Text(stringResource(R.string.temperature_hint), color = AiriTheme.onBackground.copy(0.35f), fontSize = 11.sp)
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
                        placeholder = { Text(stringResource(R.string.leave_empty_default), color = AiriTheme.onBackground.copy(0.3f), fontSize = 12.sp) },
                        minLines = 2, maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent, unfocusedBorderColor = Color.White.copy(0.15f), focusedTextColor = Color.White, unfocusedTextColor = AiriTheme.onBackground)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = AiriTheme.onBackground)) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = AiriTheme.onBackground.copy(0.6f)) } }
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
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.FAST)) + scaleIn(
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness    = androidx.compose.animation.core.Spring.StiffnessMedium
            ),
            initialScale = 0.60f
        ),
        exit  = fadeOut(animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.FAST)) + scaleOut(targetScale = 0.70f),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(CosmicAccentAlt, CosmicAccent)
                    )
                )
                .shadow(8.dp, CircleShape, ambientColor = CosmicAccent, spotColor = CosmicAccent)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
