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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.Dp
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.airi.assistant.ui.viewmodel.ModelUiState
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.airi.assistant.ui.components.AgentExecutionPanel
import com.airi.assistant.ui.theme.AiBubbleSurface
import com.airi.assistant.ui.theme.AiBubbleBorder
import com.airi.assistant.ui.theme.UserBubbleSurface
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.util.MarkdownText
import androidx.compose.runtime.snapshotFlow

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
                    if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_VOICE", "Vosk STT result len=${spoken.length} autoSend=$autoSend")
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
            if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_VOICE", "Wake-word dispatcher fired → starting in-app STT (autoSend)")
            startInAppStt(autoSend = true)
        }
    }

    // ── VoiceManager (TTS + full-duplex VAD) ────────────────────────────────
    val voiceStateRef = remember { androidx.compose.runtime.mutableStateOf(VoiceSessionState.IDLE) }

    // liveChatActiveRef: TRUE while the continuous live-chat loop is armed.
    // The TTS done-callback re-arms Vosk automatically when this is true.
    val liveChatActiveRef = remember { androidx.compose.runtime.mutableStateOf(false) }

    // voiceLoopRearmTick: bumped by onSpeakingDone() to trigger STT re-arm
    // via LaunchedEffect (avoids calling startInAppStt from a callback).
    val voiceLoopRearmTick = remember { androidx.compose.runtime.mutableStateOf(0) }

    // vadInterruptedTick: bumped by onVadInterrupted() to trigger instant
    // STT start via LaunchedEffect (same safe pattern as rearmTick).
    // Using a tick rather than a Boolean means repeated rapid interruptions
    // each get their own LaunchedEffect recomposition and can't be lost.
    val vadInterruptedTick = remember { androidx.compose.runtime.mutableStateOf(0) }

    // isVadInterrupting: true for the brief window between VAD detecting speech
    // and STT actually starting (typically <50 ms). Drives the "interrupt glow"
    // visual treatment — a warm amber pulse on the waveform banner so the user
    // gets sub-100 ms feedback that they've been heard, even before the mic icon
    // transitions. Cleared as soon as startInAppStt() is called.
    val isVadInterrupting = remember { androidx.compose.runtime.mutableStateOf(false) }

    val voiceManager = remember {
        VoiceManager(context, object : VoiceManager.VoiceListener {
            override fun onWakeWordDetected() {}
            override fun onSpeechResult(text: String) {}
            override fun onError(error: String) {
                scope.launch { snackbarHost.showSnackbar("Voice error: $error") }
                if (liveChatActiveRef.value) {
                    Log.i("AIRI_PROOF", "VOICE_LOOP_STOPPED reason=error err=$error")
                    liveChatActiveRef.value = false
                }
            }
            override fun onSpeakingStarted() {
                if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_VOICE", "TTS speaking started → VoiceSessionState.SPEAKING")
                voiceStateRef.value = VoiceSessionState.SPEAKING
            }
            override fun onSpeakingDone() {
                if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_VOICE", "TTS speaking done → VoiceSessionState.IDLE")
                voiceStateRef.value = VoiceSessionState.IDLE
                if (liveChatActiveRef.value) {
                    Log.i("AIRI_PROOF", "VOICE_LOOP_REARM_REQUESTED tick=${voiceLoopRearmTick.value + 1}")
                    voiceLoopRearmTick.value = voiceLoopRearmTick.value + 1
                }
            }
            // ── FULL-DUPLEX INTERRUPTION ────────────────────────────────
            // Called on Main by FullDuplexVadEngine after Silero confirms
            // speech (~20 ms latency). TTS has ALREADY been stopped inside
            // VoiceManager.startVad() before this callback fires, so there
            // is no audible tail. We just need to update UI state and arm STT.
            override fun onVadInterrupted() {
                Log.i("AIRI_PROOF", "VAD_INTERRUPTED_CB tick=${vadInterruptedTick.value + 1} loop=${liveChatActiveRef.value}")
                // Issue 7 — micro-latency glow: arm the amber interrupt pulse
                // BEFORE the tick bump so the first recomposition frame shows it.
                isVadInterrupting.value = true
                // Snap voice state to LISTENING immediately. voiceStateRef is
                // the TTS-owned state; writing LISTENING here prevents the
                // voiceStateRef LaunchedEffect from reverting us to IDLE.
                voiceStateRef.value = VoiceSessionState.LISTENING
                // Bump tick — LaunchedEffect(vadInterruptedTick.value) below
                // will start STT on the Compose coroutine context.
                vadInterruptedTick.value = vadInterruptedTick.value + 1
            }
        })
    }
    DisposableEffect(Unit) { onDispose { voiceManager.destroy() } }

    // ── TTS / speak state (declared here so all downstream LaunchedEffects can see them) ──
    var speakNextResponse  by rememberSaveable { mutableStateOf(false) }
    var lastSpokenMsgId    by rememberSaveable { mutableStateOf(-1L) }
    var ttsStreamingActive by rememberSaveable { mutableStateOf(false) }
    var lastTtsStreamLen   by rememberSaveable { mutableStateOf(0) }

    // ── Issue 6: Lifecycle gap — Android 15 mic-lock prevention ──────────
    // When the app is backgrounded (ON_PAUSE), stop ALL audio subsystems
    // immediately. Without this, on Android 14/15 the OS may revoke the
    // microphone grant mid-session causing AudioRecord to return ERROR_DEAD_OBJECT
    // permanently, or keep a ghost recording thread draining the battery.
    @Suppress("DEPRECATION")
    val _lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(_lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                Log.i("AIRI_PROOF", "LIFECYCLE_ON_PAUSE → releasing all audio resources")
                voiceManager.stopVadIfRunning()
                stopInAppStt()
                isVadInterrupting.value = false
                voiceStateRef.value = VoiceSessionState.IDLE
                voiceState = VoiceSessionState.IDLE
                if (liveChatActiveRef.value) {
                    liveChatActiveRef.value = false
                    Log.i("AIRI_PROOF", "VOICE_LOOP_PAUSED reason=app_backgrounded")
                }
            }
        }
        _lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { _lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── VAD interruption handler ─────────────────────────────────────────
    // Reacts to vadInterruptedTick bumps from onVadInterrupted().
    // Runs on the Compose coroutine scope (Main thread), safe to call
    // startInAppStt which mutates Compose state.
    // No delay needed — the interruption should feel INSTANT.
    LaunchedEffect(vadInterruptedTick.value) {
        if (vadInterruptedTick.value > 0) {
            Log.i("AIRI_PROOF", "VAD_INTERRUPT_EFFECT tick=${vadInterruptedTick.value} loop=${liveChatActiveRef.value}")
            // Always set state to LISTENING + arm STT after VAD interrupt,
            // regardless of liveChatActiveRef — the user clearly wants to speak.
            voiceState = VoiceSessionState.LISTENING
            // autoSend: in live-chat mode or when speakNextResponse is primed,
            // send the STT result automatically. Otherwise just fill the text field.
            val autoSend = liveChatActiveRef.value || speakNextResponse
            // Also reset speakNextResponse so the PREVIOUS AI response is not
            // re-spoken after the interrupt (the user is taking the floor).
            speakNextResponse = false
            ttsStreamingActive = false
            lastTtsStreamLen = 0
            if (!agentState.isWorking) {
                Log.i("AIRI_PROOF", "VAD_INTERRUPT_STT_START autoSend=$autoSend")
                // Clear glow BEFORE startInAppStt so the waveform banner
                // transitions: amber glow → normal listening state.
                isVadInterrupting.value = false
                startInAppStt(autoSend = autoSend)
            } else {
                // Generation still running — cancel it then start listening.
                Log.i("AIRI_PROOF", "VAD_INTERRUPT_CANCEL_GEN then STT autoSend=$autoSend")
                viewModel.cancelGeneration()
                kotlinx.coroutines.delay(100)
                isVadInterrupting.value = false
                if (!agentState.isWorking) startInAppStt(autoSend = autoSend)
            }
        }
    }

    // ── Normal TTS-done loop rearm ───────────────────────────────────────
    // (Unchanged from original — fires when TTS completes normally, no VAD)
    LaunchedEffect(voiceLoopRearmTick.value) {
        if (voiceLoopRearmTick.value > 0 &&
            liveChatActiveRef.value &&
            modelState.isModelReady &&
            !agentState.isWorking
        ) {
            kotlinx.coroutines.delay(350)
            if (liveChatActiveRef.value && !agentState.isWorking) {
                Log.i("AIRI_PROOF", "VOICE_LOOP_REARM_FIRED tick=${voiceLoopRearmTick.value}")
                startInAppStt(autoSend = true)
            } else {
                Log.i("AIRI_PROOF", "VOICE_LOOP_REARM_ABORTED reason=user_exit_or_busy")
            }
        }
    }
    // Sync TTS-driven state back to UI state variable
    LaunchedEffect(voiceStateRef.value) {
        val ttsState = voiceStateRef.value
        if (ttsState == VoiceSessionState.SPEAKING || ttsState == VoiceSessionState.IDLE) {
            if (voiceState != VoiceSessionState.LISTENING && voiceState != VoiceSessionState.PROCESSING) {
                voiceState = ttsState
            }
        }
    }
    // Auto-stop: if still LISTENING after 7s with no result, revert to IDLE.
    // PRODUCTION FIX: also call stopInAppStt() so the underlying VoskEngine
    // AudioRecord capture loop is signalled to exit. Without this, only
    // voiceState changes to IDLE while the microphone and Vosk recognizer
    // keep running — wasting battery and creating a window where a delayed
    // onFinal callback can fire into the wrong UI state.
    LaunchedEffect(voiceState) {
        if (voiceState == VoiceSessionState.LISTENING) {
            kotlinx.coroutines.delay(7_000L)
            if (voiceState == VoiceSessionState.LISTENING) {
                if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_VOICE", "Auto-stop: 7s silence → stopping engine + IDLE")
                Log.i("AIRI_PROOF", "AUTO_STOP_TIMEOUT 7s elapsed → stopping VoskEngine")
                stopInAppStt()
                voiceState = VoiceSessionState.IDLE
            }
        }
    }

    val voicePrefs = remember { context.getSharedPreferences("airi_voice", android.content.Context.MODE_PRIVATE) }
    LaunchedEffect(voiceChatInput) {
        val input = voiceChatInput
        if (input.isNotBlank() && modelState.isModelReady && !agentState.isWorking) {
            if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_VOICE", "VoiceChat auto-send: '${input.take(60)}' len=${input.length}")
            if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_UI", "sendMessage triggered by voice input len=${input.length}")
            voiceChatInput = ""
            voiceState = VoiceSessionState.IDLE
            if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_VOICE", "MicState → IDLE (auto-send dispatched)")
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
                if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_VOICE", "TTS triggered → SPEAKING msgId=${lastMsg.id} text_len=${lastMsg.text.length}")
                Log.i("AIRI_PROOF", "VOICE_RESPONSE_COMPLETE msgId=${lastMsg.id} chars=${lastMsg.text.length} loop_active=${liveChatActiveRef.value}")
                // PHASE 2/4: if streaming TTS already spoke chunks, just
                // flush the tail; else fall back to full one-shot speak.
                if (ttsStreamingActive) {
                    voiceManager.ttsStreamFlush()
                    ttsStreamingActive = false
                } else {
                    voiceManager.speak(lastMsg.text)
                }
            }
        }
    }

    // PHASE 5 (perf): observe streamingText via snapshotFlow so this coroutine
    // stays alive for the entire session and we never pay for coroutine
    // cancel/restart on every individual token emission. Previous approach used
    // LaunchedEffect(streamingText, ...) which cancelled and relaunched the
    // coroutine on each token — O(tokens) coroutine churn. snapshotFlow collects
    // inside a single long-lived coroutine that only acts when state actually changes.
    LaunchedEffect(speakNextResponse, agentState.isWorking) {
        snapshotFlow { streamingText }.collect { current ->
            if (!speakNextResponse) {
                if (ttsStreamingActive) {
                    voiceManager.ttsStreamFlush()
                    ttsStreamingActive = false
                }
                lastTtsStreamLen = 0
                return@collect
            }
            val isPlaceholder = current.isBlank() ||
                current == "Thinking..." || current == "Analyzing image..."
            if (isPlaceholder) {
                if (ttsStreamingActive) {
                    voiceManager.ttsStreamFlush()
                    ttsStreamingActive = false
                }
                lastTtsStreamLen = 0
                return@collect
            }
            if (current.length < lastTtsStreamLen) {
                voiceManager.ttsStreamReset()
                ttsStreamingActive = true
                lastTtsStreamLen = 0
            }
            if (!ttsStreamingActive) {
                voiceManager.ttsStreamReset()
                ttsStreamingActive = true
            }
            if (current.length > lastTtsStreamLen) {
                val delta = current.substring(lastTtsStreamLen)
                voiceManager.ttsStreamAppend(delta)
                lastTtsStreamLen = current.length
            }
        }
    }

    // ── PHASE 3 (actual fix): unified attachment list ───────────────────────
    // Replaces the previous fragmented state (selectedImageUri, capturedBitmap,
    // an orphan filePicker that did nothing). All four pickers (gallery
    // image, camera capture, generic file, mmproj if re-enabled) now append
    // to this single list. Removing a chip drops it from the list. On send
    // we hand the list to ChatViewModel.sendMessageWithAttachments which
    // makes one explicit capability decision — no hidden forks downstream.
    var pendingAttachments by remember {
        mutableStateOf<List<com.airi.assistant.domain.ChatAttachment>>(emptyList())
    }

    fun addAttachment(att: com.airi.assistant.domain.ChatAttachment) {
        // Cap at 6 attachments per turn so the chip row never overflows
        // the input area on small devices.
        if (pendingAttachments.size >= 6) {
            scope.launch { snackbarHost.showSnackbar("Maximum 6 attachments per message") }
            return
        }
        pendingAttachments = pendingAttachments + att
        Log.i("AIRI_PROOF",
            "ATTACHMENT_ADDED kind=${att.kind} name=${att.displayName} " +
            "total=${pendingAttachments.size}")
    }
    fun removeAttachment(id: String) {
        pendingAttachments = pendingAttachments.filterNot { it.id == id }
        Log.i("AIRI_PROOF", "ATTACHMENT_REMOVED id=$id remaining=${pendingAttachments.size}")
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val fileName = uri.lastPathSegment ?: uri.toString()
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull()?.takeIf { it >= 0 }
            Log.i("AIRI_PROOF",
                "ATTACHMENT_SELECTED kind=FILE name=$fileName mime=$mime size=$size")
            addAttachment(
                com.airi.assistant.domain.ChatAttachment(
                    kind = com.airi.assistant.domain.ChatAttachment.Kind.FILE,
                    uri = uri,
                    displayName = fileName,
                    mimeType = mime,
                    sizeBytes = size
                )
            )
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment ?: "image_${System.currentTimeMillis()}"
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val visionReady = viewModel.isVisionReady()
            Log.i("AIRI_PROOF",
                "ATTACHMENT_SELECTED kind=IMAGE name=$name uri=$uri " +
                "vision_backend=${if (visionReady) "mtmd" else "none"}")
            // Keep the historical IMAGE_ATTACHED tag for back-compat parsers.
            Log.i("AIRI_PROOF", "IMAGE_ATTACHED uri=$uri vision_ready=$visionReady")
            addAttachment(
                com.airi.assistant.domain.ChatAttachment(
                    kind = com.airi.assistant.domain.ChatAttachment.Kind.IMAGE,
                    uri = uri,
                    displayName = name,
                    mimeType = mime
                )
            )
        }
    }
    // Phase 3 — mmproj projector picker. UI is hidden (auto-load handles
    // the common case in LlamaManager.maybeAutoLoadMmproj), but the wiring
    // is preserved so re-enabling the bubble is a one-literal flip.
    val mmprojPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            Log.i("AIRI_PROOF", "MMPROJ_PICKED uri=$uri name=${uri.lastPathSegment ?: "unknown"}")
            viewModel.loadMmproj(uri)
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            val name = "camera_${System.currentTimeMillis()}.jpg"
            Log.i("AIRI_PROOF", "ATTACHMENT_SELECTED kind=CAMERA name=$name")
            addAttachment(
                com.airi.assistant.domain.ChatAttachment(
                    kind = com.airi.assistant.domain.ChatAttachment.Kind.CAMERA,
                    bitmap = bitmap,
                    displayName = name,
                    mimeType = "image/jpeg"
                )
            )
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
              Column(modifier = Modifier.fillMaxWidth().imePadding()) {
                // ── Live agent execution status panel ─────────────────────────
                AgentExecutionPanel(agentState = agentState)
                // ── PHASE 3 (actual fix): unified attachment chip row ──────────
                // One row, one chip per attachment, regardless of kind. The
                // image-vs-file-vs-camera distinction is now just an icon +
                // a label, not a separate code path.
                androidx.compose.animation.AnimatedVisibility(
                    visible = pendingAttachments.isNotEmpty(),
                    enter = androidx.compose.animation.fadeIn() +
                            androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.fadeOut() +
                           androidx.compose.animation.shrinkVertically()
                ) {
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(pendingAttachments, key = { it.id }) { att ->
                            AttachmentChip(
                                attachment = att,
                                onRemove = { removeAttachment(att.id) }
                            )
                        }
                    }
                }
                ChatInputBar(
                    modelState      = modelState,
                    isGenerating    = agentState.isWorking,
                    voiceInput      = voiceInput,
                    smartReplies    = smartReplies,
                    onSend          = { text ->
                        // PHASE 3 (actual fix): one entry point for everything.
                        // The view-model makes the single capability decision
                        // (vision vs. text-marker) — the UI never branches.
                        val toSend = pendingAttachments
                        if (toSend.isNotEmpty()) {
                            Log.i("AIRI_PROOF",
                                "ATTACHMENTS_SENT count=${toSend.size} " +
                                "kinds=${toSend.joinToString(",") { it.kind.name }} " +
                                "vision_ready=${viewModel.isVisionReady()}")
                            viewModel.sendMessageWithAttachments(text, toSend)
                            pendingAttachments = emptyList()
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
                    isVadInterrupting = isVadInterrupting.value,
                    onMicClick      = mic@{
                        // Interrupt TTS if currently speaking
                        if (voiceState == VoiceSessionState.SPEAKING) {
                            voiceManager.stopVadIfRunning()   // Issue 2: stop VAD before TTS
                            voiceManager.stopSpeaking()
                            isVadInterrupting.value = false
                            voiceStateRef.value = VoiceSessionState.IDLE
                            voiceState = VoiceSessionState.IDLE
                            if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_VOICE", "TTS interrupted by mic press → IDLE")
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
                            voiceManager.stopVadIfRunning()   // Issue 2: stop VAD before TTS
                            voiceManager.stopSpeaking()
                            isVadInterrupting.value = false
                            voiceStateRef.value = VoiceSessionState.IDLE
                            voiceState = VoiceSessionState.IDLE
                            Log.i("AIRI_PROOF", "VOICE_INTERRUPTED reason=tap_during_speaking loop_active=${liveChatActiveRef.value}")
                            if (liveChatActiveRef.value) {
                                liveChatActiveRef.value = false
                                Log.i("AIRI_PROOF", "VOICE_LOOP_STOPPED reason=tap_during_speaking")
                            }
                            if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_VOICE", "TTS interrupted by voice-chat press → IDLE")
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
                    onOpenModels    = { onNavigate(AiriRoute.MODELS) },
                    onUserStartedTyping = {
                        // User typing == intent to take the floor. Stop TTS
                        // instantly. Also stop VAD so it doesn't fire an
                        // interrupt after the user has already interrupted by
                        // typing (prevents spurious STT re-arm mid-keypress).
                        if (voiceState == VoiceSessionState.SPEAKING) {
                            Log.i("AIRI_PROOF", "TTS_INTERRUPTED reason=user_typing")
                            voiceManager.stopVadIfRunning()   // Issue 2: stop VAD before TTS
                            voiceManager.stopSpeaking()
                            isVadInterrupting.value = false
                            voiceState = VoiceSessionState.IDLE
                        }
                        if (liveChatActiveRef.value) {
                            Log.i("AIRI_PROOF", "VOICE_LOOP_STOPPED reason=user_typing")
                            liveChatActiveRef.value = false
                        }
                    }
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
                        voiceManager.stopVadIfRunning()   // Issue 2: stop VAD before TTS
                        voiceManager.stopSpeaking()
                        isVadInterrupting.value = false
                        voiceState = VoiceSessionState.SPEAKING
                        voiceStateRef.value = VoiceSessionState.SPEAKING
                        voiceManager.speak(text)
                        if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_VOICE", "Speak-action triggered from message → SPEAKING")
                    },
                    onSuggestionClick = { suggestion -> viewModel.sendMessage(suggestion) },
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
                                if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_PROOF", "INTEGRITY_BANNER dismissed by user")
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
    onSuggestionClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    // Pre-reverse the list once per messages-list change (not on every
    // streaming token), eliminating O(n) allocation on each recomposition.
    val reversedMessages = remember(messages) { messages.reversed() }

    val isPinnedToBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex <= 1 }
    }
    var lastScrolledStreamLen by remember { mutableStateOf(0) }
    LaunchedEffect(messages.size) {
        if (isPinnedToBottom && (messages.isNotEmpty() || streamingText.isNotEmpty())) {
            scope.launch { listState.animateScrollToItem(0) }
        }
        lastScrolledStreamLen = 0
    }
    // PHASE 5 (perf): use snapshotFlow so the scroll-to-bottom observer lives in
    // one long-running coroutine instead of cancelling/restarting on every token.
    // Also use scrollToItem (instant) for mid-stream (fast tokens) and only
    // animateScrollToItem on the final scroll triggered by message count change.
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
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 28.dp)
            ) {
                // Pulsing avatar icon
                val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "idle_pulse")
                val idleAlpha by infinite.animateFloat(
                    initialValue = 0.15f,
                    targetValue  = 0.28f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(1800, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "idle_alpha"
                )
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(CosmicAccent.copy(alpha = idleAlpha), Color.Transparent)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.SmartToy,
                        contentDescription = null,
                        tint = CosmicAccent.copy(alpha = idleAlpha + 0.10f),
                        modifier = Modifier.size(44.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    if (isModelReady) stringResource(R.string.airi_ready) else stringResource(R.string.app_name),
                    color = Color.White.copy(alpha = 0.80f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    letterSpacing = (-0.3).sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isModelReady) stringResource(R.string.ask_anything_model_active)
                    else stringResource(R.string.activate_model_gallery_first),
                    color = Color.White.copy(alpha = 0.38f),
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 19.sp
                )
                if (!isModelReady) {
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onOpenModels,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Outlined.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.model_gallery), fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    // Suggestion chips
                    Spacer(Modifier.height(28.dp))
                    val suggestions = listOf(
                        stringResource(R.string.suggestion_what_can_you_do),
                        stringResource(R.string.suggestion_explain_ai),
                        stringResource(R.string.suggestion_write_poem),
                        stringResource(R.string.suggestion_brainstorm)
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        suggestions.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { s ->
                                    Surface(
                                        onClick = { onSuggestionClick(s) },
                                        shape = RoundedCornerShape(20.dp),
                                        color = CosmicAccent.copy(alpha = 0.08f),
                                        modifier = Modifier.border(
                                            1.dp, CosmicAccent.copy(alpha = 0.28f), RoundedCornerShape(20.dp)
                                        )
                                    ) {
                                        Text(
                                            text = s,
                                            color = Color.White.copy(alpha = 0.72f),
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        Box(modifier = modifier) {
            LazyColumn(
                state            = listState,
                modifier         = Modifier.fillMaxSize(),
                reverseLayout    = true,
                contentPadding   = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (streamingText.isNotEmpty() && isGenerating) {
                    item(key = "streaming") { AiStreamingBubble(text = streamingText) }
                }
                itemsIndexed(reversedMessages, key = { _, msg -> msg.uid }) { index, msg ->
                    val prevMsg = reversedMessages.getOrNull(index + 1)
                    val hideAvatar = !msg.isUser && prevMsg != null && !prevMsg.isUser
                    if (msg.isUser) {
                        UserBubble(msg.text, msg.imageUri)
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
            // Scroll-to-bottom FAB — surfaces when the user scrolls up
            ScrollToBottomFab(
                visible  = !isPinnedToBottom,
                onClick  = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
            )
        }
    }
}

@Composable
fun UserBubble(text: String, imageUri: String? = null) {
    val displayText = remember(text, imageUri) {
        if (imageUri != null) text.replace(Regex("""\s*\n*\[image:[^\]]*\]\s*$"""), "").trim()
        else text
    }
    val context = LocalContext.current
    val haptic  = LocalHapticFeedback.current

    val transition = remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = transition,
        enter = androidx.compose.animation.fadeIn(
            animationSpec = androidx.compose.animation.core.tween(200)
        ) + androidx.compose.animation.slideInHorizontally(
            animationSpec = androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        ) { it / 5 }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp))
                    .background(UserBubbleSurface)
                    .border(1.dp, CosmicAccent.copy(alpha = 0.28f), RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AIRI", displayText))
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.End
            ) {
                if (imageUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp))
                            .background(Color.Black.copy(alpha = 0.25f)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                    if (displayText.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (displayText.isNotBlank() || imageUri == null) {
                    Text(
                        text       = displayText,
                        color      = Color.White,
                        fontSize   = 15.sp,
                        lineHeight = 23.sp
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
    androidx.compose.animation.AnimatedVisibility(
        visibleState = transition,
        enter = androidx.compose.animation.fadeIn(
            animationSpec = androidx.compose.animation.core.tween(240)
        ) + androidx.compose.animation.slideInVertically(
            animationSpec = androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        ) { it / 5 }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 44.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!hideAvatar) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(CosmicAccent.copy(alpha = 0.22f), CosmicAccent.copy(alpha = 0.06f))
                            )
                        )
                        .border(1.dp, CosmicAccent.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("A", color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
            } else {
                Spacer(Modifier.width(40.dp))
            }

            Column {
                // ── Bubble ────────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp))
                        .background(AiBubbleSurface)
                        .border(1.dp, AiBubbleBorder, RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    MarkdownText(
                        rawText     = text,
                        modifier    = Modifier.fillMaxWidth(),
                        baseFontSp  = 15f,
                        lineHeightSp = 23f
                    )
                }

                // ── Inline action row ─────────────────────────────────────────
                Row(
                    modifier = Modifier.padding(start = 2.dp, top = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AIRI", text))
                            if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_UI", "Message copied len=${text.length}")
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", tint = Color.White.copy(alpha = 0.38f), modifier = Modifier.size(15.dp))
                    }
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSpeak(text)
                            if (com.airi.assistant.BuildConfig.DEBUG) Log.d("AIRI_UI", "Speak action triggered len=${text.length}")
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = "Speak", tint = Color.White.copy(alpha = 0.38f), modifier = Modifier.size(15.dp))
                    }
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onShare(text)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White.copy(alpha = 0.38f), modifier = Modifier.size(15.dp))
                    }
                }

                // ── Agent Trace Card ──────────────────────────────────────────
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
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(13.dp))
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
                                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
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
                                                Text(text = step.displayName, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
                                                    color = if (step.error != null) Color(0xFFFF5252).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.4f),
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
                        Text(text = "⚙ $agentTag", color = CosmicAccent.copy(alpha = 0.85f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
                // Execution origin badge — always visible on assistant messages.
                // LOCAL / CLOUD / HYBRID — AIRI never hides where the answer came from.
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
    val isThinkingStage = text in setOf(
        "Thinking...", "Analyzing...", "Planning...", "Generating...",
        "Preparing...", "Imagining...", "Reasoning...", "Creating..."
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 44.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(CosmicAccent.copy(alpha = 0.22f), CosmicAccent.copy(alpha = 0.06f))
                    )
                )
                .border(1.dp, CosmicAccent.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp))
                .background(AiBubbleSurface)
                .border(1.dp, CosmicAccent.copy(alpha = 0.22f), RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text       = text,
                    color      = Color.White.copy(alpha = if (isThinkingStage) 0.50f else 0.93f),
                    fontSize   = 15.sp,
                    lineHeight = 23.sp,
                    fontStyle  = if (isThinkingStage) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                    modifier   = Modifier.weight(1f, fill = false)
                )
                if (!isThinkingStage) BlinkingCursor()
            }
            if (isThinkingStage) {
                Spacer(Modifier.height(10.dp))
                AiriThinkingPulse()
            }
        }
    }
}

