package com.airi.assistant.ui.screens

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.airi.assistant.voice.LiveVoiceService
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.voice.OpenWakeWordEngine
import com.airi.assistant.voice.PorcupineEngine
import com.airi.assistant.voice.VoskModelManager
import com.airi.assistant.voice.realtime.GeminiLiveProvider
import com.airi.assistant.voice.realtime.OpenAIRealtimeProvider
import com.airi.assistant.execution.security.SecureApiKeyStore
import com.airi.assistant.execution.CloudProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    onBack: () -> Unit,
    onNavigateToPersonalization: () -> Unit = {}
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope    = rememberCoroutineScope()

    var downloadProgress by remember { mutableStateOf<Int?>(null) }
    var downloadError    by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        VoskModelManager.refreshInstalled(context)
        if (VoskModelManager.installed.value.isEmpty()) {
            scope.launch {
                val triggered = VoskModelManager.triggerFirstRunDownloadIfNeeded(
                    context,
                    onProgress = { pct -> downloadProgress = pct }
                )
                if (triggered) {
                    downloadProgress = null
                    snackbar.showSnackbar("Voice model downloaded and activated")
                }
            }
        }
    }

    val installed by VoskModelManager.installed.collectAsState()
    val activeId  by VoskModelManager.activeModelId.collectAsState()
    var porcupineStatus by remember { mutableStateOf(PorcupineEngine.status(context)) }
    val owwStatus by remember { mutableStateOf(OpenWakeWordEngine.status(context)) }
    var accessKeyInput  by remember { mutableStateOf("") }
    var showKey         by remember { mutableStateOf(false) }

    val smallEnPreset = VoskModelManager.PRESETS.first()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.voice_settings_title), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        snackbarHost   = { SnackbarHost(snackbar) },
        containerColor = AiriTheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape    = AIRIShapes.md,
                color    = CosmicAccent.copy(alpha = 0.08f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CosmicAccent.copy(0.25f), AIRIShapes.md)
                    .clickable { onNavigateToPersonalization() }
            ) {
                Row(
                    modifier              = Modifier.padding(14.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.Tune, null, tint = CosmicAccent, modifier = Modifier.size(22.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Voice Personalization",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = AiriTheme.onBackground
                        )
                        Text(
                            stringResource(R.string.voice_personalization_shortcut_desc),
                            fontSize = 12.sp, color = AiriTheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = AiriTheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
            }
            VoiceStatusCard(
                porcupineStatus = porcupineStatus,
                owwStatus       = owwStatus,
                voskReady       = VoskModelManager.isReady(context)
            )
            if (installed.isEmpty()) {
                Surface(
                    shape    = AIRIShapes.md,
                    color    = CosmicAccent.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth().border(
                        1.dp, CosmicAccent.copy(0.4f), AIRIShapes.md
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Outlined.MicOff, null, tint = CosmicAccent,
                                modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.vosk_no_model_card_title), color = AiriTheme.onBackground,
                                fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        Text(
                            stringResource(R.string.vosk_download_small_desc),
                            color = AiriTheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp
                        )
                        downloadError?.let {
                            Text(it, color = SemanticError, fontSize = 12.sp)
                        }
                        if (downloadProgress != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinearProgressIndicator(
                                    progress = ((downloadProgress ?: 0) / 100f),
                                    modifier = Modifier.fillMaxWidth().clip(AIRIShapes.xs.copy(topStart = 4.dp)),
                                    color = CosmicAccent,
                                    trackColor = CosmicAccent.copy(0.2f)
                                )
                                Text(
                                    stringResource(R.string.vosk_downloading_progress, downloadProgress!!),
                                    color = CosmicAccent, fontSize = 12.sp
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    downloadError = null
                                    scope.launch {
                                        val result = VoskModelManager.downloadAndInstall(
                                            context  = context,
                                            preset   = smallEnPreset,
                                            onProgress = { pct -> downloadProgress = pct }
                                        )
                                        downloadProgress = null
                                        when (result) {
                                            is VoskModelManager.DownloadResult.Ok ->
                                                snackbar.showSnackbar(context.getString(R.string.vosk_install_success))
                                            is VoskModelManager.DownloadResult.Failed ->
                                                downloadError = "Download failed: ${result.reason}"
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CosmicAccent,
                                    contentColor   = AiriTheme.onSurface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.vosk_download_small_btn),
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            PorcupineCard(
                status         = porcupineStatus,
                accessKeyInput = accessKeyInput,
                showKey        = showKey,
                onKeyChange    = { accessKeyInput = it },
                onShowToggle   = { showKey = !showKey },
                onSave = {
                    PorcupineEngine.setRuntimeAccessKey(context, accessKeyInput)
                    accessKeyInput = ""
                    porcupineStatus = PorcupineEngine.status(context)
                },
                onClear = {
                    PorcupineEngine.setRuntimeAccessKey(context, null)
                    porcupineStatus = PorcupineEngine.status(context)
                }
            )
            InstalledModelsCard(
                installed = installed,
                activeId  = activeId,
                onActivate = { id -> VoskModelManager.setActive(context, id) },
                onDelete   = { id -> VoskModelManager.delete(context, id) }
            )
            CloudVoiceCard(
                context  = context,
                onSnackbar = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun VoiceStatusCard(
    porcupineStatus: PorcupineEngine.Status,
    owwStatus:       OpenWakeWordEngine.Status,
    voskReady:       Boolean
) {
    val wakeWordReady   = owwStatus.ready || porcupineStatus.ready
    val sttReady        = voskReady
    val vadReady        = voskReady
    val ttsReady        = true
    val liveReady       = false

    Surface(
        shape    = AIRIShapes.md,
        color    = AiriTheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.voice_system_section), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
            StatusRow("Wake Word", wakeWordReady)
            StatusRow("Speech-to-Text", sttReady)
            StatusRow("Voice Activity Detection", vadReady)
            StatusRow("Text-to-Speech", ttsReady)
            StatusRow("Live Duplex", liveReady)
        }
    }
}

@Composable
private fun StatusRow(label: String, isReady: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
        Text(
            if (isReady) "READY" else "NOT READY",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isReady) SemanticSuccess else SemanticError
        )
    }
}

@Composable
private fun PorcupineCard(
    status: PorcupineEngine.Status,
    accessKeyInput: String,
    showKey: Boolean,
    onKeyChange: (String) -> Unit,
    onShowToggle: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        shape = AIRIShapes.md,
        color = AiriTheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.voice_porcupine_legacy), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
            if (status.ready) {
                Text(stringResource(R.string.voice_access_key_active), color = SemanticSuccess, fontSize = 12.sp)
                Button(onClick = onClear, colors = ButtonDefaults.buttonColors(containerColor = SemanticError)) {
                    Text(stringResource(R.string.voice_clear_access_key))
                }
            } else {
                OutlinedTextField(
                    value = accessKeyInput,
                    onValueChange = onKeyChange,
                    label = { Text(stringResource(R.string.voice_access_key_label)) },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.voice_save_access_key))
                }
            }
        }
    }
}

@Composable
private fun InstalledModelsCard(
    installed: List<VoskModelManager.Installed>,
    activeId: String?,
    onActivate: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Surface(
        shape = AIRIShapes.md,
        color = AiriTheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.voice_vosk_stt_models), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
            for (model in installed) {
                val isActive = model.id == activeId
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(model.displayName, fontSize = 13.sp, color = AiriTheme.onBackground)
                    Row {
                        if (!isActive) {
                            TextButton(onClick = { onActivate(model.id) }) {
                                Text(stringResource(R.string.voice_activate))
                            }
                        }
                        IconButton(onClick = { onDelete(model.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.voice_delete_wake_word_cd), tint = SemanticError)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudVoiceCard(
    context: android.content.Context,
    onSnackbar: (String) -> Unit
) {
    Surface(
        shape = AIRIShapes.md,
        color = AiriTheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.voice_cloud_realtime_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
            Text(stringResource(R.string.voice_cloud_realtime_desc), fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
        }
    }
}
