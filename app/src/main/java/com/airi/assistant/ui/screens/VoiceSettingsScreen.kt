package com.airi.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import com.airi.assistant.voice.VoiceCapabilityPolicy
import com.airi.assistant.voice.VoskModelManager
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
    var downloadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        VoskModelManager.refreshInstalled(context)
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
                            stringResource(R.string.voice_personalization_title),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = AiriTheme.onBackground
                        )
                        Text(
                            stringResource(R.string.voice_personalization_shortcut_desc),
                            fontSize = 12.sp, color = AiriTheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = AiriTheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
            }
            VoiceStatusCard(
                porcupineStatus = porcupineStatus,
                owwStatus = owwStatus,
                voskReady = VoskModelManager.isReady(context),
                microphoneGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
                        if (downloadFailed) {
                            Text(
                                text = stringResource(R.string.vosk_download_failed_generic),
                                color = SemanticError,
                                fontSize = 12.sp
                            )
                        }
                        if (downloadProgress != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinearProgressIndicator(
                                    progress = ((downloadProgress ?: 0) / 100f),
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 4.dp, topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 6.dp)),
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
                                    downloadFailed = false
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
                                                downloadFailed = true
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
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun VoiceStatusCard(
    porcupineStatus: PorcupineEngine.Status,
    owwStatus: OpenWakeWordEngine.Status,
    voskReady: Boolean,
    microphoneGranted: Boolean
) {
    val capability = VoiceCapabilityPolicy.snapshot(
        wakeWordConfigured = owwStatus.ready || porcupineStatus.ready,
        activeSpeechModelReady = voskReady,
        microphoneGranted = microphoneGranted
    )

    Surface(
        shape    = AIRIShapes.md,
        color    = AiriTheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.voice_system_section), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
            StatusRow(R.string.voice_status_wake_word, capability.wakeWordReady)
            StatusRow(R.string.voice_status_speech_recognition, capability.speechRecognitionReady)
            StatusRow(R.string.voice_status_voice_activity_detection, capability.voiceActivityDetectionReady)
            StatusRow(R.string.voice_status_live_duplex, capability.liveDuplexReady)
        }
    }
}

@Composable
private fun StatusRow(@androidx.annotation.StringRes label: Int, isReady: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(label), fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
        Text(
            stringResource(if (isReady) R.string.voice_status_ready else R.string.voice_status_unavailable),
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
