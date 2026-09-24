package com.airi.assistant.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.WindowInsets
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.OpenableColumns
import android.util.Size as AndroidSize
import android.media.projection.MediaProjectionManager
import androidx.compose.runtime.DisposableEffect
import com.airi.assistant.voice.VoskEngine
import com.airi.assistant.voice.VoskModelManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.*
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.Divider
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
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
import com.airi.core.attachments.AttachmentPolicy
import com.airi.assistant.domain.ChatAttachment
import androidx.compose.foundation.lazy.LazyRow
import com.airi.assistant.util.ChatExporter
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.AgentMode
import com.airi.assistant.ui.viewmodel.AttachmentDispatchFailure
import com.airi.assistant.ui.viewmodel.ChatInputSuggestion
import com.airi.assistant.ui.viewmodel.ChatMessage
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.ui.viewmodel.FinalAnswerUiState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.airi.assistant.ui.viewmodel.ModelUiState
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.ui.activity.TaskInfoBottomSheet
import com.airi.assistant.ui.activity.TaskInfoTrigger
import com.airi.assistant.auth.identity.BiometricGatekeeper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.airi.assistant.ui.text.BidiAwareMarkdownRenderer
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class VoiceSessionState { IDLE, LISTENING, PROCESSING, SPEAKING }

private data class AttachmentMetadata(
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?
)

