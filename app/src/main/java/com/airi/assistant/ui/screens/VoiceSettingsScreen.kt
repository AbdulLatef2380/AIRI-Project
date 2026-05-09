package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.voice.InternalWakeWordEngine
import com.airi.assistant.voice.NativeSttEngine
import com.airi.assistant.voice.VoskModelManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { VoskModelManager.refreshInstalled(context) }

    val installed by VoskModelManager.installed.collectAsState()
    val activeId  by VoskModelManager.activeModelId.collectAsState()

    var wakeWordStatus by remember { mutableStateOf(InternalWakeWordEngine.status(context)) }
    var inFlightId     by remember { mutableStateOf<String?>(null) }
    var progress       by remember { mutableStateOf(0) }

    // Refresh wake-word status when installed list changes
    LaunchedEffect(installed, activeId) {
        wakeWordStatus = InternalWakeWordEngine.status(context)
    }

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

            // ── Native Android STT (always available) ─────────────────────
            val nativeAvailable = remember { NativeSttEngine.isAvailable(context) }
            val prefs = remember { context.getSharedPreferences("airi_voice", android.content.Context.MODE_PRIVATE) }
            var sttLocale by remember { mutableStateOf(prefs.getString("stt_locale", "ar-SA") ?: "ar-SA") }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "النظام الصوتي الأساسي",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        if (nativeAvailable) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(com.airi.assistant.ui.theme.SemanticSuccess.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    "جاهز",
                                    color = com.airi.assistant.ui.theme.SemanticSuccess,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Text(
                        "Android SpeechRecognizer — يعمل فوراً على كل جهاز دون تنزيل نماذج. " +
                            "يدعم العربية أولاً مع احتياطي إنجليزي.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Divider()

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "لغة التعرف",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))
                        listOf("ar-SA" to "عربي", "en-US" to "English").forEach { (locale, label) ->
                            val selected = sttLocale == locale
                            Box(
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        sttLocale = locale
                                        prefs.edit().putString("stt_locale", locale).apply()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    label,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    if (!nativeAvailable) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "خدمة التعرف على الكلام غير متاحة على هذا الجهاز.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // ── Internal Wake Word status card ────────────────────────────
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Wake Word — \"Hey AIRI\"",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Text(
                        "Fully on-device keyword detection powered by Vosk. " +
                            "No API key, no cloud dependency, no proprietary SDK.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Divider()

                    val st = wakeWordStatus
                    val (statusIcon, statusTint, statusMsg) = if (st.ready) {
                        Triple(
                            Icons.Filled.CheckCircle,
                            Color(0xFF2BB673),
                            "Ready — using ${st.modelName ?: "active model"}"
                        )
                    } else {
                        Triple(
                            Icons.Filled.Warning,
                            MaterialTheme.colorScheme.error,
                            "No Vosk model installed — download one below to enable wake word"
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(statusIcon, contentDescription = null, tint = statusTint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            statusMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (st.ready) statusTint else MaterialTheme.colorScheme.error,
                        )
                    }

                    if (st.activeModelId != null) {
                        Text(
                            "Model ID: ${st.activeModelId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }

                    if (!st.ready) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Download a Vosk model below. Once installed and selected, " +
                                        "\"Hey AIRI\" detection activates automatically.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }

            // ── Vosk installed models ──────────────────────────────────────
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
                                    TextButton(onClick = {
                                        VoskModelManager.setActive(context, m.id)
                                        wakeWordStatus = InternalWakeWordEngine.status(context)
                                    }) {
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
                                IconButton(onClick = {
                                    VoskModelManager.delete(context, m.id)
                                    wakeWordStatus = InternalWakeWordEngine.status(context)
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.vosk_delete))
                                }
                            }
                            Divider()
                        }
                    }
                }
            }

            // ── Vosk available presets ─────────────────────────────────────
            VoskModelManager.PRESETS.forEach { preset ->
                val isInstalled   = installed.any { it.id == preset.id }
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
                                        inFlightId     = null
                                        wakeWordStatus = InternalWakeWordEngine.status(context)
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

            Spacer(Modifier.height(24.dp))
        }
    }
}