/**
 * PHASE 3 (actual fix): single chip composable for ALL attachment kinds.
 * Renders a 40dp leading visual (image thumbnail for images/cameras, file
 * icon for files), the display name, a one-line subtitle (MIME or vision
 * status), and a remove button. The chip never collapses on load failure
 * because the leading box has a solid fallback background + icon.
 */
@Composable
private fun AttachmentChip(
    attachment: com.airi.assistant.domain.ChatAttachment,
    onRemove: () -> Unit
) {
    val accent = CosmicAccent
    val subtitle = when (attachment.kind) {
        com.airi.assistant.domain.ChatAttachment.Kind.IMAGE,
        com.airi.assistant.domain.ChatAttachment.Kind.CAMERA ->
            attachment.mimeType ?: "image"
        com.airi.assistant.domain.ChatAttachment.Kind.FILE ->
            attachment.mimeType ?: "file"
    }
    Row(
        modifier = Modifier
            .widthIn(min = 140.dp, max = 240.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1A1A1A).copy(alpha = 0.55f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            // Fallback icon shown beneath any image so the chip never collapses.
            val fallback = when (attachment.kind) {
                com.airi.assistant.domain.ChatAttachment.Kind.IMAGE,
                com.airi.assistant.domain.ChatAttachment.Kind.CAMERA -> Icons.Default.Image
                com.airi.assistant.domain.ChatAttachment.Kind.FILE   -> Icons.Default.AttachFile
            }
            Icon(
                imageVector = fallback,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            // Real thumbnail for image attachments (gallery URI or in-mem bitmap).
            val thumbModel: Any? = attachment.uri ?: attachment.bitmap
            if (attachment.isVisualImage && thumbModel != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(thumbModel)
                        .crossfade(true)
                        .build(),
                    contentDescription = attachment.displayName,
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.displayName,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove attachment",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun BlinkingCursor() {
    var cursorOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500L)
            cursorOn = !cursorOn
        }
    }
    androidx.compose.animation.AnimatedContent(
        targetState = cursorOn,
        transitionSpec = {
            androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(80)
            ) togetherWith androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(80)
            )
        },
        label = "cursor_blink"
    ) { on ->
        Text(
            text      = if (on) "▍" else " ",
            color     = CosmicAccent.copy(alpha = 0.85f),
            fontSize  = 15.sp,
            lineHeight = 23.sp
        )
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
    dotSize: Dp = 7.dp,
    color: Color = CosmicAccent
) {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "airi_pulse")
    // PERF: both alpha and scale are driven via graphicsLayer, which is a
    // draw-phase-only property — zero layout passes. Background color uses a
    // stable base color; only graphicsLayer alpha varies, avoiding Color
    // allocation on every frame (color.copy(alpha=…) allocates a new Color).
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val alphaPct by infinite.animateFloat(
                initialValue = 0.20f,
                targetValue  = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(
                        durationMillis = 600,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    ),
                    repeatMode   = androidx.compose.animation.core.RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(i * 160)
                ),
                label = "airi_pulse_a_$i"
            )
            val scale by infinite.animateFloat(
                initialValue = 0.70f,
                targetValue  = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(
                        durationMillis = 600,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    ),
                    repeatMode   = androidx.compose.animation.core.RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(i * 160)
                ),
                label = "airi_pulse_s_$i"
            )
            Box(
                modifier = Modifier
                    .padding(end = if (i < 2) 6.dp else 0.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = alphaPct }
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color)
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
    // When true: VAD just detected user speech and we're in the ~50 ms
    // transition window before STT starts. Renders amber "Interrupting…"
    // glow so the user sees instant feedback even before the mic icon changes.
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
    // Fires the first time the user starts typing a brand-new message after
    // the input was empty. ChatScreen uses it to interrupt in-flight TTS.
    onUserStartedTyping: () -> Unit = {}
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

            // Voice state indicator banner — animated waveform bars
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
                // Interrupt glow: amber pulse when VAD fires, before STT starts.
                // Regular LISTENING = coral red. PROCESSING = cosmic accent.
                // SPEAKING = sky blue. isVadInterrupting overrides LISTENING.
                val waveColor = when {
                    isVadInterrupting                        -> Color(0xFFFFB347)  // amber
                    voiceState == VoiceSessionState.LISTENING  -> Color(0xFFFF6B6B)  // coral
                    voiceState == VoiceSessionState.PROCESSING -> CosmicAccent
                    voiceState == VoiceSessionState.SPEAKING   -> Color(0xFF4FC3F7)  // sky blue
                    else                                       -> CosmicAccent
                }
                val label = when {
                    isVadInterrupting                          -> "Interrupting…"
                    voiceState == VoiceSessionState.LISTENING  -> "Listening…"
                    voiceState == VoiceSessionState.PROCESSING -> "Processing…"
                    voiceState == VoiceSessionState.SPEAKING   -> "Speaking…"
                    else                                       -> ""
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 6.dp, start = 14.dp)
                ) {
                    VoiceWaveformBars(
                        active = voiceState == VoiceSessionState.LISTENING || isVadInterrupting,
                        color  = waveColor
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = waveColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    if (voiceState == VoiceSessionState.SPEAKING) {
                        Spacer(Modifier.width(5.dp))
                        Text("· tap to stop", color = Color.White.copy(alpha = 0.32f), fontSize = 11.sp)
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
                    onValueChange = { newValue ->
                        // PHASE 4 (audio polish): interrupt TTS on the very
                        // first character of a new turn — but only on the
                        // empty→non-empty transition so we don't spam the
                        // callback (and thus stop()) on every keystroke.
                        if (text.isEmpty() && newValue.isNotEmpty()) {
                            onUserStartedTyping()
                        }
                        text = newValue
                    },
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
                                    // PHASE 3 (revised) — the dedicated vision-
                                    // projector attach button is intentionally
                                    // HIDDEN from the chat attach popup. The
                                    // unified attachment flow (Camera / Image
                                    // / File) is the only thing the user
                                    // touches; mmproj is auto-loaded by
                                    // LlamaManager.maybeAutoLoadMmproj() at
                                    // model-load time and is also reachable
                                    // from Settings → Advanced for power
                                    // users. Wiring (`onPickMmproj`) is
                                    // preserved so Settings can call it.
                                    if (false) {
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

// ─────────────────────────────────────────────────────────────────────────────
// VOICE WAVEFORM BARS
// Five bars that animate their height in staggered sequence, conveying live
// audio activity. Used in the voice-state indicator banner inside ChatInputBar.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VoiceWaveformBars(
    active: Boolean,
    color: Color,
    barCount: Int = 5,
    modifier: Modifier = Modifier
) {
    // PERF: single InfiniteTransition owns all bar animators — one Choreographer
    // callback drives all 5 bars instead of 5 separate animation clocks.
    // Background uses stable color; graphicsLayer alpha avoids Color allocation per frame.
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "voice_waveform")
    val barAlpha = if (active) 0.88f else 0.40f
    Row(
        modifier = modifier.height(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (i in 0 until barCount) {
            val maxH = when (i % 3) { 0 -> 14f; 1 -> 18f; else -> 10f }
            val barH by infinite.animateFloat(
                initialValue = 3f,
                targetValue  = if (active) maxH else 4f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(
                        durationMillis = 280 + i * 70,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    ),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(i * 75)
                ),
                label = "waveform_bar_$i"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barH.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .graphicsLayer { alpha = barAlpha }
                    .background(color)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SCROLL-TO-BOTTOM FAB
// Appears with a spring-pop animation when the user has scrolled up away from
// the latest message. Tapping snaps the list back to the bottom.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ScrollToBottomFab(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(
            animationSpec = androidx.compose.animation.core.tween(200)
        ) + androidx.compose.animation.scaleIn(
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness    = androidx.compose.animation.core.Spring.StiffnessMedium
            ),
            initialScale = 0.55f
        ),
        exit = androidx.compose.animation.fadeOut(
            animationSpec = androidx.compose.animation.core.tween(140)
        ) + androidx.compose.animation.scaleOut(
            animationSpec = androidx.compose.animation.core.tween(140),
            targetScale = 0.55f
        ),
        modifier = modifier
    ) {
        // PERF: no shadow here — colored shadows force a GPU compositing layer.
        // The FAB is only visible when the user is mid-scroll so visual weight
        // is sufficient without the overdraw penalty.
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(CosmicAccent.copy(alpha = 0.90f))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Scroll to latest",
                tint = Color.Black,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
