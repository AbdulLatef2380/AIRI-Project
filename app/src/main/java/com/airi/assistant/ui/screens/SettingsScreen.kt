package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.google.firebase.auth.FirebaseAuth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit
) {
    val user          = remember { FirebaseAuth.getInstance().currentUser }
    val email         = user?.email ?: "guest"
    val initial       = email.firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    val snackbarHost  = remember { SnackbarHostState() }
    val scope         = rememberCoroutineScope()

    val systemPrompt  by viewModel.systemPrompt.collectAsState()
    val temperature   by viewModel.temperature.collectAsState()

    var customInstructions by rememberSaveable { mutableStateOf(systemPrompt) }
    var responseStyle      by rememberSaveable { mutableStateOf("Balanced") }
    var darkModeEnabled    by rememberSaveable { mutableStateOf(true) }
    var voiceEnabled       by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog   by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost   = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                },
                title = { Text("Settings", fontWeight = FontWeight.Bold, color = Color.White) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── PROFILE ───────────────────────────────────────────────────
            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Person, title = "Profile")
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(CosmicAccent.copy(alpha = 0.18f))
                            .border(1.5.dp, CosmicAccent.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initial, color = CosmicAccent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(email, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text("Firebase account", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                    }
                }
            }

            // ── PERSONALIZATION ───────────────────────────────────────────
            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Psychology, title = "Personalization")
                Spacer(Modifier.height(12.dp))

                Text("Custom Instructions", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value         = customInstructions,
                    onValueChange = {
                        customInstructions = it
                        viewModel.setSystemPrompt(it)
                    },
                    placeholder   = { Text("e.g. Always respond concisely. Use Arabic.", color = Color.White.copy(alpha = 0.28f), fontSize = 12.sp) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = CosmicAccent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White
                    )
                )
                Text("Applied on next session start", fontSize = 10.sp, color = Color.White.copy(alpha = 0.3f), modifier = Modifier.padding(top = 4.dp))

                Spacer(Modifier.height(14.dp))
                Text("Response Style", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Concise", "Balanced", "Detailed").forEach { style ->
                        val selected = responseStyle == style
                        FilterChip(
                            selected = selected,
                            onClick  = { responseStyle = style },
                            label    = { Text(style, fontSize = 12.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor     = CosmicAccent.copy(alpha = 0.2f),
                                selectedLabelColor         = CosmicAccent,
                                containerColor             = Color.White.copy(alpha = 0.06f),
                                labelColor                 = Color.White.copy(alpha = 0.6f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor         = Color.White.copy(alpha = 0.1f),
                                selectedBorderColor = CosmicAccent.copy(alpha = 0.4f),
                                enabled = true,
                                selected = selected
                            )
                        )
                    }
                }
            }

            // ── MEMORY ───────────────────────────────────────────────────
            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Storage, title = "Memory")
                Spacer(Modifier.height(12.dp))
                SettingsNavigationRow(label = "View Stored Memory", sublabel = "Browse all conversation history") {
                    onNavigate(AiriRoute.MEMORY)
                }
                Divider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 8.dp))
                SettingsActionRow(label = "Clear All Memory", sublabel = "Reset AI context (irreversible)", destructive = true) {
                    viewModel.clearMemory()
                }
            }

            // ── APPEARANCE ───────────────────────────────────────────────
            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Palette, title = "Appearance")
                Spacer(Modifier.height(8.dp))
                SettingsSwitchRow("Dark theme (always on)", darkModeEnabled) { darkModeEnabled = it }
                Text("Light theme coming in next release", fontSize = 11.sp, color = Color.White.copy(alpha = 0.3f))
            }

            // ── LANGUAGE ────────────────────────────────────────────────
            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Language, title = "Language")
                Spacer(Modifier.height(8.dp))
                SettingsInfoRow("UI Language", "System default (Arabic / English)")
                SettingsInfoRow("Model Language", "Controlled by system prompt above")
                Spacer(Modifier.height(6.dp))
                Text("Full RTL language switching coming soon.", fontSize = 11.sp, color = Color.White.copy(alpha = 0.3f))
            }

            // ── VOICE ────────────────────────────────────────────────────
            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Mic, title = "Voice")
                Spacer(Modifier.height(8.dp))
                SettingsSwitchRow("Enable voice mode", voiceEnabled) { voiceEnabled = it }
                if (voiceEnabled) {
                    Text(
                        "Voice mode is prepared for Vosk (STT) and Picovoice wake word integration.",
                        fontSize = 11.sp,
                        color    = CosmicAccent.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                SettingsInfoRow("Speech engine", "Vosk — coming soon")
                SettingsInfoRow("Wake word", "\"Hey AIRI\" — coming soon")
            }

            // ── DATA CONTROLS ────────────────────────────────────────────
            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Security, title = "Data Controls")
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(label = "Export Chats", sublabel = "Download all chat history as text") {
                    scope.launch { snackbarHost.showSnackbar("Export coming in next update") }
                }
                Divider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 8.dp))
                SettingsActionRow(label = "Clear Chat History", sublabel = "Remove from display (not from DB)") {
                    viewModel.clearMessages()
                }
                Divider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 8.dp))
                SettingsActionRow(label = "Delete Account", sublabel = "Permanently remove your Firebase account", destructive = true) {
                    showDeleteDialog = true
                }
            }

            // ── APP INFO ────────────────────────────────────────────────
            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Info, title = "App Info")
                Spacer(Modifier.height(8.dp))
                SettingsInfoRow("Name", "AIRI — Android AI Runtime Interface")
                SettingsInfoRow("Version", "1.0.0-alpha")
                SettingsInfoRow("Engine", "llama.cpp via LlamaNative JNI")
                SettingsInfoRow("UI", "Jetpack Compose + Material 3")
                SettingsInfoRow("Database", "Room (episodic memory)")
                SettingsInfoRow("Auth", "Firebase Email/Password")
            }

            // ── SIGN OUT ────────────────────────────────────────────────
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A2E)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.4f))
            ) {
                Icon(Icons.Outlined.Logout, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign Out", color = Color(0xFFFF6B6B))
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest  = { showDeleteDialog = false },
            containerColor    = Color(0xFF12162E),
            titleContentColor = Color.White,
            textContentColor  = Color.White.copy(alpha = 0.7f),
            shape             = RoundedCornerShape(20.dp),
            title = { Text("Delete Account", fontWeight = FontWeight.Bold) },
            text  = { Text("This will permanently delete your Firebase account and sign you out. Your local model files and memory will NOT be deleted.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        user?.delete()?.addOnCompleteListener { onLogout() }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC2222))
                ) { Text("Delete Account") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = Color.White.copy(alpha = 0.6f)) }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REUSABLE COMPONENTS
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsSurface(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Column(content = content)
    }
}

@Composable
fun SettingsCategoryHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, color = CosmicAccent, fontSize = 13.sp)
    }
}

@Composable
fun SettingsSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor  = CosmicAccent,
                checkedTrackColor  = CosmicAccent.copy(alpha = 0.3f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.4f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
            )
        )
    }
}

@Composable
fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp)
        Text(value, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
    }
}

@Composable
fun SettingsNavigationRow(label: String, sublabel: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color   = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                Text(sublabel, color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = Color.White.copy(alpha = 0.3f))
        }
    }
}

@Composable
fun SettingsActionRow(label: String, sublabel: String, destructive: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color   = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(label, color = if (destructive) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                Text(sublabel, color = Color.White.copy(alpha = 0.35f), fontSize = 12.sp)
            }
            Icon(
                Icons.Outlined.ChevronRight,
                null,
                tint = if (destructive) Color(0xFFFF6B6B).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.25f)
            )
        }
    }
}
