package com.airi.assistant.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.speech.SpeechRecognizer
import com.airi.assistant.system.DefaultAssistantManager
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
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.system.LanguageManager
import com.airi.assistant.system.LanguageOption
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.domain.monetization.PaywallTriggerEngine
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.components.PremiumBadge
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
    val backgroundAgentEnabled by viewModel.backgroundAgentEnabled.collectAsState()
    val currentLanguage = remember { mutableStateOf(LanguageManager.getCurrentLanguage(context)) }

    var customInstructions by rememberSaveable { mutableStateOf(systemPrompt) }
    var responseStyle by rememberSaveable { mutableStateOf(responseStyleState) }
    var themeMode by rememberSaveable { mutableStateOf(themeModeState) }
    val voicePrefs = remember { context.getSharedPreferences("airi_voice", Context.MODE_PRIVATE) }
    var voiceEnabled by rememberSaveable { mutableStateOf(voicePrefs.getBoolean("voice_enabled", false)) }
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
                    voicePrefs.edit().putBoolean("voice_enabled", enabled).apply()
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.speech_engine),
                        color = if (isSpeechAvailable) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.35f),
                        fontSize = 13.sp
                    )
                    Text(
                        if (isSpeechAvailable) stringResource(R.string.speech_engine_value) else "Not installed",
                        color = if (isSpeechAvailable) CosmicAccent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.3f),
                        fontSize = 12.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.wake_word),
                        color = if (isSpeechAvailable) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.35f),
                        fontSize = 13.sp
                    )
                    Text(
                        if (isSpeechAvailable) stringResource(R.string.wake_word_value) else "Requires voice engine",
                        color = if (isSpeechAvailable) CosmicAccent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.3f),
                        fontSize = 12.sp
                    )
                }
            }

            SkillsSection(viewModel = viewModel)

            AgentSection(
                isEnabled  = backgroundAgentEnabled,
                onToggle   = { viewModel.setBackgroundAgentEnabled(it) },
                onNavigate = onNavigate,
                isPremium  = viewModel.isPremium()
            )

            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Speed, title = "Performance")
                Spacer(Modifier.height(8.dp))
                SettingsNavigationRow(
                    label    = "Performance & Device Info",
                    sublabel = "RAM, CPU, token speed, performance mode"
                ) { onNavigate(AiriRoute.PERFORMANCE) }
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

            SubscriptionSection(viewModel = viewModel, onNavigate = onNavigate)

            DefaultAssistantSection(activity = activity)

            ObservabilitySection(onNavigate = onNavigate)

            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Info, title = stringResource(R.string.app_info))
                Spacer(Modifier.height(8.dp))
                SettingsInfoRow(stringResource(R.string.name), stringResource(R.string.app_full_name))
                SettingsInfoRow(stringResource(R.string.version), stringResource(R.string.app_version_value))
                SettingsInfoRow(stringResource(R.string.engine), stringResource(R.string.engine_value))
                SettingsInfoRow(stringResource(R.string.ui), stringResource(R.string.ui_value))
                SettingsInfoRow(stringResource(R.string.database), stringResource(R.string.database_value))
                SettingsInfoRow(stringResource(R.string.auth), stringResource(R.string.auth_value))
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 8.dp))
                SettingsActionRow(
                    label    = stringResource(R.string.report_a_problem),
                    sublabel = stringResource(R.string.report_a_problem_sublabel)
                ) {
                    val deviceInfo = buildString {
                        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                        append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                        append("App version: ${context.getString(R.string.app_version_value)}")
                    }
                    val body = "Please describe the issue:\n\n\n\n--- Device Info ---\n$deviceInfo"
                    val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        putExtra(Intent.EXTRA_EMAIL, arrayOf("xwenbrr@gmail.com"))
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.report_email_subject))
                        putExtra(Intent.EXTRA_TEXT, body)
                    }
                    runCatching { context.startActivity(emailIntent) }
                }
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