private fun resolveAttachmentMetadata(
    context: android.content.Context,
    uri: Uri,
    fallbackName: String
): AttachmentMetadata {
    val values = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = nameIndex.takeIf { it >= 0 }?.let(cursor::getString)
            val size = sizeIndex.takeIf { it >= 0 }?.let(cursor::getLong)?.takeIf { it >= 0L }
            name to size
        }
    }.getOrNull()
    val resolvedMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        ?.substringBefore(';')
        ?.takeIf { it.isNotBlank() }
        ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(values?.first?.substringAfterLast('.', "")?.lowercase())
    return AttachmentMetadata(
        displayName = AttachmentPolicy.normalizedDisplayName(values?.first ?: fallbackName),
        mimeType = resolvedMime,
        sizeBytes = values?.second
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onChatActiveChanged: (Boolean) -> Unit = {},
    bottomNavVisible: Boolean = false,
    onBottomNavToggle: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context       = LocalContext.current
    val profilePreferences = remember {
        context.getSharedPreferences("airi_profile", Context.MODE_PRIVATE)
    }
    var profileDisplayName by remember {
        mutableStateOf(profilePreferences.getString("display_name", "").orEmpty())
    }
    DisposableEffect(profilePreferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "display_name") {
                profileDisplayName = profilePreferences.getString("display_name", "").orEmpty()
            }
        }
        profilePreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { profilePreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val scope         = rememberCoroutineScope()
    val messages      by viewModel.messages.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val agentState    by viewModel.agentState.collectAsState()
    val modelState    by viewModel.modelState.collectAsState()
    val capabilityDescriptor = remember(modelState) { viewModel.currentCapabilityDescriptor() }
    val agentMode     by viewModel.agentMode.collectAsState()
    val smartReplies  by viewModel.smartReplies.collectAsState()
    val attachmentDispatchInFlight by viewModel.attachmentDispatchInFlight.collectAsState()
    val dailyCreditsRemaining  by viewModel.dailyCreditsRemaining.collectAsState()
    val isProPlan = ServiceLocator.subscriptionManager.isPro()
    // : real-time network state — drives offline banner
    val isOnline      by viewModel.isOnline.collectAsState()
    // LiveVoiceService — voice mode state
    val voiceModeActive    by viewModel.voiceModeActive.collectAsState()
    val snackbarHost  = remember { SnackbarHostState() }
    val paywallTrigger        by viewModel.paywallTrigger.collectAsState()
    val upgradePrompt         by viewModel.upgradePrompt.collectAsState()
    val systemIntegrityFailed by viewModel.systemIntegrityFailed.collectAsState()
    val contextResetWarning   by viewModel.contextResetWarning.collectAsState()
    val isSummarizing         by viewModel.isSummarizing.collectAsState()
    val pendingSummary        by viewModel.pendingSummary.collectAsState()
    val currentSessionId      by viewModel.currentSessionId.collectAsState()
    val sessions              by viewModel.sessions.collectAsState()
    val favoriteSessionIds    by viewModel.favoriteSessionIds.collectAsState()
    val composerDrafts        by viewModel.composerDrafts.collectAsState()
    val currentComposerDraft = composerDrafts[currentSessionId]
    val currentSession = sessions.firstOrNull { it.id == currentSessionId }
    val sessionActions = ChatSessionActionPolicy.availability(
        hasPersistedSession = currentSession?.id == currentSessionId,
        sessionId = currentSessionId,
        messageCount = maxOf(currentSession?.messageCount ?: 0, messages.size),
        title = currentSession?.title.orEmpty(),
    )
    var skillSuggestions by remember { mutableStateOf<List<ChatInputSuggestion>>(emptyList()) }
    var knowledgeSuggestions by remember { mutableStateOf<List<ChatInputSuggestion>>(emptyList()) }
    var knowledgeSearchVersion by remember { mutableStateOf(0) }

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
    var partialVoiceInput   by remember { mutableStateOf("") }
    var voiceState          by remember { mutableStateOf(VoiceSessionState.IDLE) }
    var showWakeActionSheet by remember { mutableStateOf(false) }

    // /C04: AgentPlanViewModel for ModalBottomSheet control
    val agentPlanViewModel: com.airi.assistant.ui.plan.AgentPlanViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    LaunchedEffect(currentSessionId) {
        agentPlanViewModel.setContext(
            com.airi.assistant.ui.plan.PlanContext(
                sessionId = currentSessionId,
                projectId = ServiceLocator.workspaceRuntime.activeSession.value?.sessionId.orEmpty()
            )
        )
    }
    val isPanelVisible by agentPlanViewModel.isVisible.collectAsState()
    val showPanel      by agentPlanViewModel.showPanel.collectAsState()

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
            partialVoiceInput = ""
            voiceState = VoiceSessionState.LISTENING
            engine.start(
                scope     = this,
                onPartial = { partial ->
                    partialVoiceInput = partial
                },
                onFinal   = { spoken ->
                    partialVoiceInput = ""
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
                onError = { _ ->
                    partialVoiceInput = ""
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

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch { snackbarHost.showSnackbar("تم السماح بمشاركة الشاشة مع Airi لهذه الجلسة") }
        }
    }

    LaunchedEffect(wakeCounter) {
        if (wakeCounter > 0) showWakeActionSheet = true
    }

    if (showWakeActionSheet) {
        WakeActionSheet(
            onDismiss = { showWakeActionSheet = false },
            onChat = {
                showWakeActionSheet = false
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startInAppStt(autoSend = true)
                } else {
                    voiceChatPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onShareScreen = {
                showWakeActionSheet = false
                val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
            }
        )
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
                scope.launch { snackbarHost.showSnackbar(context.userFacingVoiceError(error)) }
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

    val pendingAttachments = currentComposerDraft?.attachments.orEmpty()

    fun addAttachment(att: ChatAttachment) {
        if (pendingAttachments.any { existing ->
                AttachmentPolicy.isSameSource(existing.uri?.toString(), att.uri?.toString())
            }) {
            scope.launch { snackbarHost.showSnackbar(context.getString(R.string.attachment_already_added)) }
            return
        }
        if (pendingAttachments.size >= AttachmentPolicy.MAX_ATTACHMENTS_PER_MESSAGE) {
            scope.launch { snackbarHost.showSnackbar(context.getString(R.string.attachment_limit_reached)) }
            return
        }
        when (AttachmentPolicy.validateSize(att.sizeBytes, att.contentType)) {
            AttachmentPolicy.ValidationResult.Accepted ->
                viewModel.updateComposerAttachments(pendingAttachments + att)
            AttachmentPolicy.ValidationResult.TooLarge -> scope.launch {
                snackbarHost.showSnackbar(context.getString(R.string.attachment_too_large))
            }
            AttachmentPolicy.ValidationResult.TextTooLarge -> scope.launch {
                snackbarHost.showSnackbar(context.getString(R.string.text_attachment_too_large))
            }
        }
    }
    fun removeAttachment(id: String) {
        viewModel.updateComposerAttachments(pendingAttachments.filterNot { it.id == id })
    }

    fun stageUriAttachment(uri: Uri, kind: ChatAttachment.Kind, fallbackName: String) {
        val metadata = resolveAttachmentMetadata(context, uri, fallbackName)
        addAttachment(
            ChatAttachment(
                kind = kind,
                uri = uri,
                displayName = metadata.displayName,
                mimeType = metadata.mimeType,
                sizeBytes = metadata.sizeBytes
            )
        )
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { stageUriAttachment(it, ChatAttachment.Kind.FILE, "file") }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { stageUriAttachment(it, ChatAttachment.Kind.IMAGE, "image") }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { stageUriAttachment(it, ChatAttachment.Kind.VIDEO, "video") }
    }
    val textPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { stageUriAttachment(it, ChatAttachment.Kind.FILE, "text.txt") }
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

    // Converted long prompts arrive from the ViewModel as a URI and must be
    // staged into the active conversation draft before the original field is cleared.
    LaunchedEffect(Unit) {
        viewModel.stagedAttachmentUri.collect { uri ->
            stageUriAttachment(uri, ChatAttachment.Kind.FILE, "prompt.txt")
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
            ChatViewModel.PlusPickerRequest.FILE      -> { filePicker.launch(arrayOf("*/*"));        viewModel.consumePlusPickerRequest() }
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
    var showTaskInfo by remember { mutableStateOf(false) }
    val isTaskConversation = agentState.isWorking || isPlanModeActive

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
                dailyCreditsRemaining  = dailyCreditsRemaining,
                onHistoryOpen     = { showHistoryPanel = true },
                onExit            = { onNavigate(AiriRoute.HISTORY) },
                onModelPickerOpen = { showModelPicker = true },
                onToggleDropdown  = { showMenu = !showMenu },
                onDismissDropdown = { showMenu = false },
                onGenSettings     = { showMenu = false; showGenSettings = true },
                onModeSelected    = { viewModel.setAgentMode(it) },
                onSwitchModel     = { showMenu = false; onNavigate(AiriRoute.MODELS) },
                onExportChat      = { showMenu = false; exportChatLauncher.launch(ChatExporter.buildFileName("md")) },
                onShareChat       = { showMenu = false; shareChatTranscript(context, messages) },
                currentSessionTitle = currentSession?.title.orEmpty(),
                sessionActions = sessionActions,
                isCurrentSessionPinned = currentSession?.isPinned == true,
                onSetSessionPinned = { isPinned -> viewModel.setCurrentSessionPinned(isPinned) },
                isCurrentSessionFavorite = currentSessionId in favoriteSessionIds,
                onSetSessionFavorite = { favorite -> viewModel.setSessionFavorite(currentSessionId, favorite) },
                onArchiveSession = { viewModel.archiveSession(currentSessionId) },
                onRenameChat      = { title -> viewModel.renameCurrentSession(title) },
                onNewChat         = { viewModel.clearMessages() },
                planLabel          = if (isProPlan) "Pro" else "Free",
                onPointsClick     = { onNavigate(if (isProPlan) AiriRoute.PRO_PLAN else AiriRoute.FREE_PLAN) },
                onNavigate        = onNavigate
            )
        },
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                    // Keep the live plan in the same bottom-bar column as the
                    // composer. This prevents a modal sheet from covering the
                    // task context and keeps steps/traces synchronized with the
                    // actual ExecutionStatusBus-owned AgentPlanViewModel.
                    if (isPanelVisible && (showPanel || isPlanModeActive)) {
                        com.airi.assistant.ui.plan.AgentPlanOverlay(
                            modifier = Modifier.fillMaxWidth(),
                            planViewModel = agentPlanViewModel
                        )
                    }
                    TaskInfoTrigger(
                        isWorking = agentState.isWorking,
                        isTaskConversation = isTaskConversation,
                        executionId = agentState.executionId.takeIf { it.isNotBlank() },
                        onClick = { showTaskInfo = true }
                    )
                    // Activity feed only visible while agent is executing
                AnimatedVisibility(
                    visible = agentState.isWorking,
                    enter   = fadeIn() + expandVertically(),
                    exit    = fadeOut() + shrinkVertically()
                ) {
                    com.airi.assistant.ui.activity.ActivityFeedComposable(
                        modifier        = Modifier.fillMaxWidth(),
                        compactMaxItems = 3,
                        executionId     = agentState.executionId.takeIf { it.isNotBlank() },
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
                    isDispatchingAttachment = attachmentDispatchInFlight,
                    voiceInput    = voiceInput,
                    voicePartial  = partialVoiceInput,
                    smartReplies  = smartReplies,
                    hasUserSentMessage = messages.any { it.isUser },
                    onSend        = { text, onAccepted ->
                        val toSend = pendingAttachments
                        if (toSend.isNotEmpty()) {
                            viewModel.sendMessageWithAttachments(
                                input = text,
                                attachments = toSend,
                                onAccepted = {
                                    viewModel.clearCurrentComposerAttachments()
                                    onAccepted()
                                },
                                onRejected = { failure ->
                                    val messageRes = when (failure) {
                                        AttachmentDispatchFailure.MODEL_LOADING -> R.string.attachment_model_loading
                                        AttachmentDispatchFailure.GENERATION_IN_PROGRESS -> R.string.attachment_generation_in_progress
                                        AttachmentDispatchFailure.SESSION_CHANGED -> R.string.attachment_session_changed
                                        AttachmentDispatchFailure.VISION_UNAVAILABLE -> R.string.attachment_vision_unavailable
                                        AttachmentDispatchFailure.CAPABILITY_UNAVAILABLE -> R.string.attachment_capability_unavailable
                                        AttachmentDispatchFailure.CAPABILITY_UNKNOWN -> R.string.attachment_capability_unknown
                                        AttachmentDispatchFailure.STAGING_FAILED -> R.string.attachment_staging_failed
                                    }
                                    scope.launch { snackbarHost.showSnackbar(context.getString(messageRes)) }
                                },
                            )
                        } else if (viewModel.sendMessage(text)) {
                            onAccepted()
                        }
                    },
                    onCancel      = { viewModel.cancelGeneration() },
                    onSmartReply  = { reply -> viewModel.clearSmartReplies(); viewModel.sendMessage(reply) },
                    onPickImage   = { imagePicker.launch("image/*") },
                    onPickVideo   = { videoPicker.launch("video/*") },
                    onPickText    = { textPicker.launch(arrayOf("text/*", "application/json", "application/xml")) },
                    onPickFile    = { filePicker.launch(arrayOf("*/*")) },
                    onOpenPromptBuilder = { onNavigate(AiriRoute.PROMPT_BUILDER) },
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
                        // The active voice-chat path is local Vosk STT plus Android TTS.
                        // Realtime providers remain isolated until their PCM transport is wired end-to-end;
                        // the UI must never claim a cloud session when it is using local recognition.
                        when {
                            !VoskModelManager.isReady(context) -> onNavigate(AiriRoute.VOICE_SETTINGS)
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED -> {
                                viewModel.toggleVoiceMode()
                                if (!voiceModeActive) {
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
                    draftText            = currentComposerDraft?.text.orEmpty(),
                    onDraftTextChanged   = viewModel::updateComposerText,
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
                    onWebClick        = { viewModel.prefillInput("/skill:web_search ") },
                    onCodeClick       = { viewModel.prefillInput("/skill:code_assistant ") },
                    skillSuggestions = skillSuggestions,
                    knowledgeSuggestions = knowledgeSuggestions,
                    onSkillQueryChanged = { query ->
                        skillSuggestions = viewModel.searchSkillsForQuery(query)
                        knowledgeSuggestions = emptyList()
                    },
                    onKnowledgeQueryChanged = { query ->
                        skillSuggestions = emptyList()
                        val version = knowledgeSearchVersion + 1
                        knowledgeSearchVersion = version
                        scope.launch {
                            val results = viewModel.searchKnowledgeForQuery(query)
                            if (version == knowledgeSearchVersion) knowledgeSuggestions = results
                        }
                    },
                    // Pass attachments so they render inside the pill
                    attachments         = pendingAttachments,
                    onRemoveAttachment  = { uid ->
                        viewModel.updateComposerAttachments(
                            pendingAttachments.filterNot { it.id == uid || it.uid == uid }
                        )
                    },
                    bottomNavVisible = bottomNavVisible,
                    onBottomNavToggle = onBottomNavToggle,
                    imageInputEnabled = capabilityDescriptor.isReady(com.airi.assistant.execution.Capability.IMAGE_UNDERSTANDING)
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            ChatMessageList(
                messages      = messages,
                streamingText = streamingText,
                isGenerating  = agentState.isWorking,
                finalAnswerVerification = agentState.finalAnswerVerification,
                isModelReady  = modelState.isModelReady,
                isCloudReady  = modelState.isCloudReady,
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
                profileDisplayName = profileDisplayName,
                onEditMessage      = { text -> viewModel.prefillInput(text) },
                onDeleteMessage    = { message ->
                    scope.launch {
                        viewModel.deleteMessage(message).onFailure {
                            snackbarHost.showSnackbar(context.getString(R.string.message_delete_failed))
                        }
                    }
                },
                onExportPdf        = { exportPdfLauncher.launch(ChatExporter.buildFileName("pdf")) },
                onExportMarkdown   = { exportMdLauncher.launch(ChatExporter.buildFileName("md")) },
                onFeedback         = { uid, liked -> viewModel.submitFeedback(uid, liked) },
                modifier = Modifier.fillMaxSize()
            )

            // : Thinking animation — shown between send and first streaming token.
            // Replaces the frozen-UI gap that users see during local LLM inference (2–15 s).
            // Condition: agent is working BUT no streamed text yet (first token hasn't arrived).
            if (agentState.isWorking && streamingText.isEmpty()) {
                Surface(
                    shape = AIRIShapes.pill,
                    color = AiriTheme.surfaceVariant.copy(alpha = 0.94f),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, AiriTheme.outline.copy(alpha = 0.45f)),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.mipmap.ic_launcher_foreground),
                            contentDescription = "AIRI",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = agentState.currentAction.takeIf { it.isNotBlank() } ?: stringResource(R.string.generating),
                            color = AiriTheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
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

    if (showTaskInfo) {
        TaskInfoBottomSheet(
            isWorking = agentState.isWorking,
            executionId = agentState.executionId.takeIf { it.isNotBlank() },
            onDismiss = { showTaskInfo = false }
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
            containerColor   = AiriTheme.surface,
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
                        color = AiriTheme.surfaceVariant
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
                    border  = BorderStroke(1.dp, AiriTheme.outline.copy(0.7f)),
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
    dailyCreditsRemaining: Int = 200,
    planLabel: String = "Free",
    onHistoryOpen: () -> Unit,
    onExit: () -> Unit,
    onModelPickerOpen: () -> Unit,
    onToggleDropdown: () -> Unit,
    onDismissDropdown: () -> Unit,
    onGenSettings: () -> Unit,
    onModeSelected: (AgentMode) -> Unit,
    onSwitchModel: () -> Unit,
    onExportChat: () -> Unit,
    onShareChat: () -> Unit,
    currentSessionTitle: String,
    sessionActions: ChatSessionActionAvailability,
    isCurrentSessionPinned: Boolean,
    onSetSessionPinned: (Boolean) -> Unit,
    isCurrentSessionFavorite: Boolean,
    onSetSessionFavorite: (Boolean) -> Unit,
    onArchiveSession: () -> Unit,
    onRenameChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onPointsClick: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = AiriTheme.background.copy(alpha = 0.92f)
        ),
        navigationIcon = {
            Text(
                text = "AIRI",
                color = AiriTheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp),
            )
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
                        .padding(horizontal = 14.dp, vertical = 7.dp),
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
                            modelState.isModelLoading -> stringResource(R.string.loading_model)
                            modelState.isModelReady || modelState.isCloudReady ->
                                ChatPresentationPolicy.humanModelLabel(
                                    rawId = if (modelState.isModelReady) modelState.selectedModelId else modelState.cloudModelName,
                                    isLocal = modelState.isModelReady,
                                    isCloud = modelState.isCloudReady,
                                )
                            else -> stringResource(R.string.no_model_active)
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
            var showRenameDialog by remember { mutableStateOf(false) }
            var renameDraft by remember(currentSessionTitle) { mutableStateOf(currentSessionTitle) }
            if (showRenameDialog) {
                AlertDialog(
                    onDismissRequest = { showRenameDialog = false },
                    title = { Text(stringResource(R.string.rename_chat_title)) },
                    text = {
                        OutlinedTextField(
                            value = renameDraft,
                            onValueChange = { renameDraft = it.take(80) },
                            label = { Text(stringResource(R.string.rename_chat_hint)) },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onRenameChat(renameDraft)
                                showRenameDialog = false
                            },
                            enabled = renameDraft.trim().isNotBlank()
                        ) { Text(stringResource(R.string.save)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRenameDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }
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
                            text  = { Text(stringResource(R.string.switch_model), color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.Memory, contentDescription = null, tint = CosmicAccent) },
                            onClick = onSwitchModel
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.project_home_add_file), color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, tint = CosmicAccent) },
                            onClick = { onDismissDropdown(); onNavigate(AiriRoute.WORKSPACE) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_project_files), color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = CosmicAccent) },
                            onClick = { onDismissDropdown(); onNavigate(AiriRoute.WORKSPACE) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_recent_tasks), color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null, tint = CosmicAccent) },
                            onClick = { onDismissDropdown(); onNavigate(AiriRoute.AGENT_TASKS) }
                        )
                        if (sessionActions.canShareOrExport || sessionActions.canPinOrRename) {
                            Divider(color = AiriTheme.outline.copy(alpha = 0.35f))
                        }
                        if (sessionActions.canShareOrExport) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share_chat), color = AiriTheme.onBackground) },
                                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, tint = AiriTheme.onSurfaceVariant) },
                                onClick = onShareChat,
                            )
                        }
                        if (sessionActions.canPinOrRename) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(if (isCurrentSessionPinned) R.string.unpin_chat else R.string.pin_chat),
                                        color = AiriTheme.onBackground,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.PushPin,
                                        contentDescription = null,
                                        tint = AiriTheme.onSurfaceVariant,
                                    )
                                },
                                onClick = {
                                    onSetSessionPinned(!isCurrentSessionPinned)
                                    onDismissDropdown()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename_chat), color = AiriTheme.onBackground) },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = AiriTheme.onSurfaceVariant) },
                                onClick = {
                                    renameDraft = currentSessionTitle
                                    showRenameDialog = true
                                    onDismissDropdown()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (isCurrentSessionFavorite) stringResource(R.string.library_remove_favorite) else stringResource(R.string.library_toggle_favorite), color = AiriTheme.onBackground) },
                                leadingIcon = { Icon(if (isCurrentSessionFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null, tint = CosmicAccent) },
                                onClick = { onSetSessionFavorite(!isCurrentSessionFavorite); onDismissDropdown() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.archive_chat), color = AiriTheme.onBackground) },
                                leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null, tint = AiriTheme.onSurfaceVariant) },
                                onClick = { onDismissDropdown(); onArchiveSession() }
                            )
                        }
                        if (sessionActions.canShareOrExport) {
                            DropdownMenuItem(
                                text  = { Text(stringResource(R.string.export_chat), color = AiriTheme.onBackground) },
                                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, tint = AiriTheme.onSurfaceVariant) },
                                onClick = onExportChat,
                            )
                        }
                    }
                }
                IconButton(onClick = onToggleDropdown) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.more_options),
                        tint = AiriTheme.onBackground.copy(alpha = 0.65f)
                    )
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
    val context = LocalContext.current
    val sessions by viewModel.sessions.collectAsState()
    val favoriteSessionIds by viewModel.favoriteSessionIds.collectAsState()
    var renameTarget by remember { mutableStateOf<com.airi.assistant.memory.dao.ChatSessionSummary?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    ModalDrawerSheet(
        drawerContainerColor = AiriTheme.surface,
        drawerContentColor   = AiriTheme.onSurface,
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
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            HistorySessionItem(
                                session = session,
                                onSelect = {
                                    viewModel.loadSession(session.id)
                                    onSessionSelected()
                                },
                                onDelete = { viewModel.deleteSession(session.id) },
                                onRename = { renameDraft = session.title; renameTarget = session },
                                onPin = { viewModel.setSessionPinned(session.id, !session.isPinned) },
                                isFavorite = session.id in favoriteSessionIds,
                                onFavorite = { viewModel.setSessionFavorite(session.id, session.id !in favoriteSessionIds) },
                                onArchive = { viewModel.archiveSession(session.id) },
                                onShare = { shareSession(context, session) }
                            )
                    }
                }
            }
        }
    }
    renameTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = AiriTheme.surface,
            title = { Text(stringResource(R.string.rename_chat), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it.take(80) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.rename_chat_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSession(session.id, renameDraft)
                    renameTarget = null
                }) { Text(stringResource(R.string.rename_chat)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
// Message list
@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    streamingText: String,
    isGenerating: Boolean,
    finalAnswerVerification: FinalAnswerUiState? = null,
    isModelReady: Boolean = false,
    isCloudReady: Boolean = false,
    onOpenModels: () -> Unit = {},
    onShareAiResponse: (String) -> Unit = {},
    onSpeak: (String) -> Unit = {},
    onSuggestionClick: (String) -> Unit = {},
    profileDisplayName: String = "",
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (ChatMessage) -> Unit = {},
    onExportPdf: (String) -> Unit = {},
    onExportMarkdown: (String) -> Unit = {},
    onFeedback: (uid: String, liked: Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()
    val reversedMessages = remember(messages) { messages.reversed() }
    val isPinnedToBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 48
        }
    }
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
                    initialValue  = 0.95f,
                    targetValue  = 1.05f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation  = androidx.compose.animation.core.tween(2800, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "idle_scale"
                )
                Spacer(Modifier.height(28.dp))
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = "AIRI",
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer {
                            alpha = orbAlpha
                            scaleX = orbScale
                            scaleY = orbScale
                        }
                )
                Spacer(Modifier.height(12.dp))
                val greetings = listOf(
                    "مرحباً، أنا AIRI. ماذا سنفعل اليوم؟",
                    "أهلاً بك. ما الجديد الذي تريد إنجازه؟",
                    "أنا هنا لمساعدتك — من أين نبدأ؟"
                )
                // The greeting is selected once when this screen enters composition;
                // it never changes on a timer or while the user is reading it.
                val greeting = remember { greetings.first() }
                Text(
                    text = greeting,
                    color = AiriTheme.onBackground.copy(alpha = 0.92f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 30.sp
                )
                Spacer(Modifier.height(22.dp))
                val greetingName = profileDisplayName.trim().take(48)
                Text(
                    text = if (greetingName.isBlank()) {
                        stringResource(R.string.chat_how_can_help)
                    } else {
                        stringResource(R.string.chat_how_can_help_name, greetingName)
                    },
                    color = AiriTheme.onBackground.copy(alpha = 0.92f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 30.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.chat_empty_state_tagline),
                    color = AiriTheme.onSurfaceVariant.copy(alpha = 0.60f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                // Deliberately empty action area: the welcome state contains
                // only the greeting/tagline. Actions remain available through
                // the composer and navigation, not as central starter cards.
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
                    item(key = "streaming", contentType = "streaming") {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            AiStreamingBubble(text = streamingText)
                        }
                    }
                }
                if (finalAnswerVerification != null && !isGenerating) {
                    item(key = "final_answer_verification", contentType = "verification") {
                        FinalAnswerVerificationBadge(finalAnswerVerification)
                    }
                }
                itemsIndexed(reversedMessages, key = { _, msg -> msg.uid }, contentType = { _, msg -> if (msg.isUser) "user" else "assistant" }) { index, msg ->
                    val prevMsg = reversedMessages.getOrNull(index + 1)
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        val hideAvatar = !msg.isUser && prevMsg != null && !prevMsg.isUser
                        if (msg.isUser) {
                            UserBubble(
                                text               = msg.text,
                                imageUri           = msg.imageUri,
                                voiceRecordingPath = msg.voiceRecordingPath,
                                voiceDurationMs    = msg.voiceDurationMs,
                                onEdit             = { onEditMessage(msg.text) },
                                onDelete           = { onDeleteMessage(msg) }
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
                                executionSource = msg.executionSource,
                                onFeedback      = { liked -> onFeedback(msg.uid, liked) },
                                onExportPdf     = onExportPdf,
                                onExportMarkdown = onExportMarkdown,
                                initialFeedback = msg.feedback
                            )
                        }
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

@Composable
private fun FinalAnswerVerificationBadge(state: FinalAnswerUiState) {
    var expanded by rememberSaveable(state.verified, state.issueCount) { mutableStateOf(false) }
    val positive = state.verified
    val title = if (positive) "تمت مراجعة الإجابة" else "تحتاج الإجابة إلى مراجعة إضافية"
    val subtitle = if (positive) {
        "فحص سريع للاكتمال قبل العرض"
    } else {
        "قد توجد نقاط غير مكتملة؛ يمكنك مراجعة التفاصيل"
    }
    val tint = if (positive) Color(0xFF7AD9A1) else Color(0xFFFFC266)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.08f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (positive) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = AiriTheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = "عرض التفاصيل",
                tint = AiriTheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 24.dp, top = 6.dp)) {
                if (state.details.isEmpty()) {
                    Text(
                        "لا توجد ملاحظات إضافية.",
                        color = AiriTheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                } else {
                    state.details.forEach { detail ->
                        Text(
                            "• ${detail.take(160)}",
                            color = AiriTheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
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

    var showContextMenu by remember { mutableStateOf(false) }
    var isSelectingText by remember { mutableStateOf(false) }
    val bubbleGesture = if (isSelectingText) {
        Modifier
    } else {
        Modifier.combinedClickable(
            onClick = { },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showContextMenu = true
            }
        )
    }

    AnimatedVisibility(
        visibleState = transition,
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.FAST)) +
                slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.NORMAL)) { it / 5 }
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Box {
                Box {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 640.dp)
                            .then(
                                when (ChatPresentationPolicy.classifyLength(displayText)) {
                                    ChatPresentationPolicy.MessageLength.SHORT,
                                    ChatPresentationPolicy.MessageLength.MEDIUM -> Modifier.wrapContentWidth(Alignment.End)
                                    ChatPresentationPolicy.MessageLength.LONG,
                                    ChatPresentationPolicy.MessageLength.VERY_LONG ->
                                        Modifier.fillMaxWidth(ChatPresentationPolicy.userBubbleFraction(displayText))
                                }
                            )
                            .clip(AIRIShapes.userBubble)
                            .background(AiriTheme.surfaceVariant)
                            .then(bubbleGesture)
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
                        CompositionLocalProvider(LocalLayoutDirection provides chatTextDirection(displayText)) {
                            if (isSelectingText) {
                                SelectionContainer {
                                    BidiAwareMarkdownRenderer(
                                        text = displayText,
                                        textColor = AiriTheme.onSurface
                                    )
                                }
                            } else {
                                BidiAwareMarkdownRenderer(
                                    text = displayText,
                                    textColor = AiriTheme.onSurface
                                )
                            }
                        }
                    }
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
                        onClick      = {
                            showContextMenu = false
                            isSelectingText = true
                        }
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
    executionSource: String? = null,
    
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
    var isSelectingText by remember { mutableStateOf(false) }
    var showFullscreenViewer by remember { mutableStateOf(false) }
    val hasRichContent = remember(text) { ChatRichContentPolicy.needsFullscreen(text) }
    val textDirection = remember(text) { chatTextDirection(text) }
    val responseGesture = if (isSelectingText) {
        Modifier
    } else {
        Modifier.combinedClickable(
            onClick = { },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showContextMenu = true
            }
        )
    }

    val transition = remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = transition,
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(240)) +
                slideInVertically(animationSpec = androidx.compose.animation.core.tween(240)) { it / 5 }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!hideAvatar) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = "AIRI",
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.width(36.dp))
            }

            Column(modifier = Modifier.fillMaxWidth().widthIn(max = 800.dp)) {
                Box {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(responseGesture)
                            .padding(horizontal = 2.dp, vertical = 2.dp)
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides textDirection) {
                            if (isSelectingText) {
                                SelectionContainer { BidiAwareMarkdownRenderer(text = text, modifier = Modifier.fillMaxWidth()) }
                            } else {
                                BidiAwareMarkdownRenderer(text = text, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = showContextMenu,
                        onDismissRequest = { showContextMenu = false },
                        modifier = Modifier.background(AiriTheme.surfaceVariant)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy), color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, tint = AiriTheme.onBackground.copy(0.7f), modifier = Modifier.size(16.dp)) },
                            onClick = {
                                showContextMenu = false
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AIRI", text))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.select_text), color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.TextFields, null, tint = AiriTheme.onBackground.copy(0.7f), modifier = Modifier.size(16.dp)) },
                            onClick = {
                                showContextMenu = false
                                isSelectingText = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share), color = AiriTheme.onBackground) },
                            leadingIcon = { Icon(Icons.Outlined.Share, null, tint = AiriTheme.onBackground.copy(0.7f), modifier = Modifier.size(16.dp)) },
                            onClick = { showContextMenu = false; onShare(text) }
                        )
                        if (hasRichContent) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.expand), color = AiriTheme.onBackground) },
                                leadingIcon = { Icon(Icons.Outlined.OpenInFull, null, tint = AiriTheme.onBackground.copy(0.7f), modifier = Modifier.size(16.dp)) },
                                onClick = { showContextMenu = false; showFullscreenViewer = true }
                            )
                        }
                        Divider(color = AiriTheme.onBackground.copy(alpha = 0.08f))
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
                    IconButton(onClick = { onSpeak(text) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = stringResource(R.string.speak_to_airi), tint = AiriTheme.outline, modifier = Modifier.size(20.dp))
                    }
                    // More actions
                    IconButton(onClick = { showContextMenu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.more_options), tint = AiriTheme.outline, modifier = Modifier.size(20.dp))
                    }
                    // Copy
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AIRI", text))
                    }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy), tint = AiriTheme.outline, modifier = Modifier.size(20.dp))
                    }
                    if (hasRichContent) {
                        IconButton(onClick = { showFullscreenViewer = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.OpenInFull, contentDescription = stringResource(R.string.expand), tint = AiriTheme.outline, modifier = Modifier.size(20.dp))
                        }
                    }
                    // Persisted thumbs up/down — initialized from DB feedback column.
                    var liked    by remember { mutableStateOf(initialFeedback == 1) }
                    var disliked by remember { mutableStateOf(initialFeedback == -1) }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val newDisliked = !disliked
                        disliked = newDisliked; if (newDisliked) liked = false
                        onFeedback(false)
                    }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.ThumbDown, contentDescription = stringResource(R.string.feedback_not_helpful),
                            tint = if (disliked) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val newLiked = !liked
                        liked = newLiked; if (newLiked) disliked = false
                        onFeedback(true)
                    }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.ThumbUp, contentDescription = stringResource(R.string.feedback_helpful),
                            tint = if (liked) CosmicAccent else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(20.dp))
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
                                    text = " ${agentTag ?: "Agent"} · ${trace.stepCount} ${if (traceExpanded) "▲" else "▼"}",
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
                        Text(" $agentTag", color = CosmicAccent.copy(0.85f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (execOrigin.isVisible) {
                    Spacer(Modifier.height(3.dp))
                    ExecOriginBadge(origin = execOrigin, source = executionSource)
                }
            }
        }
    }
    if (showFullscreenViewer) {
        FullscreenResponseViewer(
            text = text,
            onDismiss = { showFullscreenViewer = false },
            onShare = { onShare(text); showFullscreenViewer = false }
        )
    }
}

