package com.airi.assistant.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.airi.assistant.util.ChatExporter
import com.airi.assistant.ui.theme.InputBarBackground
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.AgentMode
import com.airi.assistant.ui.viewmodel.ChatMessage
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.ui.viewmodel.ModelUiState
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

enum class VoiceSessionState { IDLE, LISTENING, PROCESSING, SPEAKING }

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
    val smartReplies  by viewModel.smartReplies.collectAsState()
    val snackbarHost  = remember { SnackbarHostState() }
    val paywallTrigger        by viewModel.paywallTrigger.collectAsState()
    val upgradePrompt         by viewModel.upgradePrompt.collectAsState()
    val systemIntegrityFailed by viewModel.systemIntegrityFailed.collectAsState()

    // Navigate to paywall when daily limit is reached
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

    // Re-engagement banner: show once if user returns after inactivity
    LaunchedEffect(Unit) {
        if (RetentionManager.shouldShowReEngagement()) {
            snackbarHost.showSnackbar(
                message        = RetentionManager.getReEngagementMessage(),
                duration       = SnackbarDuration.Short
            )
        }
    }

    var showMenu            by remember { mutableStateOf(false) }
    var showGenSettings     by remember { mutableStateOf(false) }
    var voiceInput          by remember { mutableStateOf("") }
    var voiceChatInput      by remember { mutableStateOf("") }
    var voiceState            by remember { mutableStateOf(VoiceSessionState.IDLE) }

    LaunchedEffect(voiceState) {
        viewModel.updateVoiceState(voiceState.name)
    }

    val wakeCounter by WakeWordDispatcher.counter

    // ── In-app speech-to-text (Vosk on-device, NO Google APIs) ──────────────
    //
    // This screen used to drive android.speech.SpeechRecognizer, which
    // requires Google's offline voice-search bundle to actually run
    // offline. On devices without that bundle (many OEMs, GMS-less
    // hardware) it either popped a Google dialog or silently failed.
    //
    // We now stream microphone audio straight into a Vosk Recognizer
    // (com.alphacephei:vosk-android) loaded from a model the user
    // downloads via the in-app downloader (VoskModelManager). No
    // RecognizerIntent, no SpeechRecognizer, no network, no Google.
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
            scope.launch {
                snackbarHost.showSnackbar(
                    context.getString(R.string.no_voice_model_installed)
                )
            }
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
                onPartial = { /* future: surface live partial text */ },
                onFinal   = { spoken ->
                    Log.d("AIRI_VOICE", "Vosk STT result len=${spoken.length} autoSend=$autoSend")
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
                onError   = { err ->
                    Log.w("AIRI_VOICE", "Vosk STT error: $err")
                    voskEngineHolder.value?.release()
                    voskEngineHolder.value = null
                    voiceState = VoiceSessionState.IDLE
                    scope.launch {
                        snackbarHost.showSnackbar(
                            context.getString(R.string.speech_recognition_unavailable)
                        )
                    }
                }
            )
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startInAppStt(autoSend = false)
        } else {
            voiceState = VoiceSessionState.IDLE
            val isPermanentlyDenied = context is Activity &&
                !context.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            scope.launch {
                snackbarHost.showSnackbar(
                    if (isPermanentlyDenied)
                        context.getString(R.string.mic_blocked_settings)
                    else
                        context.getString(R.string.microphone_permission_required)
                )
            }
        }
    }

    val voiceChatPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startInAppStt(autoSend = true)
        } else {
            voiceState = VoiceSessionState.IDLE
            scope.launch { snackbarHost.showSnackbar(context.getString(R.string.microphone_permission_required)) }
        }
    }

    // ── Wake-word → in-app dictation bridge ──────────────────────────────────
    // MainActivity's BroadcastReceiver picks up HotwordService's "Hey AIRI"
    // intent and bumps WakeWordDispatcher.counter. We observe the bump here
    // and start a fresh in-app listen turn on the chat input — turning the
    // wake word from "does nothing" into an actual conversational entry point.
    LaunchedEffect(wakeCounter) {
        if (wakeCounter > 0 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED &&
            VoskModelManager.isReady(context) &&
            voiceState == VoiceSessionState.IDLE) {
            Log.d("AIRI_VOICE", "Wake-word dispatcher fired → starting in-app STT (autoSend)")
            startInAppStt(autoSend = true)
        }
    }

    // ── VoiceManager (TTS) ───────────────────────────────────────────────────
    val voiceStateRef = remember { androidx.compose.runtime.mutableStateOf(VoiceSessionState.IDLE) }
    // Issue #2 — continuous live-voice loop. When the user taps the
    // live-chat button, we set this to TRUE; the TTS done-callback then
    // automatically re-arms Vosk so the assistant feels like a real
    // back-and-forth conversation instead of a single-shot voice command.
    // Reset to FALSE when the user explicitly stops, when generation errors,
    // or when ChatScreen leaves the composition.
    val liveChatActiveRef = remember { androidx.compose.runtime.mutableStateOf(false) }
    val voiceLoopRearmTick = remember { androidx.compose.runtime.mutableStateOf(0) }
    val voiceManager = remember {
        VoiceManager(context, object : VoiceManager.VoiceListener {
            override fun onWakeWordDetected() {}
            override fun onSpeechResult(text: String) {}
            override fun onError(error: String) {
                scope.launch { snackbarHost.showSnackbar("Voice error: $error") }
                // Hard-stop the loop on any voice error so we don't spin
                // re-arming Vosk against a broken mic / missing model.
                if (liveChatActiveRef.value) {
                    Log.i("AIRI_PROOF", "VOICE_LOOP_STOPPED reason=error err=$error")
                    liveChatActiveRef.value = false
                }
            }
            override fun onSpeakingStarted() {
                Log.d("AIRI_VOICE", "TTS speaking started → VoiceSessionState.SPEAKING")
                voiceStateRef.value = VoiceSessionState.SPEAKING
            }
            override fun onSpeakingDone() {
                Log.d("AIRI_VOICE", "TTS speaking done → VoiceSessionState.IDLE")
                voiceStateRef.value = VoiceSessionState.IDLE
                // Re-arm STT iff we are in continuous-conversation mode.
                if (liveChatActiveRef.value) {
                    Log.i("AIRI_PROOF", "VOICE_LOOP_REARM_REQUESTED tick=${voiceLoopRearmTick.value + 1}")
                    voiceLoopRearmTick.value = voiceLoopRearmTick.value + 1
                }
            }
        })
    }
    DisposableEffect(Unit) { onDispose { voiceManager.destroy() } }
    // Issue #2 — consume the re-arm tick that the TTS done-callback bumps
    // when liveChatActive is true. We pause briefly so the user can hear
    // the tail of the response settle before the mic starts listening
    // again, otherwise the re-arm feels jarring.
    LaunchedEffect(voiceLoopRearmTick.value) {
        if (voiceLoopRearmTick.value > 0 &&
            liveChatActiveRef.value &&
            modelState.isModelReady &&
            !agentState.isWorking
        ) {
            kotlinx.coroutines.delay(350)
            // Re-check after the delay — the user may have tapped to exit.
            if (liveChatActiveRef.value && !agentState.isWorking) {
                Log.i("AIRI_PROOF", "VOICE_LOOP_REARM_FIRED tick=${voiceLoopRearmTick.value}")
                Log.i("AIRI_PROOF", "VOICE_REARMED tick=${voiceLoopRearmTick.value}")
                startInAppStt(autoSend = true)
            } else {
                Log.i("AIRI_PROOF", "VOICE_LOOP_REARM_ABORTED reason=user_exit_or_busy")
            }
        }
    }
    // Sync the TTS-driven state updates back to the UI state variable
    LaunchedEffect(voiceStateRef.value) {
        val ttsState = voiceStateRef.value
        if (ttsState == VoiceSessionState.SPEAKING || ttsState == VoiceSessionState.IDLE) {
            if (voiceState != VoiceSessionState.LISTENING && voiceState != VoiceSessionState.PROCESSING) {
                voiceState = ttsState
            }
        }
    }
    // Auto-stop: if still LISTENING after 7s with no result, revert to IDLE
    LaunchedEffect(voiceState) {
        if (voiceState == VoiceSessionState.LISTENING) {
            kotlinx.coroutines.delay(7_000L)
            if (voiceState == VoiceSessionState.LISTENING) {
                voiceState = VoiceSessionState.IDLE
                Log.d("AIRI_VOICE", "Auto-stop: 7s silence → IDLE")
            }
        }
    }

    val voicePrefs = remember { context.getSharedPreferences("airi_voice", android.content.Context.MODE_PRIVATE) }
    var speakNextResponse by rememberSaveable { mutableStateOf(false) }
    var lastSpokenMsgId   by rememberSaveable { mutableStateOf(-1L) }

    LaunchedEffect(voiceChatInput) {
        val input = voiceChatInput
        if (input.isNotBlank() && modelState.isModelReady && !agentState.isWorking) {
            Log.d("AIRI_VOICE", "VoiceChat auto-send: '${input.take(60)}' len=${input.length}")
            Log.d("AIRI_UI", "sendMessage triggered by voice input len=${input.length}")
            voiceChatInput = ""
            voiceState = VoiceSessionState.IDLE
            Log.d("AIRI_VOICE", "MicState → IDLE (auto-send dispatched)")
            viewModel.sendMessage(input)
            // Phase 2 — when the continuous live-chat loop is armed, ALWAYS
            // speak the response so the loop can advance via onSpeakingDone
            // and re-arm Vosk for the next turn. Otherwise honour the user's
            // global voice toggle. Without this, enabling live-chat without
            // the voice toggle leaves the loop stuck in PROCESSING forever.
            if (liveChatActiveRef.value || voicePrefs.getBoolean("voice_enabled", false)) {
                speakNextResponse = true
                if (liveChatActiveRef.value) {
                    Log.i("AIRI_PROOF", "VOICE_LOOP_ACTIVE forcing_tts=true reason=continuous_mode")
                }
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
                Log.d("AIRI_VOICE", "TTS triggered → SPEAKING msgId=${lastMsg.id} text_len=${lastMsg.text.length}")
                Log.i("AIRI_PROOF", "VOICE_RESPONSE_COMPLETE msgId=${lastMsg.id} chars=${lastMsg.text.length} loop_active=${liveChatActiveRef.value}")
                voiceManager.speak(lastMsg.text)
            }
        }
    }

    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val fileName = uri.lastPathSegment ?: uri.toString()
            Log.d("AIRI_UI", "File picked: $fileName uri=$uri")
            scope.launch { snackbarHost.showSnackbar(context.getString(R.string.file_selected_name, fileName)) }
        }
    }
    // Issue #1 — selected-image state (held in saveable so config-change
    // doesn't drop the user's pick). The current LLM is text-only; this
    // chip is a visible acknowledgement that we received the file. The
    // chat send-handler appends a "[image attached: <name>]" marker so the
    // assistant can at least respond to the fact something was attached.
    var selectedImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            Log.d("AIRI_UI", "Image picked: $uri")
            // Spec-mandated proof tag (also keep the historical IMAGE_ATTACHED
            // line for back-compat with existing log parsers). The
            // vision_backend field now reflects the *runtime* mmproj state,
            // not a build-time constant — so logcat tells the truth about
            // whether this attachment will hit the real vision pipeline.
            val visionReady = viewModel.isVisionReady()
            Log.i("AIRI_PROOF", "ATTACHMENT_SELECTED type=image name=${uri.lastPathSegment ?: "unknown"} uri=$uri vision_backend=${if (visionReady) "mtmd" else "none"}")
            Log.i("AIRI_PROOF", "IMAGE_ATTACHED uri=$uri vision_ready=$visionReady")
            selectedImageUri = uri
        }
    }
    // Phase 3 — mmproj projector picker. The user picks a *.gguf projector
    // file (typically named ...-mmproj-...gguf) from storage; the VM copies
    // it to cache, calls the serialized native loader, and re-detects
    // ModelCapabilities so the vision badge flips to "ready".
    val mmprojPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            Log.i("AIRI_PROOF", "MMPROJ_PICKED uri=$uri name=${uri.lastPathSegment ?: "unknown"}")
            viewModel.loadMmproj(uri)
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            capturedBitmap = bitmap
            scope.launch { snackbarHost.showSnackbar(context.getString(R.string.photo_captured)) }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraLauncher.launch(null)
    }

    val exportChatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        scope.launch {
            val success = uri != null && ChatExporter.exportToUri(context, uri, messages)
            snackbarHost.showSnackbar(
                if (success) context.getString(R.string.export_success)
                else context.getString(R.string.export_failed)
            )
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AiriDrawer(
                modelState = modelState,
                onNavigate = { route ->
                    scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                    onNavigate(route)
                },
                onNewChat = {
                    viewModel.clearMessages()
                    scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                },
                onLogout = {
                    scope.launch { drawerState.snapTo(DrawerValue.Closed) }
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
                    onLongPressTitle = { onNavigate(AiriRoute.DEBUG_SCREEN) },
                    onExportChat  = {
                        showMenu = false
                        exportChatLauncher.launch(ChatExporter.buildFileName())
                    }
                )
            },
            bottomBar = {
              Column(modifier = Modifier.fillMaxWidth()) {
                // Issue #1 — honest image preview chip. Appears above the
                // input pill when the user has picked an image. Tap × to
                // remove. We do NOT pretend to do vision: the chip text
                // explicitly says "Vision model required".
                androidx.compose.animation.AnimatedVisibility(
                    visible = selectedImageUri != null,
                    enter = androidx.compose.animation.fadeIn() +
                            androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.fadeOut() +
                           androidx.compose.animation.shrinkVertically()
                ) {
                    val uri = selectedImageUri
                    if (uri != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF1A1A1A).copy(alpha = 0.45f))
                                .border(1.dp, CosmicAccent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CosmicAccent.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    tint = CosmicAccent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = uri.lastPathSegment ?: uri.toString().takeLast(28),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.image_attached_no_vision),
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 10.sp,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = {
                                Log.i("AIRI_PROOF", "ATTACHMENT_REMOVED type=image name=${uri.lastPathSegment ?: "unknown"}")
                                Log.i("AIRI_PROOF", "IMAGE_REMOVED")
                                selectedImageUri = null
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.remove_image_cd),
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                ChatInputBar(
                    modelState      = modelState,
                    isGenerating    = agentState.isWorking,
                    voiceInput      = voiceInput,
                    smartReplies    = smartReplies,
                    onSend          = { text ->
                        // Phase 3 — vision pipeline: hand off both the typed
                        // text AND any pending image artefact to the VM. The
                        // VM decides whether to invoke the real vision path
                        // (mmproj loaded + capability detected) or fall back
                        // to the historical "[ATTACHMENT: image: name]" text
                        // marker. Either way, we clear the preview chip
                        // immediately so the user can keep typing.
                        val attachedUri = selectedImageUri
                        val attachedBmp = capturedBitmap
                        if (attachedUri != null || attachedBmp != null) {
                            val name = attachedUri?.lastPathSegment
                                ?: "camera_${System.currentTimeMillis()}"
                            Log.i("AIRI_PROOF",
                                "ATTACHMENT_SENT type=image name=$name " +
                                "vision_ready=${viewModel.isVisionReady()}")
                            viewModel.sendMessageWithImage(text, attachedUri, attachedBmp)
                            selectedImageUri = null
                            capturedBitmap = null
                        } else {
                            viewModel.sendMessage(text)
                        }
                    },
                    onCancel        = { viewModel.cancelGeneration() },
                    onSmartReply    = { reply -> viewModel.clearSmartReplies(); viewModel.sendMessage(reply) },
                    onPickImage     = { imagePicker.launch("image/*") },
                    onPickFile      = { filePicker.launch("*/*") },
                    onPickMmproj    = { mmprojPicker.launch("*/*") },
                    onTakePhoto     = {
                        when {
                            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED ->
                                cameraLauncher.launch(null)
                            else ->
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    voiceState        = voiceState,
                    onMicClick      = mic@{
                        // Interrupt TTS if currently speaking
                        if (voiceState == VoiceSessionState.SPEAKING) {
                            voiceManager.stopSpeaking()
                            voiceStateRef.value = VoiceSessionState.IDLE
                            voiceState = VoiceSessionState.IDLE
                            Log.d("AIRI_VOICE", "TTS interrupted by mic press → IDLE")
                            return@mic
                        }
                        // Tap-while-listening = stop and flush current Vosk session
                        if (voiceState == VoiceSessionState.LISTENING) {
                            stopInAppStt()
                            return@mic
                        }
                        when {
                            !VoskModelManager.isReady(context) -> {
                                scope.launch { snackbarHost.showSnackbar(context.getString(R.string.no_voice_model_installed)) }
                            }
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                                startInAppStt(autoSend = false)
                            }
                            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onVoiceChatClick = vc@{
                        // Interrupt TTS if currently speaking — and EXIT the
                        // continuous loop, because tapping during TTS is the
                        // user's signal that they want out.
                        if (voiceState == VoiceSessionState.SPEAKING) {
                            voiceManager.stopSpeaking()
                            voiceStateRef.value = VoiceSessionState.IDLE
                            voiceState = VoiceSessionState.IDLE
                            // Phase 2 — explicit barge-in proof tag (the user
                            // tapped while TTS was speaking, that's a real
                            // interrupt event, not just a stop).
                            Log.i("AIRI_PROOF", "VOICE_INTERRUPTED reason=tap_during_speaking loop_active=${liveChatActiveRef.value}")
                            if (liveChatActiveRef.value) {
                                liveChatActiveRef.value = false
                                Log.i("AIRI_PROOF", "VOICE_LOOP_STOPPED reason=tap_during_speaking")
                            }
                            Log.d("AIRI_VOICE", "TTS interrupted by voice-chat press → IDLE")
                            return@vc
                        }
                        // Tap while listening → stop AND exit loop
                        if (voiceState == VoiceSessionState.LISTENING) {
                            if (liveChatActiveRef.value) {
                                liveChatActiveRef.value = false
                                Log.i("AIRI_PROOF", "VOICE_LOOP_STOPPED reason=tap_during_listening")
                            }
                            stopInAppStt()
                            return@vc
                        }
                        when {
                            !VoskModelManager.isReady(context) -> {
                                scope.launch { snackbarHost.showSnackbar(context.getString(R.string.no_voice_model_installed)) }
                            }
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                                // Enter continuous loop mode and arm Vosk.
                                liveChatActiveRef.value = true
                                Log.i("AIRI_PROOF", "VOICE_LOOP_STARTED")
                                Log.i("AIRI_PROOF", "VOICE_LOOP_ACTIVE state=listening turn=initial")
                                startInAppStt(autoSend = true)
                            }
                            else -> voiceChatPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onVoiceConsumed = { voiceInput = ""; voiceState = VoiceSessionState.IDLE },
                    onOpenModels    = { onNavigate(AiriRoute.MODELS) }
                )
              } // end of bottomBar Column (image-preview chip + ChatInputBar)
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
                        voiceManager.stopSpeaking()
                        voiceState = VoiceSessionState.SPEAKING
                        voiceStateRef.value = VoiceSessionState.SPEAKING
                        voiceManager.speak(text)
                        Log.d("AIRI_VOICE", "Speak-action triggered from message → SPEAKING")
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (com.airi.assistant.BuildConfig.DEBUG) {
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) {
                        com.airi.assistant.ui.debug.DebugOverlay()
                    }
                }
                AnimatedVisibility(
                    visible = systemIntegrityFailed,
                    enter   = slideInVertically { -it } + fadeIn(),
                    exit    = slideOutVertically { -it } + fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Surface(
                        color  = Color(0xFFFF4444),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "System Integrity Failed",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            TextButton(onClick = {
                                viewModel.clearSystemIntegrityFailed()
                                Log.d("AIRI_PROOF", "INTEGRITY_BANNER dismissed by user")
                            }) {
                                Text("Dismiss", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Attach bottom sheet ──────────────────────────────────────────────────
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
    onLongPressTitle: () -> Unit = {},
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
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = { onLongPressTitle() })
                }
            ) {
                Text(
                    stringResource(R.string.app_agent_mode_title),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        agentState.isWorking         -> stringResource(R.string.generating)
                        modelState.isModelReady      -> modelState.selectedModelName
                        modelState.isModelLoading    -> stringResource(R.string.loading_model)
                        else                         -> stringResource(R.string.no_model_active)
                    },
                    fontSize = 12.sp,
                    color = when {
                        agentState.isWorking      -> CosmicAccent
                        modelState.isModelReady   -> CosmicAccent.copy(alpha = 0.8f)
                        else                      -> Color.White.copy(alpha = 0.6f)
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
    onOpenModels: () -> Unit = {},
    onShareAiResponse: (String) -> Unit = {},
    onSpeak: (String) -> Unit = {},
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.20f),
                    modifier = Modifier.size(96.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    if (isModelReady) stringResource(R.string.airi_ready) else "تفعيل عقل AIRI",
                    color = Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isModelReady) stringResource(R.string.ask_anything_model_active)
                    else stringResource(R.string.activate_model_gallery_first),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 18.sp
                )
                if (!isModelReady) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onOpenModels,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Outlined.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("تفعيل عقل AIRI", fontWeight = FontWeight.Bold)
                    }
                }
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
            itemsIndexed(messages.reversed(), key = { _, msg -> msg.uid }) { index, msg ->
                val prevMsg = messages.reversed().getOrNull(index + 1)
                val hideAvatar = !msg.isUser && prevMsg != null && !prevMsg.isUser
                if (msg.isUser) {
                    UserBubble(msg.text)
                } else {
                    AiBubble(
                        text      = msg.text,
                        agentTag  = msg.agentTag,
                        traceId   = msg.traceId,
                        hideAvatar = hideAvatar,
                        onShare   = onShareAiResponse,
                        onSpeak   = onSpeak
                    )
                }
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
fun AiBubble(
    text: String,
    agentTag: String? = null,
    traceId: String? = null,
    hideAvatar: Boolean = false,
    onShare: (String) -> Unit = {},
    onSpeak: (String) -> Unit = {}
) {
    val context   = androidx.compose.ui.platform.LocalContext.current
    val allTraces by com.airi.assistant.ai.agent.trace.AgentTraceManager.instance.traces.collectAsState()
    val trace = remember(traceId, allTraces) {
        if (traceId != null) allTraces.find { it.id == traceId } else null
    }
    var traceExpanded  by remember { mutableStateOf(false) }
    var showActions    by remember { mutableStateOf(false) }

    // Slide-in animation on first composition
    val transition = remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }

    androidx.compose.animation.AnimatedVisibility(
        visibleState = transition,
        enter = androidx.compose.animation.fadeIn(
            animationSpec = androidx.compose.animation.core.tween(220)
        ) + androidx.compose.animation.slideInVertically(
            animationSpec = androidx.compose.animation.core.tween(220)
        ) { it / 4 }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            // Avatar — hidden when consecutive AI messages (grouping)
            if (!hideAvatar) {
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
            } else {
                Spacer(Modifier.width(36.dp))
            }

            Column(modifier = Modifier.widthIn(max = 300.dp)) {
                Box {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                            .pointerInput(text) {
                                detectTapGestures(onLongPress = { showActions = true })
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(text, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, lineHeight = 21.sp)
                    }

                    // Long-press action menu
                    DropdownMenu(
                        expanded = showActions,
                        onDismissRequest = { showActions = false },
                        modifier = Modifier.background(Color(0xFF1A1F38))
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Copy", color = Color.White, fontSize = 14.sp)
                                }
                            },
                            onClick = {
                                showActions = false
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AIRI message", text))
                                Log.d("AIRI_UI", "Message copied len=${text.length}")
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.VolumeUp, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Speak", color = Color.White, fontSize = 14.sp)
                                }
                            },
                            onClick = {
                                showActions = false
                                onSpeak(text)
                                Log.d("AIRI_UI", "Speak action triggered len=${text.length}")
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Share, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Share", color = Color.White, fontSize = 14.sp)
                                }
                            },
                            onClick = {
                                showActions = false
                                onShare(text)
                            }
                        )
                    }
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
                        if (traceExpanded) {
                            Divider(color = Color.White.copy(alpha = 0.05f))
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

                TextButton(
                    onClick = { onShare(text) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, tint = CosmicAccent.copy(alpha = 0.72f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.share), color = CosmicAccent.copy(alpha = 0.72f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun AiStreamingBubble(text: String) {
    val isThinkingStage = text in setOf(
        "Thinking...", "Analyzing...", "Planning...", "Generating...",
        "Preparing...", "Imagining...", "Reasoning...", "Creating..."
    )
    var cursorOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(530L)
            cursorOn = !cursorOn
        }
    }
    val displayText = if (!isThinkingStage && cursorOn) "$text▋" else text

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
            Text(
                text  = displayText,
                color = Color.White.copy(alpha = if (isThinkingStage) 0.55f else 0.92f),
                fontSize   = 14.sp,
                lineHeight = 21.sp,
                fontStyle  = if (isThinkingStage) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal
            )
            if (isThinkingStage) {
                Spacer(Modifier.height(8.dp))
                AiriThinkingPulse()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// THINKING PULSE — replaces the old LinearProgressIndicator that the user
// (correctly) flagged as ugly UX. Three dots that pulse in sequence with
// AIRI's accent color. Pure Compose, no images, no extra deps.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AiriThinkingPulse(
    modifier: Modifier = Modifier,
    dotSize: Dp = 6.dp,
    color: Color = CosmicAccent
) {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "airi_pulse")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val alpha by infinite.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(
                        durationMillis = 700,
                        delayMillis = i * 180,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    ),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "airi_pulse_dot_$i"
            )
            Box(
                modifier = Modifier
                    .padding(end = if (i < 2) 5.dp else 0.dp)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
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
    voiceState: VoiceSessionState = VoiceSessionState.IDLE,
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
    onOpenModels: () -> Unit
) {
    var showAttachPopup by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val canSend = text.isNotBlank() && modelState.isModelReady && !modelState.isModelLoading && !isGenerating

    val micPulse = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(voiceState) {
        when (voiceState) {
            VoiceSessionState.LISTENING -> {
                while (true) {
                    micPulse.animateTo(1.30f, animationSpec = androidx.compose.animation.core.tween(450))
                    micPulse.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(450))
                }
            }
            VoiceSessionState.PROCESSING -> {
                while (true) {
                    micPulse.animateTo(1.18f, animationSpec = androidx.compose.animation.core.tween(600))
                    micPulse.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(600))
                }
            }
            VoiceSessionState.SPEAKING -> {
                while (true) {
                    micPulse.animateTo(1.22f, animationSpec = androidx.compose.animation.core.tween(700))
                    micPulse.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(700))
                }
            }
            else -> micPulse.snapTo(1f)
        }
    }

    LaunchedEffect(voiceInput) {
        if (voiceInput.isNotBlank()) {
            text = listOf(text, voiceInput).filter { it.isNotBlank() }.joinToString(" ")
            onVoiceConsumed()
        }
    }

    Surface(
        color = Color.Transparent,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {

            // Smart replies chips (visible only when not generating)
            androidx.compose.animation.AnimatedVisibility(
                visible = smartReplies.isNotEmpty() && !isGenerating,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(200)
                ) + androidx.compose.animation.expandVertically(
                    animationSpec = androidx.compose.animation.core.tween(200)
                ),
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(150)
                ) + androidx.compose.animation.shrinkVertically(
                    animationSpec = androidx.compose.animation.core.tween(150)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    smartReplies.forEach { reply ->
                        Surface(
                            onClick = { onSmartReply(reply) },
                            shape = RoundedCornerShape(20.dp),
                            color = CosmicAccent.copy(alpha = 0.12f),
                            modifier = Modifier.border(
                                1.dp, CosmicAccent.copy(alpha = 0.4f), RoundedCornerShape(20.dp)
                            )
                        ) {
                            Text(
                                text = reply,
                                color = CosmicAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Voice state indicator banner
            androidx.compose.animation.AnimatedVisibility(
                visible = voiceState != VoiceSessionState.IDLE,
                enter = androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(150)
                ) + androidx.compose.animation.expandVertically(
                    animationSpec = androidx.compose.animation.core.tween(150)
                ),
                exit = androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(150)
                ) + androidx.compose.animation.shrinkVertically(
                    animationSpec = androidx.compose.animation.core.tween(150)
                )
            ) {
                val (dotColor, label, textColor) = when (voiceState) {
                    VoiceSessionState.LISTENING   -> Triple(Color(0xFFFF4444), "Listening…",  Color(0xFFFF6666))
                    VoiceSessionState.PROCESSING  -> Triple(CosmicAccent,      "Processing…", CosmicAccent)
                    VoiceSessionState.SPEAKING    -> Triple(Color(0xFF4FC3F7),  "Speaking…",   Color(0xFF4FC3F7))
                    else                          -> Triple(Color.Transparent, "",             Color.Transparent)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp, start = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    if (voiceState == VoiceSessionState.SPEAKING) {
                        Spacer(Modifier.width(6.dp))
                        Text("(tap to stop)", color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp)
                    }
                }
            }

            // Model status chip
            if (!modelState.isModelReady && !modelState.isModelLoading) {
                TextButton(onClick = onOpenModels, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = Color(0xFFFFCC00), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.no_model_tap_select), color = Color(0xFFFFCC00), fontSize = 12.sp)
                }
            } else if (modelState.isModelLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp, start = 12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = CosmicAccent)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.loading_model_name, modelState.selectedModelName), color = CosmicAccent.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }

            // ── Floating pill ──────────────────────────────────────────────
            val isTyping  = text.isNotBlank()
            val showSend  = isTyping || isGenerating
            val mainEnabled = if (showSend) (canSend || isGenerating)
                              else (modelState.isModelReady && !isGenerating)

            // DarkSurface = 0x331A1A1A → dark gray @ 20 % alpha so the
            // starfield behind the pill stays visible (per spec).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF1A1A1A).copy(alpha = 0.20f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(32.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                // ── Live-Chat / Send morphing circle (left) ─────────────
                val mainScale = if (!showSend && voiceState != VoiceSessionState.IDLE) micPulse.value else 1f
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .padding(2.dp)
                        .graphicsLayer { scaleX = mainScale; scaleY = mainScale }
                        .shadow(
                            elevation = if (mainEnabled) 14.dp else 0.dp,
                            shape = CircleShape,
                            ambientColor = CosmicAccent,
                            spotColor = CosmicAccent
                        )
                        .clip(CircleShape)
                        .background(
                            when {
                                isGenerating -> Color(0xFFFF6B6B)
                                mainEnabled  -> CosmicAccent
                                else         -> CosmicAccent.copy(alpha = 0.30f)
                            }
                        )
                        .clickable(enabled = mainEnabled || isGenerating) {
                            when {
                                isGenerating -> onCancel()
                                showSend && canSend -> { onSend(text); text = "" }
                                !showSend -> onVoiceChatClick()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = when {
                            isGenerating -> "stop"
                            showSend     -> "send"
                            else         -> "live"
                        },
                        transitionSpec = {
                            (androidx.compose.animation.fadeIn(
                                animationSpec = androidx.compose.animation.core.tween(180)
                            ) + androidx.compose.animation.scaleIn(
                                animationSpec = androidx.compose.animation.core.tween(180),
                                initialScale = 0.7f
                            )) togetherWith (androidx.compose.animation.fadeOut(
                                animationSpec = androidx.compose.animation.core.tween(120)
                            ) + androidx.compose.animation.scaleOut(
                                animationSpec = androidx.compose.animation.core.tween(120),
                                targetScale = 0.7f
                            ))
                        },
                        label = "main_btn"
                    ) { state ->
                        when (state) {
                            "stop" -> Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            "send" -> Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = stringResource(R.string.send),
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            else -> Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = "Live Chat",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // ── Mic (hidden when typing or generating) ──────────────
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isTyping && !isGenerating,
                    enter = androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(180)
                    ) + androidx.compose.animation.expandHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(180)
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(120)
                    ) + androidx.compose.animation.shrinkHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(120)
                    )
                ) {
                    val micActive = voiceState != VoiceSessionState.IDLE
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(enabled = modelState.isModelReady) { onMicClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (micActive) {
                            Box(
                                modifier = Modifier
                                    .size((34 * micPulse.value).dp)
                                    .clip(CircleShape)
                                    .background(CosmicAccent.copy(alpha = 0.18f))
                            )
                        }
                        Icon(
                            Icons.Outlined.Mic,
                            contentDescription = stringResource(R.string.voice_input),
                            tint = when (voiceState) {
                                VoiceSessionState.LISTENING  -> Color.White
                                VoiceSessionState.PROCESSING -> Color.White
                                VoiceSessionState.SPEAKING   -> Color.White
                                VoiceSessionState.IDLE       ->
                                    if (modelState.isModelReady) CosmicAccent
                                    else CosmicAccent.copy(alpha = 0.35f)
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // ── Text field (transparent — pill provides surface) ───
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = modelState.isModelReady && !isGenerating,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 15.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(CosmicAccent),
                    maxLines = 6,
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = when {
                                        isGenerating              -> stringResource(R.string.generating)
                                        modelState.isModelLoading -> stringResource(R.string.model_is_loading)
                                        modelState.isModelReady   -> stringResource(R.string.message_airi)
                                        else                      -> stringResource(R.string.activate_model_first)
                                    },
                                    color = Color.White.copy(alpha = 0.40f),
                                    fontSize = 15.sp
                                )
                            }
                            inner()
                        }
                    }
                )

                // ── + Attach (right) with inline popup bubble above ────
                Box {
                    val plusInteractive = !isGenerating
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(enabled = plusInteractive) { showAttachPopup = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.attach),
                            tint = if (plusInteractive) Color.White else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    if (showAttachPopup) {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val gapPx = with(density) { 12.dp.roundToPx() }
                        val provider = remember {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize
                                ): androidx.compose.ui.unit.IntOffset {
                                    val x = (anchorBounds.right - popupContentSize.width)
                                        .coerceAtLeast(8)
                                    val y = (anchorBounds.top - popupContentSize.height - gapPx)
                                        .coerceAtLeast(8)
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
                                shape = RoundedCornerShape(28.dp),
                                color = Color(0xFF1A1F35),
                                shadowElevation = 12.dp,
                                modifier = Modifier.border(
                                    1.dp,
                                    CosmicAccent.copy(alpha = 0.25f),
                                    RoundedCornerShape(28.dp)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    AttachBubble(Icons.Outlined.CameraAlt, stringResource(R.string.take_photo)) {
                                        showAttachPopup = false; onTakePhoto()
                                    }
                                    AttachBubble(Icons.Outlined.Image, stringResource(R.string.pick_image)) {
                                        showAttachPopup = false; onPickImage()
                                    }
                                    AttachBubble(Icons.Outlined.AttachFile, stringResource(R.string.pick_file)) {
                                        showAttachPopup = false; onPickFile()
                                    }
                                    // Phase 3 — vision projector picker. Shows
                                    // up alongside camera/image/file. Tapping
                                    // it opens a *.gguf file picker; the VM
                                    // copies the selection to cache and loads
                                    // it through the serialized native bridge.
                                    AttachBubble(Icons.Outlined.AttachFile, stringResource(R.string.pick_mmproj)) {
                                        showAttachPopup = false; onPickMmproj()
                                    }
                                }
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .shadow(8.dp, CircleShape, ambientColor = CosmicAccent, spotColor = CosmicAccent)
                .clip(CircleShape)
                .background(CosmicAccent.copy(alpha = 0.18f))
                .border(1.dp, CosmicAccent.copy(alpha = 0.55f), CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = CosmicAccent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp,
            maxLines = 1
        )
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
        Box(modifier = Modifier.fillMaxHeight()) {

        // ── Scrollable content ────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 112.dp)
                .verticalScroll(rememberScrollState())
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
        DrawerNavItem(icon = Icons.Outlined.BuildCircle,    label = stringResource(R.string.custom_skills),  route = AiriRoute.SKILL_MANAGER,onNavigate = onNavigate)
        DrawerNavItem(icon = Icons.Outlined.Share,          label = stringResource(R.string.invite_friends), route = AiriRoute.REFERRALS,    onNavigate = onNavigate)

        Spacer(Modifier.height(4.dp))
        Divider(color = Color.White.copy(alpha = 0.06f))
        Spacer(Modifier.height(4.dp))

        DrawerNavItem(icon = Icons.Outlined.ManageHistory,  label = stringResource(R.string.agent_logs),    route = AiriRoute.AGENT_LOGS,   onNavigate = onNavigate)
        DrawerNavItem(icon = Icons.Outlined.Tune,           label = stringResource(R.string.agent_control), route = AiriRoute.AGENT_CONTROL,onNavigate = onNavigate)

        Spacer(Modifier.height(8.dp))

        } // end scrollable Column

        // ── Scroll fade gradient ──────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .align(Alignment.BottomCenter)
                .offset(y = (-112).dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xFF0D1124))
                    )
                )
        )

        // ── Sticky bottom: Settings + Logout ─────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xFF0D1124))
        ) {
            Divider(color = Color.White.copy(alpha = 0.08f))
            DrawerNavItem(
                icon      = Icons.Outlined.Settings,
                label     = stringResource(R.string.settings),
                route     = AiriRoute.SETTINGS,
                onNavigate = onNavigate
            )
            DrawerActionItem(
                icon    = Icons.Outlined.Logout,
                label   = stringResource(R.string.sign_out),
                tint    = Color(0xFFFF6B6B),
                onClick = onLogout
            )
            Spacer(Modifier.height(16.dp))
        }

        } // end Box
    }
}

private fun shareAiResponse(context: android.content.Context, response: String) {
    val shareText = "${response.trim()}\n\nGenerated by AIRI"
    AnalyticsService.shareableOutputShared("android_share")
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareText)
    }
    context.startActivity(Intent.createChooser(intent, "Share AIRI response"))
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