@Composable
private fun SkillsSection(viewModel: ChatViewModel) {
    val skillInfos = remember { viewModel.getSkillInfos().toMutableStateList() }

    SettingsSurface {
        SettingsCategoryHeader(
            icon = Icons.Outlined.AutoAwesome,
            title = "Skills"
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "High-level capabilities powered by your connected integrations",
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.padding(bottom = 10.dp)
        )

        skillInfos.forEachIndexed { index, info ->
            if (index > 0) {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.05f),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = skillDisplayName(info.name),
                            color = if (info.isConnected) Color.White.copy(alpha = 0.88f)
                            else Color.White.copy(alpha = 0.35f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(6.dp))
                        if (!info.isConnected) {
                            Text(
                                text = "Not connected",
                                color = Color(0xFFFF6B6B).copy(alpha = 0.65f),
                                fontSize = 10.sp
                            )
                        }
                    }
                    Text(
                        text = info.description,
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = info.isEnabled && info.isConnected,
                    onCheckedChange = { enabled ->
                        if (info.isConnected) {
                            skillInfos[index] = info.copy(isEnabled = enabled)
                            viewModel.setSkillEnabled(info.name, enabled)
                        }
                    },
                    enabled = info.isConnected,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CosmicAccent,
                        checkedTrackColor = CosmicAccent.copy(alpha = 0.3f),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.4f),
                        uncheckedTrackColor = Color.White.copy(alpha = 0.1f),
                        disabledCheckedThumbColor = Color.White.copy(alpha = 0.2f),
                        disabledUncheckedThumbColor = Color.White.copy(alpha = 0.15f),
                        disabledCheckedTrackColor = Color.White.copy(alpha = 0.08f),
                        disabledUncheckedTrackColor = Color.White.copy(alpha = 0.05f)
                    )
                )
            }
        }
    }
}

@Composable
private fun AgentSection(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onNavigate: (String) -> Unit = {},
    isPremium: Boolean = false
) {
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences("airi_ui_state", Context.MODE_PRIVATE) }
    val lastRun = remember { prefs.getLong("bg_agent_last_run", 0L) }
    val lastSummary = remember { prefs.getString("bg_agent_last_result", null) }
    val lastRunFormatted = remember(lastRun) {
        if (lastRun == 0L) "Never"
        else java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                 .format(java.util.Date(lastRun))
    }

    SettingsSurface {
        SettingsCategoryHeader(
            icon  = Icons.Outlined.SmartToy,
            title = "Background Agent"
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text  = "Run intelligent background checks on GitHub and Gmail while you're away",
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.padding(bottom = 10.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text  = "Enable Background Agent",
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (!isPremium) PremiumBadge()
                }
                Text(
                    text  = if (!isPremium) "Requires Premium — upgrade to unlock"
                            else if (isEnabled) "Runs every 2 hours when connected"
                            else "Background checks are off",
                    color = if (!isPremium) Color(0xFFFFB300).copy(alpha = 0.6f)
                            else if (isEnabled) CosmicAccent.copy(alpha = 0.65f)
                            else Color.White.copy(alpha = 0.35f),
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { enabled ->
                    if (!isPremium) {
                        AnalyticsService.premiumFeatureAttempted("background_agent")
                        if (PaywallTriggerEngine.onPremiumFeatureAttempt()) onNavigate(AiriRoute.PAYWALL)
                    } else {
                        onToggle(enabled)
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor   = CosmicAccent,
                    checkedTrackColor   = CosmicAccent.copy(alpha = 0.3f),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.4f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                )
            )
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 8.dp))
        SettingsInfoRow(label = stringResource(R.string.last_run), value = lastRunFormatted)
        if (!lastSummary.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text  = lastSummary,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 8.dp))
        SettingsNavigationRow(
            label    = stringResource(R.string.agent_logs),
            sublabel = stringResource(R.string.agent_logs_description),
            onClick  = { onNavigate(AiriRoute.AGENT_LOGS) }
        )
        SettingsNavigationRow(
            label    = stringResource(R.string.agent_control),
            sublabel = stringResource(R.string.agent_control_description),
            onClick  = { onNavigate(AiriRoute.AGENT_CONTROL) }
        )
    }
}