@Composable
private fun FullscreenResponseViewer(
    text: String,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = AIRIShapes.lg,
            color = AiriTheme.background,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.expand),
                        color = AiriTheme.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AIRI", text))
                        }) {
                            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy), tint = AiriTheme.onBackground)
                        }
                        IconButton(onClick = onShare) {
                            Icon(Icons.Outlined.Share, stringResource(R.string.share), tint = AiriTheme.onBackground)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.close), tint = AiriTheme.onBackground)
                        }
                    }
                }
                Divider(color = AiriTheme.outline.copy(alpha = 0.22f))
                SelectionContainer {
                    CompositionLocalProvider(LocalLayoutDirection provides chatTextDirection(text)) {
                        BidiAwareMarkdownRenderer(
                            text = text,
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)
                        )
                    }
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
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = "AIRI",
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 1.dp)) {
            if (isThinkingStage) {
                AiriThinkingDots()
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    CompositionLocalProvider(LocalLayoutDirection provides chatTextDirection(text)) {
                        BidiAwareMarkdownRenderer(text = text, modifier = Modifier.fillMaxWidth(), isStreaming = true)
                    }
                    BlinkingCursor()
                }
            }
        }
    }
}

@Composable
private fun AiriThinkingDots() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "airi_thinking")
    Row(
        modifier = Modifier.semantics { contentDescription = "AIRI is generating a response" }.padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.28f, targetValue = 0.95f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(520, delayMillis = index * 150),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ), label = "thinking_dot_$index"
            )
            Box(modifier = Modifier.size(7.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(CosmicAccent))
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: com.airi.assistant.domain.ChatAttachment,
    isRemovalEnabled: Boolean = true,
    onRemove: () -> Unit,
) {
    val accent = CosmicAccent
    val context = LocalContext.current
    val typeLabel = when (attachment.contentType) {
        AttachmentPolicy.ContentType.IMAGE -> stringResource(R.string.attachment_type_image)
        AttachmentPolicy.ContentType.VIDEO -> stringResource(R.string.attachment_type_video)
        AttachmentPolicy.ContentType.TEXT -> stringResource(R.string.attachment_type_text)
        AttachmentPolicy.ContentType.DOCUMENT -> stringResource(R.string.attachment_type_document)
        AttachmentPolicy.ContentType.FILE -> stringResource(R.string.attachment_type_file)
    }
    val extension = attachment.safeDisplayName.substringAfterLast('.', "")
        .takeIf { it.isNotBlank() }
        ?.let { ".${it.uppercase()}" }
    val subtitle = listOfNotNull(typeLabel, extension, attachment.displaySize).joinToString(" • ")
    Box(
        modifier = Modifier
            .width(252.dp)
            .height(72.dp)
            .shadow(3.dp, AIRIShapes.lg, ambientColor = accent.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.24f))
            .clip(AIRIShapes.lg)
            .background(AiriTheme.surfaceVariant.copy(alpha = 0.96f))
            .border(1.dp, accent.copy(alpha = 0.30f), AIRIShapes.lg)
            .padding(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(AIRIShapes.md)
                    .background(accent.copy(alpha = 0.16f))
                    .border(1.dp, accent.copy(alpha = 0.22f), AIRIShapes.md),
                contentAlignment = Alignment.Center
            ) {
                val fallback = when (attachment.contentType) {
                    AttachmentPolicy.ContentType.IMAGE -> Icons.Default.Image
                    AttachmentPolicy.ContentType.VIDEO -> Icons.Outlined.Videocam
                    AttachmentPolicy.ContentType.TEXT -> Icons.Outlined.Description
                    AttachmentPolicy.ContentType.DOCUMENT -> Icons.Outlined.Article
                    AttachmentPolicy.ContentType.FILE -> Icons.Default.AttachFile
                }
                Icon(fallback, contentDescription = typeLabel, tint = accent, modifier = Modifier.size(22.dp))
                val videoThumbnail by produceState<Bitmap?>(null, attachment.uri, attachment.contentType) {
                    val videoUri = attachment.uri
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        attachment.contentType == AttachmentPolicy.ContentType.VIDEO &&
                        videoUri != null
                    ) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.loadThumbnail(
                                    videoUri,
                                    AndroidSize(112, 112),
                                    CancellationSignal()
                                )
                            }.getOrNull()
                        }
                    }
                }
                if (videoThumbnail != null) {
                    Image(
                        bitmap = videoThumbnail!!.asImageBitmap(),
                        contentDescription = attachment.safeDisplayName,
                        modifier = Modifier.matchParentSize().clip(AIRIShapes.md),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }
                val thumbModel: Any? = attachment.uri ?: attachment.bitmap
                if (attachment.isVisualImage && thumbModel != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(thumbModel).crossfade(true).build(),
                        contentDescription = attachment.safeDisplayName,
                        modifier = Modifier.matchParentSize().clip(AIRIShapes.md),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = attachment.safeDisplayName,
                    color = AiriTheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = AiriTheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(
            onClick = onRemove,
            enabled = isRemovalEnabled,
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(24.dp)
                .background(AiriTheme.surface.copy(alpha = 0.92f), CircleShape),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.remove),
                tint = AiriTheme.onBackground.copy(if (isRemovalEnabled) 0.7f else 0.3f),
                modifier = Modifier.size(14.dp),
            )
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
    isDispatchingAttachment: Boolean = false,
    voiceInput: String,
    voicePartial: String = "",
    voiceState: VoiceSessionState = VoiceSessionState.IDLE,
    isVadInterrupting: Boolean = false,
    smartReplies: List<String> = emptyList(),
    onSend: (String, () -> Unit) -> Unit,
    onCancel: () -> Unit = {},
    onSmartReply: (String) -> Unit = {},
    onPickImage: () -> Unit = {},
    onPickVideo: () -> Unit = {},
    onPickText: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onMicClick: () -> Unit,
    onVoiceChatClick: () -> Unit,
    onVoiceConsumed: () -> Unit,
    onOpenModels: () -> Unit,
    onNavigate: (String) -> Unit = {},
    // : called when user converts large prompt to attached file
    onStageFile: (android.net.Uri) -> Unit = {},
    draftText: String = "",
    onDraftTextChanged: (String) -> Unit = {},
    // When non-null, pre-fills the text field
    externalInputText: String? = null,
    onExternalInputConsumed: () -> Unit = {},
    onUserStartedTyping: () -> Unit = {},
    
    onFocusChanged: (Boolean) -> Unit = {},
    skillSuggestions: List<ChatInputSuggestion> = emptyList(),
    knowledgeSuggestions: List<ChatInputSuggestion> = emptyList(),
    onSkillQueryChanged: (String) -> Unit = {},
    onKnowledgeQueryChanged: (String) -> Unit = {},

    attachments: List<ChatAttachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    imageInputEnabled: Boolean = true
) {
    val context          = LocalContext.current
    var showAttachPopup by remember { mutableStateOf(false) }
    // Keep collapsed and expanded states available; the content remains
    // scrollable so every attachment shortcut is reachable on small screens.
    val attachSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var text by rememberSaveable { mutableStateOf(draftText) }
    LaunchedEffect(draftText) {
        if (text != draftText) text = draftText
    }
    // The chat composer is intentionally compact; full-screen editing remains
    // available for long text without permanently consuming the chat viewport.
    var showFullScreenEditor by rememberSaveable { mutableStateOf(false) }
    val isInferenceReady = modelState.isModelReady || modelState.isCloudReady
    val isInteractionLocked = isGenerating || isDispatchingAttachment
    val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && isInferenceReady && !modelState.isModelLoading && !isInteractionLocked
    val isTyping = text.isNotBlank()
    val shortcutInput = text.trimStart()
    val showingSkillShortcuts = shortcutInput.startsWith("/")
    val showingKnowledgeShortcuts = shortcutInput.startsWith("@")
    val activeSuggestions = when {
        showingSkillShortcuts -> skillSuggestions
        showingKnowledgeShortcuts -> knowledgeSuggestions
        else -> emptyList()
    }

    // Large prompt conversion is handled on send by the ViewModel. Never create a
    // file from a LaunchedEffect while the user is still typing or pasting.
    val showWarningBanner = false
    val showLimitBottomSheet = false
    var hasDismissedBottomSheet by remember(text.length < 3000) { mutableStateOf(false) }

    val limitSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Apply external pre-fill (e.g. from Edit bubble action)
    LaunchedEffect(externalInputText) {
        val prefill = externalInputText
        if (prefill != null) {
            onDraftTextChanged(prefill)
            onExternalInputConsumed()
        }
    }
    val showSend = isTyping || attachments.isNotEmpty() || isInteractionLocked

    if (showFullScreenEditor) {
        Dialog(
            onDismissRequest = { showFullScreenEditor = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().imePadding(),
                color = AiriTheme.background
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.chat_assign_task_hint),
                            color = AiriTheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                        TextButton(onClick = { showFullScreenEditor = false }) {
                            Text(stringResource(android.R.string.ok), color = CosmicAccent)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            onDraftTextChanged(it)
                        },
                        enabled = isInferenceReady && !isInteractionLocked,
                        modifier = Modifier.fillMaxSize(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = AiriTheme.onBackground,
                            fontSize = 17.sp,
                            lineHeight = 25.sp,
                            textAlign = TextAlign.Start
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(CosmicAccent),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (text.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.chat_assign_task_hint),
                                        color = AiriTheme.onBackground.copy(0.35f),
                                        fontSize = 17.sp
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
            }
        }
    }

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
            onDraftTextChanged(
                listOf(text, voiceInput).filter { it.isNotBlank() }.joinToString(" ")
            )
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
                            onDraftTextChanged("")
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, color = waveColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    if (voiceState == VoiceSessionState.LISTENING && voicePartial.isNotBlank()) {
                        Text(
                            text = voicePartial,
                            color = AiriTheme.onBackground.copy(alpha = 0.72f),
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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
                .border(0.5.dp, AiriTheme.outline.copy(alpha = 0.6f), AIRIShapes.xl)
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
                            isRemovalEnabled = !isDispatchingAttachment,
                            onRemove   = { onRemoveAttachment(attachment.uid) },
                        )
                    }
                }
                Divider(color = AiriTheme.outline, thickness = 0.5.dp)
            }
            AnimatedVisibility(
                visible = activeSuggestions.isNotEmpty() && !isGenerating,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .clip(AIRIShapes.md)
                        .background(AiriTheme.surfaceVariant)
                ) {
                    activeSuggestions.forEach { suggestion ->
                        val suggestionCategory = stringResource(
                            if (suggestion.isKnowledge) R.string.input_saved_knowledge else R.string.skill_title
                        )
                        val suggestionDescription = listOf(
                            suggestionCategory,
                            suggestion.title,
                            suggestion.subtitle.takeIf { it.isNotBlank() }
                        ).filterNotNull().joinToString(", ")
                        Surface(
                            onClick = {
                                onDraftTextChanged(
                                    com.airi.assistant.ui.composer.ComposerDirectivePolicy.applySelection(
                                        currentText = text,
                                        directiveId = suggestion.id,
                                        isKnowledge = suggestion.isKnowledge,
                                    )
                                )
                                onSkillQueryChanged("")
                                onKnowledgeQueryChanged("")
                            },
                            color = Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics(mergeDescendants = true) {
                                    contentDescription = suggestionDescription
                                    role = Role.Button
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (suggestion.isKnowledge) Icons.Outlined.Lightbulb else Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = if (suggestion.isKnowledge) Color(0xFFFFB347) else CosmicAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        suggestion.title,
                                        color = AiriTheme.onSurface,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        if (suggestion.subtitle.isBlank() && suggestion.isKnowledge) {
                                            stringResource(R.string.input_saved_knowledge)
                                        } else {
                                            suggestion.subtitle
                                        },
                                        color = AiriTheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 0.dp),
                horizontalArrangement = Arrangement.End
            ) {
                val fullScreenEditorDescription = stringResource(R.string.expand)
                if (text.lineSequence().count() >= 5) {
                    IconButton(
                        onClick = { showFullScreenEditor = true },
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = fullScreenEditorDescription
                            role = Role.Button
                        }
                    ) {
                        Icon(
                            Icons.Outlined.Fullscreen,
                            contentDescription = null,
                            tint = AiriTheme.onBackground.copy(0.45f),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { newValue ->
                        if (text.isEmpty() && newValue.isNotEmpty()) onUserStartedTyping()
                        text = newValue
                        onDraftTextChanged(newValue)
                        val query = newValue.trimStart()
                        when {
                            query.startsWith("/skill:") || query.startsWith("@knowledge:") -> {
                                onSkillQueryChanged("")
                                onKnowledgeQueryChanged("")
                            }
                            query.startsWith("/") -> {
                                onSkillQueryChanged(query.drop(1).takeWhile { !it.isWhitespace() })
                            }
                            query.startsWith("@") -> {
                                onKnowledgeQueryChanged(query.drop(1).takeWhile { !it.isWhitespace() })
                            }
                            else -> {
                                onSkillQueryChanged("")
                                onKnowledgeQueryChanged("")
                            }
                        }
                    },
                    enabled = isInferenceReady && !isInteractionLocked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 26.dp, max = 72.dp)
                        .onFocusChanged { state ->
                            // Propagate focus change upward so toolbar collapses
                            onFocusChanged(state.isFocused)
                        },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = AiriTheme.onBackground, fontSize = 15.sp,
                        textAlign = TextAlign.Start
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(CosmicAccent),
                    maxLines = 3,
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = when {
                                        isGenerating              -> stringResource(R.string.generating)
                                        isDispatchingAttachment   -> stringResource(R.string.attachment_preparing)
                                        modelState.isModelLoading -> stringResource(R.string.model_is_loading)
                                        else                      -> stringResource(R.string.chat_assign_task_hint)
                                    },
                                    color = AiriTheme.onSurfaceVariant.copy(0.82f),
                                    fontSize = 15.sp,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start
                                )
                            }
                            inner()
                        }
                    }
                )
            }
            val attachmentDescription = stringResource(R.string.attach)
            val voiceInputDescription = stringResource(R.string.voice_input)
            val connectorsDescription = stringResource(R.string.connectors_title)
            val mainActionDescription = stringResource(if (showSend) R.string.send else R.string.voice_input)
            val mainScale = animateFloatAsState(
                targetValue = if (showSend || isGenerating || isDispatchingAttachment) 1f else 0.95f,
                label = "composer_action_scale"
            ).value
            CompositionLocalProvider(LocalLayoutDirection provides LocalLayoutDirection.current) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Keep the two action pairs anchored to opposite edges. The pair
                // containing connectors and send/live stays on the reading side,
                // while attachment and microphone stay on the opposite side.
                // The pair order mirrors with the app language, without changing
                // the action semantics or any runtime/security configuration.
                val utilities = @Composable {
                    Row(
                        modifier = Modifier.semantics { contentDescription = "Composer utilities" },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .shadow(4.dp, CircleShape, ambientColor = CosmicAccent.copy(alpha = 0.28f), spotColor = CosmicAccent.copy(alpha = 0.24f))
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(CosmicAccent.copy(alpha = 0.24f), AiriTheme.surface)))
                                .border(1.dp, CosmicAccent.copy(alpha = 0.42f), CircleShape)
                                .semantics {
                                    contentDescription = attachmentDescription
                                    role = Role.Button
                                }
                                .clickable(enabled = !isInteractionLocked) { showAttachPopup = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Add,
                                attachmentDescription,
                                tint = AiriTheme.onBackground.copy(if (!isInteractionLocked) 0.7f else 0.3f),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        AnimatedVisibility(visible = !isGenerating, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                            Box(
                                modifier = Modifier.size(44.dp).clip(CircleShape)
                                    .semantics {
                                        contentDescription = voiceInputDescription
                                        role = Role.Button
                                    }
                                    .clickable(enabled = isInferenceReady) { onMicClick() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (voiceState != VoiceSessionState.IDLE) {
                                    Box(modifier = Modifier.size((28 * micPulse.value).dp).clip(CircleShape).background(CosmicAccent.copy(0.18f)))
                                }
                                Icon(Icons.Outlined.Mic, voiceInputDescription,
                                    tint = when (voiceState) {
                                        VoiceSessionState.IDLE -> if (isInferenceReady) Color.White.copy(0.70f) else Color.White.copy(0.30f)
                                        else -> CosmicAccent
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                val actions = @Composable {
                    Row(
                        modifier = Modifier.semantics { contentDescription = "Composer actions" },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isGenerating) {
                            Box(
                                modifier = Modifier
                                    .clip(AIRIShapes.xl)
                                    .background(AiriTheme.surfaceVariant)
                                    .border(1.dp, AiriTheme.outline, AIRIShapes.xl)
                                    .semantics {
                                        contentDescription = connectorsDescription
                                        role = Role.Button
                                    }
                                    .clickable { onNavigate(AiriRoute.CONNECTORS) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Outlined.Hub, null, tint = CosmicAccent, modifier = Modifier.size(14.dp))
                                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = AiriTheme.onBackground.copy(0.45f), modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                            Box(
                                modifier = Modifier.size(44.dp).graphicsLayer { scaleX = mainScale; scaleY = mainScale }
                                .shadow(if (isInferenceReady) 12.dp else 0.dp, CircleShape, ambientColor = CosmicAccent.copy(0.5f), spotColor = CosmicAccent.copy(0.6f))
                                .clip(CircleShape)
                                .background(when {
                                    isGenerating -> Color(0xFFFF6B6B)
                                    isDispatchingAttachment -> CosmicAccent
                                    isInferenceReady || showSend -> CosmicAccent
                                    else -> CosmicAccent.copy(0.30f)
                                })
                                .semantics { contentDescription = mainActionDescription; role = Role.Button }
                                .clickable(enabled = isInferenceReady || isInteractionLocked) {
                                    when {
                                        isGenerating -> onCancel()
                                        showSend && canSend -> {
                                            onSend(text) {
                                                text = ""
                                                onDraftTextChanged("")
                                            }
                                        }
                                        !showSend -> onVoiceChatClick()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedContent(
                                targetState = when {
                                    isGenerating -> "stop"
                                    isDispatchingAttachment -> "preparing"
                                    showSend -> "send"
                                    else -> "live"
                                },
                                transitionSpec = {
                                    (fadeIn(animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.FAST)) + scaleIn(initialScale = 0.7f)) togetherWith
                                    (fadeOut(animationSpec = androidx.compose.animation.core.tween(AIRIAnimations.FAST)) + scaleOut(targetScale = 0.7f))
                                },
                                label = "main_btn"
                            ) { state ->
                                when (state) {
                                    "stop" -> Icon(Icons.Default.Stop, mainActionDescription, tint = AiriTheme.onBackground, modifier = Modifier.size(20.dp))
                                    "preparing" -> CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AiriTheme.onBackground, strokeWidth = 2.dp)
                                    "send" -> Icon(Icons.Default.ArrowUpward, mainActionDescription, tint = AiriTheme.onBackground, modifier = Modifier.size(20.dp))
                                    else -> Icon(Icons.Default.GraphicEq, mainActionDescription, tint = AiriTheme.onBackground, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
                utilities()
                actions()
            }
            }
        }

    }
    if (showAttachPopup) {
        ModalBottomSheet(
            onDismissRequest = { showAttachPopup = false },
            sheetState = attachSheetState,
            containerColor = AiriTheme.surface,
            contentColor = AiriTheme.onSurface,
            tonalElevation = 8.dp,
            scrimColor = Color.Black.copy(alpha = if (AiriTheme.onBackground == Color.White) 0.62f else 0.28f),
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
            // Material3 animates the sheet itself; this animates its content as it appears.
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(animationSpec = tween(220)) +
                    slideInVertically(
                        initialOffsetY = { fullHeight -> fullHeight / 12 },
                        animationSpec = tween(260, easing = FastOutSlowInEasing)
                    ),
                exit = fadeOut(animationSpec = tween(120)) +
                    shrinkVertically(animationSpec = tween(160))
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                Text(
                    text = stringResource(R.string.attach_section_media),
                    color = AiriTheme.onSurfaceVariant,
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
                        label = if (imageInputEnabled) stringResource(R.string.attach_image) else stringResource(R.string.attachment_capability_unavailable),
                        enabled = imageInputEnabled,
                        modifier = Modifier.weight(1f)
                    ) { showAttachPopup = false; onPickImage() }
                    AttachCard(
                        icon = Icons.Outlined.CameraAlt,
                        label = stringResource(R.string.attach_camera),
                        enabled = imageInputEnabled,
                        modifier = Modifier.weight(1f)
                    ) { showAttachPopup = false; onTakePhoto() }
                    AttachCard(
                        icon = Icons.Outlined.Videocam,
                        label = stringResource(R.string.attach_video),
                        modifier = Modifier.weight(1f)
                    ) { showAttachPopup = false; onPickVideo() }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AttachCard(
                        icon = Icons.Outlined.Description,
                        label = stringResource(R.string.attach_text),
                        modifier = Modifier.weight(1f)
                    ) { showAttachPopup = false; onPickText() }
                    AttachCard(
                        icon = Icons.Outlined.AttachFile,
                        label = stringResource(R.string.attach_files),
                        modifier = Modifier.weight(1f)
                    ) { showAttachPopup = false; onPickFile() }
                    Spacer(modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.attach_section_actions),
                    color = AiriTheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                AttachListRow(Icons.Outlined.Description, stringResource(R.string.attach_recent_files)) { showAttachPopup = false; onPickFile() }
                AttachListRow(Icons.Outlined.EventNote, stringResource(R.string.attach_recent_tasks)) { showAttachPopup = false; onNavigate(AiriRoute.AGENT_TASKS) }
                AttachListRow(Icons.Outlined.StarBorder, stringResource(R.string.attach_skills)) { showAttachPopup = false; onNavigate(AiriRoute.SKILL_MANAGER) }
                AttachListRow(Icons.Outlined.EditNote, stringResource(R.string.prompt_builder_shortcut)) { showAttachPopup = false; onNavigate(AiriRoute.PROMPT_BUILDER) }
                AttachListRow(Icons.Outlined.Assignment, stringResource(R.string.attach_plan)) { showAttachPopup = false; onNavigate(AiriRoute.PLANNING_DASHBOARD) }
                AttachListRow(Icons.Outlined.Slideshow, stringResource(R.string.attach_slides)) { showAttachPopup = false; onDraftTextChanged(context.getString(R.string.attach_slides) + ": ") }
                AttachListRow(Icons.Outlined.Language, stringResource(R.string.attach_website)) { showAttachPopup = false; onDraftTextChanged(context.getString(R.string.attach_website) + ": ") }
                AttachListRow(Icons.Outlined.PhoneAndroid, stringResource(R.string.attach_app)) { showAttachPopup = false; onDraftTextChanged(context.getString(R.string.attach_app) + ": ") }
                AttachListRow(
                    icon = Icons.Outlined.Storage,
                    label = stringResource(R.string.attach_spreadsheet)
                ) {
                    showAttachPopup = false
                    onDraftTextChanged(
                        text + if (text.isBlank()) {
                            context.getString(R.string.chat_create_spreadsheet_prefix)
                        } else {
                            "\n${context.getString(R.string.chat_create_spreadsheet_prefix)}"
                        }
                    )
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
                    onDraftTextChanged(
                        text + if (text.isBlank()) {
                            context.getString(R.string.chat_edit_image_prefix)
                        } else {
                            "\n${context.getString(R.string.chat_edit_image_prefix)}"
                        }
                    )
                    onPickImage()
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeActionSheet(
    onDismiss: () -> Unit,
    onChat: () -> Unit,
    onShareScreen: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AiriTheme.surface,
        contentColor = AiriTheme.onSurface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "مرحباً، أنا Airi",
                style = MaterialTheme.typography.headlineSmall,
                color = AiriTheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End
            )
            Text(
                text = "كيف تريدين أن أساعدك؟",
                color = AiriTheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End
            )
            Button(
                onClick = onChat,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = AiriTheme.background)
            ) {
                Icon(Icons.Outlined.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("الدردشة مع Airi")
            }
            OutlinedButton(onClick = onShareScreen, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.ScreenShare, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("مشاركة الشاشة مع Airi")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun AttachCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(AIRIShapes.md)
            .background(AiriTheme.surfaceVariant)
            .border(1.dp, AiriTheme.outline.copy(alpha = 0.9f), AIRIShapes.md)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) CosmicAccent else AiriTheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        Text(
            text = label,
            color = if (enabled) AiriTheme.onSurface else AiriTheme.onSurfaceVariant,
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
                .background(AiriTheme.surfaceVariant)
                .border(1.dp, AiriTheme.outline.copy(alpha = 0.9f), AIRIShapes.md),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = CosmicAccent, modifier = Modifier.size(20.dp))
        }
        Text(
            text = label,
            color = AiriTheme.onSurface,
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
        titleContentColor = AiriTheme.onSurface, textContentColor = AiriTheme.onSurface,
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
        drawerContainerColor = AiriTheme.surface,
        drawerContentColor   = AiriTheme.onSurface,
        modifier = Modifier
            .width(300.dp)
            .clip(RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp))
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
                    .background(Brush.verticalGradient(listOf(Color.Transparent, AiriTheme.surface)))
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
    val shareText = response.trim()
    if (shareText.isBlank()) return
    AnalyticsService.shareableOutputShared("android_share")
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareText)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chat)))
}

private fun shareChatTranscript(context: android.content.Context, messages: List<ChatMessage>) {
    val transcript = messages
        .takeLast(100)
        .joinToString(separator = "\n\n") { message ->
            val speaker = if (message.isUser) "You" else "AIRI"
            "$speaker:\n${message.text.trim()}"
        }
        .take(250_000)
        .trim()
    if (transcript.isBlank()) return
    AnalyticsService.shareableOutputShared("android_chat_share")
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, transcript)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chat)))
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
private fun DrawerActionItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = AiriTheme.onBackground.copy(0.7f), onClick: () -> Unit) {
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
        containerColor = AiriTheme.surface, titleContentColor = AiriTheme.onSurface, textContentColor = AiriTheme.onSurface,
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
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent, unfocusedBorderColor = AiriTheme.outline, focusedTextColor = AiriTheme.onSurface, unfocusedTextColor = AiriTheme.onBackground)
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
    val scrollToBottomDescription = stringResource(R.string.cd_scroll_to_bottom)
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
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(CosmicAccentAlt, CosmicAccent)
                    )
                )
                .shadow(8.dp, CircleShape, ambientColor = CosmicAccent, spotColor = CosmicAccent)
                .semantics {
                    contentDescription = scrollToBottomDescription
                    role = Role.Button
                }
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.KeyboardArrowDown, null, tint = AiriTheme.onBackground, modifier = Modifier.size(20.dp))
        }
    }
}

private fun android.content.Context.userFacingVoiceError(error: String): String {
    val platformCode = error.removePrefix("stt_platform_error_").toIntOrNull()
    val resourceId = when {
        error == "stt_unavailable" -> R.string.voice_error_unavailable
        error == "vosk_model_load_failed" -> R.string.voice_error_model_load
        error == "stt_empty_result" -> R.string.voice_error_empty_result
        platformCode in setOf(
            android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            android.speech.SpeechRecognizer.ERROR_NETWORK,
            android.speech.SpeechRecognizer.ERROR_SERVER
        ) -> R.string.voice_error_network
        platformCode in setOf(
            android.speech.SpeechRecognizer.ERROR_AUDIO,
            android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        ) -> R.string.voice_error_microphone
        platformCode == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_error_busy
        platformCode in setOf(
            android.speech.SpeechRecognizer.ERROR_NO_MATCH,
            android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        ) -> R.string.voice_error_empty_result
        else -> R.string.voice_error_generic
    }
    return getString(resourceId)
}
