package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.airi.assistant.R
import com.airi.assistant.voice.PorcupineEngine
import com.airi.assistant.voice.VoskModelManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { VoskModelManager.refreshInstalled(context) }

    val installed by VoskModelManager.installed.collectAsState()
    val activeId  by VoskModelManager.activeModelId.collectAsState()

    var porcupineStatus by remember { mutableStateOf(PorcupineEngine.status(context)) }
    var accessKeyInput  by remember { mutableStateOf("") }
    var inFlightId      by remember { mutableStateOf<String?>(null) }
    var progress        by remember { mutableStateOf(0) }
    var showPorcupine   by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                stringResource(R.string.voice_settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Primary: Vosk installed models (works out of the box) ─────
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.MicNone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.vosk_section_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        stringResource(R.string.vosk_section_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (installed.isEmpty()) {
                        Text(
                            stringResource(R.string.vosk_no_models),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        installed.forEach { m ->
                            val isActive = m.id == activeId
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.displayName, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${m.locale} • ${m.sizeBytes / (1024 * 1024)} MB" +
                                            if (isActive) " • " + stringResource(R.string.vosk_active) else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!isActive) {
                                    TextButton(onClick = { VoskModelManager.setActive(context, m.id) }) {
                                        Text(stringResource(R.string.vosk_set_active))
                                    }
                                } else {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF2BB673),
                                        modifier = Modifier.size(20.dp).padding(end = 4.dp),
                                    )
                                }
                                IconButton(onClick = { VoskModelManager.delete(context, m.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.vosk_delete))
                                }
                            }
                            Divider()
                        }
                    }
                }
            }

            // ── Vosk available presets ────────────────────────────────────
            VoskModelManager.PRESETS.forEach { preset ->
                val isInstalled  = installed.any { it.id == preset.id }
                val isDownloading = inFlightId == preset.id
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(preset.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            preset.locale + " • " + stringResource(R.string.vosk_size_mb, preset.sizeMb),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (preset.sha256 == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Info, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "No pinned checksum — downloaded as-is.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (isDownloading) {
                            LinearProgressIndicator(
                                progress = progress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${stringResource(R.string.vosk_downloading)} $progress%",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            Button(
                                enabled = !isInstalled && inFlightId == null,
                                onClick = {
                                    inFlightId = preset.id
                                    progress   = 0
                                    scope.launch {
                                        val res = VoskModelManager.downloadAndInstall(
                                            context    = context,
                                            preset     = preset,
                                            onProgress = { progress = it },
                                        )
                                        inFlightId = null
                                        when (res) {
                                            is VoskModelManager.DownloadResult.Ok ->
                                                snackbar.showSnackbar("${res.installed.displayName} ✓")
                                            is VoskModelManager.DownloadResult.Failed ->
                                                snackbar.showSnackbar(
                                                    context.getString(R.string.vosk_download_failed, res.reason)
                                                )
                                        }
                                    }
                                },
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isInstalled) stringResource(R.string.vosk_installed)
                                    else stringResource(R.string.vosk_download)
                                )
                            }
                        }
                    }
                }
            }

            // ── Optional: Porcupine wake-word (collapsible) ───────────────
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.porcupine_section_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Optional — voice recognition works without this.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showPorcupine = !showPorcupine }) {
                            Text(if (showPorcupine) "Hide" else "Configure")
                        }
                    }

                    // Status badge (always visible, concise)
                    val st = porcupineStatus
                    val (icon, tint, msg) = when {
                        st.ready -> Triple(Icons.Filled.CheckCircle, Color(0xFF2BB673),
                            stringResource(R.string.porcupine_status_ready))
                        !st.accessKeyPresent && !st.ppnPresent -> Triple(
                            Icons.Filled.Info, MaterialTheme.colorScheme.onSurfaceVariant,
                            stringResource(R.string.porcupine_status_both_missing))
                        !st.accessKeyPresent -> Triple(
                            Icons.Filled.Warning, Color(0xFFE3A93B),
                            stringResource(R.string.porcupine_status_missing_key))
                        else -> Triple(
                            Icons.Filled.Warning, Color(0xFFE3A93B),
                            stringResource(R.string.porcupine_status_missing_ppn))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Expanded configuration section
                    if (showPorcupine) {
                        Divider()
                        if (st.ppnSourceLabel != null) {
                            Text(
                                "ppn: ${st.ppnSourceLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (st.accessKeySource != null) {
                            Text(
                                if (st.accessKeySource == "runtime")
                                    stringResource(R.string.porcupine_source_runtime)
                                else stringResource(R.string.porcupine_source_build),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = accessKeyInput,
                            onValueChange = { accessKeyInput = it },
                            label = { Text(stringResource(R.string.porcupine_access_key_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    PorcupineEngine.setRuntimeAccessKey(context, accessKeyInput)
                                    accessKeyInput  = ""
                                    porcupineStatus = PorcupineEngine.status(context)
                                },
                                enabled = accessKeyInput.isNotBlank(),
                            ) { Text(stringResource(R.string.porcupine_access_key_save)) }
                            OutlinedButton(onClick = {
                                PorcupineEngine.setRuntimeAccessKey(context, null)
                                porcupineStatus = PorcupineEngine.status(context)
                            }) { Text(stringResource(R.string.porcupine_access_key_clear)) }
                        }
                        Text(
                            stringResource(R.string.porcupine_help_link),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