@Composable
private fun SubscriptionSection(
    viewModel: ChatViewModel,
    onNavigate: (String) -> Unit = {}
) {
    val summary   = remember { viewModel.getSubscriptionSummary() }
    val isPremium = remember { viewModel.isPremium() }

    SettingsSurface {
        SettingsCategoryHeader(
            icon  = Icons.Outlined.Star,
            title = stringResource(R.string.subscription)
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text       = if (isPremium) stringResource(R.string.plan_premium) else stringResource(R.string.plan_free_tier),
                    color      = if (isPremium) CosmicAccent else Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp
                )
                Text(
                    text     = if (isPremium) stringResource(R.string.plan_premium_description) else stringResource(R.string.plan_free_description),
                    color    = Color.White.copy(alpha = 0.42f),
                    fontSize = 11.sp
                )
            }
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = if (isPremium) CosmicAccent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isPremium) CosmicAccent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.15f)
                )
            ) {
                Text(
                    text     = if (isPremium) stringResource(R.string.plan_badge_active) else stringResource(R.string.plan_badge_free),
                    color    = if (isPremium) CosmicAccent else Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 10.dp))

        Text(stringResource(R.string.today_usage), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))

        val limit = if (isPremium) "∞" else null
        SettingsInfoRow(stringResource(R.string.usage_messages),  "${summary.messagesUsed} / ${limit ?: summary.messagesLimit}")
        SettingsInfoRow(stringResource(R.string.usage_agent_runs),"${summary.agentsUsed} / ${limit ?: summary.agentsLimit}")
        SettingsInfoRow(stringResource(R.string.usage_skill_uses),"${summary.skillsUsed} / ${limit ?: summary.skillsLimit}")

        if (!isPremium) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 10.dp))
            Button(
                onClick = { onNavigate(AiriRoute.PAYWALL) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.White),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.upgrade_premium_price), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DefaultAssistantSection(activity: Activity?) {
    val context   = LocalContext.current
    val isDefault = remember { DefaultAssistantManager.isDefaultAssistant(context) }

    SettingsSurface {
        SettingsCategoryHeader(
            icon  = Icons.Outlined.Assistant,
            title = stringResource(R.string.default_assistant)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text     = stringResource(R.string.default_assistant_description),
            fontSize = 11.sp,
            color    = Color.White.copy(alpha = 0.38f),
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(12.dp))

        if (isDefault) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint     = CosmicAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.airi_is_default), color = CosmicAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text     = stringResource(R.string.airi_not_default),
                    color    = Color(0xFFFF9800).copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
                Button(
                    onClick = {
                        if (activity != null) {
                            DefaultAssistantManager.requestDefaultAssistant(activity)
                        } else {
                            DefaultAssistantManager.openAssistantSettings(context)
                        }
                    },
                    colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.Black),
                    shape    = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.set_as_default_assistant), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                TextButton(
                    onClick  = { DefaultAssistantManager.openAssistantSettings(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.open_assistant_settings), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ObservabilitySection(onNavigate: (String) -> Unit) {
    SettingsSurface {
        SettingsCategoryHeader(
            icon  = Icons.Outlined.Timeline,
            title = stringResource(R.string.observability)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text     = stringResource(R.string.observability_description),
            fontSize = 11.sp,
            color    = Color.White.copy(alpha = 0.38f)
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 10.dp))
        SettingsNavigationRow(
            label    = stringResource(R.string.execution_history),
            sublabel = stringResource(R.string.execution_history_description),
            onClick  = { onNavigate(AiriRoute.OBSERVABILITY) }
        )
    }
}

private fun skillDisplayName(name: String): String = when (name) {
    "github_guardian"    -> "GitHub Guardian"
    "telegram_messenger" -> "Telegram Messenger"
    "gmail_assistant"    -> "Gmail Assistant"
    "drive_search"       -> "Drive Search"
    "calendar_events"    -> "Calendar Events"
    else                 -> name.replace("_", " ").split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
