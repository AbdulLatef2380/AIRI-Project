package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.theme.*
import com.airi.assistant.voice.PorcupineEngine
import com.airi.assistant.voice.VoskModelManager

/**
 * VoiceSettingsScreen — manages AIRI's bundled voice stack.
 *
 * What is shown:
 *  - Voice system status overview (all features enabled by default)
 *  - Wake-word (Porcupine) access-key management
 *  - Installed Vosk model management (activate / delete)
 *
 * What is NOT shown (intentionally removed):
 *  - External model download cards — voice models are internally bundled
 *  - Feature locks / restrictions — Live Chat, Duplex, VAD are always enabled
 *  - Manual download progress — managed automatically by VoskModelManager
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { VoskModelManager.refreshInstalled(context) }

    val installed by VoskModelManager.installed.collectAsState()
    val activeId  by VoskModelManager.activeModelId.collectAsState()
    var porcupineStatus by remember { mutableStateOf(PorcupineEngine.status(context)) }
    var accessKeyInput  by remember { mutableStateOf("") }
    var showKey         by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Voice Settings", color = Color.White, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        snackbarHost   = { SnackbarHost(snackbar) },
        containerColor = CosmicBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── System status — all features always enabled ──────────────
            VoiceStatusCard()

            // ── Porcupine wake-word ──────────────────────────────────────
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

            // ── Installed Vosk models ────────────────────────────────────
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

// ── Voice system status card ───────────────────────────────────────────────────
@Composable
private fun VoiceStatusCard() {
    Surface(
        shape  = RoundedCornerShape(14.dp),
        color  = SurfaceRaised,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Voice System", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(
                "AIRI voice stack is fully bundled and automatically configured. " +
                "All voice features are always available.",
                fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f), lineHeight = 17.sp
            )
            Spacer(Modifier.height(2.dp))
            val features = listOf(
                "Wake word detection (Porcupine)" to true,
                "On-device STT (Vosk)"           to true,
                "Voice activity detection (VAD)" to true,
                "Text-to-speech (TTS)"           to true,
                "Live / duplex conversation"     to true,
                "Barge-in interruption"          to true
            )
            features.forEach { (label, enabled) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier.size(8.dp).clip(CircleShape)
                            .background(if (enabled) SemanticSuccess else SemanticError)
                    )
                    Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }
}

// ── Porcupine access key card ─────────────────────────────────────────────────
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
    Surface(shape = RoundedCornerShape(14.dp), color = SurfaceRaised, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Mic, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
                Text("Wake Word (Porcupine)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }

            // Status indicator
            val (tint, statusText) = when {
                status.ready           -> SemanticSuccess to "Ready — \"Hey AIRI\" active"
                status.accessKeyPresent -> SemanticWarn   to "Access key set — PPN file missing"
                else                    -> SemanticWarn   to "Access key required"
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.10f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Icon(
                    if (status.ready) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null, tint = tint, modifier = Modifier.size(14.dp)
                )
                Text(statusText, fontSize = 12.sp, color = tint)
            }

            // Access key input
            OutlinedTextField(
                value             = accessKeyInput,
                onValueChange     = onKeyChange,
                label             = { Text("Porcupine access key", fontSize = 12.sp) },
                singleLine        = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions   = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = onShowToggle) {
                        Icon(
                            if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showKey) "Hide" else "Show",
                            tint = Color.White.copy(alpha = 0.5f)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = CosmicAccent.copy(alpha = 0.6f),
                    unfocusedBorderColor = DividerColor,
                    focusedTextColor     = Color.White,
                    unfocusedTextColor   = Color.White
                )
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick  = onSave,
                    enabled  = accessKeyInput.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent.copy(alpha = 0.85f)),
                    shape    = RoundedCornerShape(10.dp)
                ) { Text("Save key", fontSize = 13.sp) }

                OutlinedButton(
                    onClick = onClear,
                    shape   = RoundedCornerShape(10.dp),
                    border  = androidx.compose.foundation.BorderStroke(1.dp, DividerColor)
                ) { Text("Clear", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f)) }
            }

            Text(
                "Get a free access key at console.picovoice.io",
                fontSize = 11.sp,
                color    = CosmicAccent.copy(alpha = 0.7f)
            )
        }
    }
}

// ── Installed Vosk models ─────────────────────────────────────────────────────
@Composable
private fun InstalledModelsCard(
    installed: List<VoskModelManager.Installed>,
    activeId: String?,
    onActivate: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Surface(shape = RoundedCornerShape(14.dp), color = SurfaceRaised, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
                Text("Installed Models", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }

            if (installed.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No models installed. Copy a Vosk model folder into the app's files directory under \"vosk/\".",
                        fontSize = 12.sp,
                        color    = Color.White.copy(alpha = 0.4f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                installed.forEach { model ->
                    val isActive = model.id == activeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isActive) CosmicAccent.copy(alpha = 0.10f)
                                else Color.White.copy(alpha = 0.03f)
                            )
                            .border(
                                0.5.dp,
                                if (isActive) CosmicAccent.copy(alpha = 0.30f) else DividerColor,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(model.displayName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                                if (isActive) {
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(SemanticSuccess.copy(alpha = 0.18f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) { Text("Active", fontSize = 9.sp, color = SemanticSuccess, fontWeight = FontWeight.SemiBold) }
                                }
                            }
                            Text(
                                "${model.locale} · ${model.sizeBytes / (1024 * 1024)} MB",
                                fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f)
                            )
                        }
                        if (!isActive) {
                            TextButton(
                                onClick = { onActivate(model.id) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("Activate", fontSize = 12.sp, color = CosmicAccent) }
                        }
                        IconButton(
                            onClick  = { onDelete(model.id) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete model",
                                tint = SemanticError.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        }
                    }
                    if (installed.last() != model) Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
