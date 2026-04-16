package com.airi.assistant.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.speech.SpeechRecognizer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.system.LanguageManager
import com.airi.assistant.system.LanguageOption
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.util.ChatExporter
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val user = remember { FirebaseAuth.getInstance().currentUser }
    val email = user?.email ?: "guest"
    val initial = email.firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val responseStyleState by viewModel.responseStyle.collectAsState()
    val themeModeState by viewModel.themeMode.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val currentLanguage = remember { mutableStateOf(LanguageManager.getCurrentLanguage(context)) }

    var customInstructions by rememberSaveable { mutableStateOf(systemPrompt) }
    var responseStyle by rememberSaveable { mutableStateOf(responseStyleState) }
    var themeMode by rememberSaveable { mutableStateOf(themeModeState) }
    var voiceEnabled by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingLanguage by remember { mutableStateOf<LanguageOption?>(null) }

    val isSpeechAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }

    val responseStyles = listOf(
        "concise" to stringResource(R.string.style_concise),
        "balanced" to stringResource(R.string.style_balanced),
        "detailed" to stringResource(R.string.style_detailed)
    )
    val themeOptions = listOf(
        "dark" to stringResource(R.string.theme_dark),
        "light" to stringResource(R.string.theme_light),
        "system" to stringResource(R.string.theme_system)
    )

    fun applySelectedLanguage(language: LanguageOption) {
        currentLanguage.value = language.code
        if (activity != null) {
            LanguageManager.applyLanguage(activity, language.code)
        } else {
            LanguageManager.saveLanguage(context, language.code)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                    }
                },
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold, color = Color.White) }
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
            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Person, title = stringResource(R.string.profile))
                Spacer(Modifier.height(12.dp))
                Surface(
                    onClick = { onNavigate(AiriRoute.PROFILE) },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(email, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(stringResource(R.string.firebase_account), color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                        }
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.3f))
                    }
                }
            }

            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Psychology, title = stringResource(R.string.personalization))
                Spacer(Modifier.height(12.dp))

                Text(stringResource(R.string.custom_instructions), fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = customInstructions,
                    onValueChange = {
                        customInstructions = it
                        viewModel.setSystemPrompt(it)
                    },
                    placeholder = { Text(stringResource(R.string.custom_instructions_placeholder), color = Color.White.copy(alpha = 0.28f), fontSize = 12.sp) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CosmicAccent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Text(stringResource(R.string.applied_next_session), fontSize = 10.sp, color = Color.White.copy(alpha = 0.3f), modifier = Modifier.padding(top = 4.dp))

                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.response_style), fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    responseStyles.forEach { (code, label) ->
                        val selected = responseStyle == code
                        FilterChip(
                            selected = selected,
                            onClick = {
                                responseStyle = code
                                viewModel.setResponseStyle(code)
                            },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CosmicAccent.copy(alpha = 0.2f),
                                selectedLabelColor = CosmicAccent,
                                containerColor = Color.White.copy(alpha = 0.06f),
                                labelColor = Color.White.copy(alpha = 0.6f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = Color.White.copy(alpha = 0.1f),
                                selectedBorderColor = CosmicAccent.copy(alpha = 0.4f),
                                enabled = true,
                                selected = selected
                            )
                        )
                    }
                }
            }

            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Storage, title = stringResource(R.string.memory))
                Spacer(Modifier.height(12.dp))
                SettingsNavigationRow(label = stringResource(R.string.view_stored_memory), sublabel = stringResource(R.string.browse_conversation_history)) {
                    onNavigate(AiriRoute.MEMORY)
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 8.dp))
                SettingsActionRow(label = stringResource(R.string.clear_all_memory), sublabel = stringResource(R.string.reset_ai_context), destructive = true) {
                    viewModel.clearMemory()
                }
            }

            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Palette, title = stringResource(R.string.appearance))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.theme), fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    themeOptions.forEach { (code, label) ->
                        val selected = themeMode == code
                        FilterChip(
                            selected = selected,
                            onClick = {
                                themeMode = code
                                viewModel.setThemeMode(code)
                            },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CosmicAccent.copy(alpha = 0.2f),
                                selectedLabelColor = CosmicAccent,
                                containerColor = Color.White.copy(alpha = 0.06f),
                                labelColor = Color.White.copy(alpha = 0.6f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = Color.White.copy(alpha = 0.1f),
                                selectedBorderColor = CosmicAccent.copy(alpha = 0.4f),
                                enabled = true,
                                selected = selected
                            )
                        )
                    }
                }
            }

            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Language, title = stringResource(R.string.language))
                Spacer(Modifier.height(8.dp))
                SettingsInfoRow(stringResource(R.string.ui_language), LanguageManager.getLanguageOption(currentLanguage.value).displayName)
                Spacer(Modifier.height(8.dp))
                LanguageSelector(
                    selectedLanguage = currentLanguage.value,
                    onLanguageSelected = { language ->
                        if (language.code == currentLanguage.value) return@LanguageSelector
                        if (LanguageManager.shouldShowPerformanceWarning(context, language.code)) {
                            pendingLanguage = language
                        } else {
                            applySelectedLanguage(language)
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                SettingsInfoRow(stringResource(R.string.model_language), stringResource(R.string.model_language_prompt_controlled))
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.recommended_english), fontSize = 11.sp, color = CosmicAccent.copy(alpha = 0.65f))
            }

            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Mic, title = stringResource(R.string.voice))
                Spacer(Modifier.height(8.dp))
                SettingsSwitchRow(stringResource(R.string.enable_voice_mode), voiceEnabled) { enabled ->
                    voiceEnabled = enabled
                }
                if (voiceEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isSpeechAvailable)
                            stringResource(R.string.voice_recognition_ready)
                        else
                            stringResource(R.string.voice_engine_not_installed),
                        fontSize = 11.sp,
                        color = if (isSpeechAvailable) CosmicAccent.copy(alpha = 0.7f) else Color(0xFFFF6B6B).copy(alpha = 0.8f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                SettingsInfoRow(stringResource(R.string.speech_engine), stringResource(R.string.speech_engine_value))
                SettingsInfoRow(stringResource(R.string.wake_word), stringResource(R.string.wake_word_value))
            }

            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Security, title = stringResource(R.string.data_controls))
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    label = stringResource(R.string.export_chats),
                    sublabel = stringResource(R.string.download_chat_history)
                ) {
                    scope.launch {
                        val success = ChatExporter.exportToJson(context, messages)
                        snackbarHost.showSnackbar(
                            if (success) context.getString(R.string.export_success)
                            else context.getString(R.string.export_failed)
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 8.dp))
                SettingsActionRow(label = stringResource(R.string.clear_chat_history), sublabel = stringResource(R.string.remove_from_display)) {
                    viewModel.clearMessages()
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 8.dp))
                SettingsActionRow(label = stringResource(R.string.delete_account), sublabel = stringResource(R.string.delete_account_sublabel), destructive = true) {
                    showDeleteDialog = true
                }
            }

            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Info, title = stringResource(R.string.app_info))
                Spacer(Modifier.height(8.dp))
                SettingsInfoRow(stringResource(R.string.name), stringResource(R.string.app_full_name))
                SettingsInfoRow(stringResource(R.string.version), stringResource(R.string.app_version_value))
                SettingsInfoRow(stringResource(R.string.engine), stringResource(R.string.engine_value))
                SettingsInfoRow(stringResource(R.string.ui), stringResource(R.string.ui_value))
                SettingsInfoRow(stringResource(R.string.database), stringResource(R.string.database_value))
                SettingsInfoRow(stringResource(R.string.auth), stringResource(R.string.auth_value))
            }

            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A2E)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.4f))
            ) {
                Icon(Icons.Outlined.Logout, contentDescription = null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sign_out), color = Color(0xFFFF6B6B))
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    pendingLanguage?.let { language ->
        AlertDialog(
            onDismissRequest = { pendingLanguage = null },
            containerColor = Color(0xFF12162E),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.75f),
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.performance_notice_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.performance_notice_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        LanguageManager.markPerformanceWarningShown(context, language.code)
                        pendingLanguage = null
                        applySelectedLanguage(language)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.Black)
                ) { Text(stringResource(R.string.continue_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLanguage = null }) {
                    Text(stringResource(R.string.cancel), color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color(0xFF12162E),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.7f),
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.delete_account), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.delete_account_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        user?.delete()?.addOnCompleteListener { onLogout() }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC2222))
                ) { Text(stringResource(R.string.delete_account)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel), color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }
}

@Composable
private fun LanguageSelector(
    selectedLanguage: String,
    onLanguageSelected: (LanguageOption) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LanguageManager.supportedLanguages.forEach { language ->
            val selected = selectedLanguage == language.code
            Surface(
                onClick = { onLanguageSelected(language) },
                color = if (selected) CosmicAccent.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) CosmicAccent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(language.displayName, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                    if (selected) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

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
                checkedThumbColor = CosmicAccent,
                checkedTrackColor = CosmicAccent.copy(alpha = 0.3f),
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
        color = Color.Transparent,
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
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.3f))
        }
    }
}

@Composable
fun SettingsActionRow(label: String, sublabel: String, destructive: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
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
                contentDescription = null,
                tint = if (destructive) Color(0xFFFF6B6B).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.25f)
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
