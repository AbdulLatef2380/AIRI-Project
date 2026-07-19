package com.airi.assistant.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import com.airi.assistant.system.LanguageManager
import com.airi.assistant.system.LanguageOption
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(onBack: () -> Unit) {
    val context         = LocalContext.current
    val activity        = context.findActivity()
    val currentLanguage = remember { mutableStateOf(LanguageManager.getCurrentLanguage(context)) }
    var pendingLanguage by remember { mutableStateOf<LanguageOption?>(null) }

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
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.65f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Text(stringResource(R.string.settings_general), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
                }
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
                SettingsCategoryHeader(
                    icon  = Icons.Outlined.Language,
                    title = stringResource(R.string.language)
                )
                Spacer(Modifier.height(8.dp))
                SettingsInfoRow(
                    stringResource(R.string.ui_language),
                    LanguageManager.getLanguageOption(currentLanguage.value).displayName
                )
                Spacer(Modifier.height(8.dp))
                LanguageSelector(
                    selectedLanguage   = currentLanguage.value,
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
                SettingsInfoRow(
                    stringResource(R.string.model_language),
                    stringResource(R.string.model_language_prompt_controlled)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.recommended_english),
                    fontSize = 11.sp,
                    color    = CosmicAccent.copy(alpha = 0.65f)
                )
            }

            DefaultAssistantSection(activity = activity)

            // : Reset to Defaults section
            // Uses PreferenceCoordinator.resetAllToDefaults() which now clears
            // exec prefs, voice prefs, theme prefs, and all model paths.
            var showResetConfirm by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            SettingsSurface {
                SettingsCategoryHeader(
                    icon  = Icons.Outlined.RestartAlt,
                    title = "Reset to Defaults"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Resets all execution, voice, theme, and model preferences to factory defaults. " +
                    "Conversation history is not deleted.",
                    fontSize = 12.sp,
                    color    = AiriTheme.onSurfaceVariant.copy(0.7f),
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { showResetConfirm = true },
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = com.airi.assistant.ui.theme.SemanticWarn.copy(0.18f),
                        contentColor   = com.airi.assistant.ui.theme.SemanticWarn
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_reset_all), fontWeight = FontWeight.SemiBold)
                }
            }

            if (showResetConfirm) {
                AlertDialog(
                    onDismissRequest = { showResetConfirm = false },
                    containerColor   = MaterialTheme.colorScheme.surface,
                    textContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(20.dp),
                    title = { Text(stringResource(R.string.settings_reset_dialog_title), fontWeight = FontWeight.Bold) },
                    text  = { Text(stringResource(R.string.settings_reset_dialog_body)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                showResetConfirm = false
                                com.airi.assistant.core.ServiceLocator.preferenceCoordinator
                                    .resetAllToDefaults()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = com.airi.assistant.ui.theme.SemanticWarn,
                                contentColor   = MaterialTheme.colorScheme.background
                            )
                        ) { Text(stringResource(R.string.settings_reset_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetConfirm = false }) {
                            Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
                        }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    pendingLanguage?.let { language ->
        AlertDialog(
            onDismissRequest  = { pendingLanguage = null },
            containerColor    = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            shape             = RoundedCornerShape(20.dp),
            title = {
                Text(
                    stringResource(R.string.performance_notice_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text           = { Text(stringResource(R.string.performance_notice_message)) },
            confirmButton  = {
                Button(
                    onClick = {
                        LanguageManager.markPerformanceWarningShown(context, language.code)
                        pendingLanguage = null
                        applySelectedLanguage(language)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CosmicAccent,
                        contentColor   = MaterialTheme.colorScheme.background
                    )
                ) { Text(stringResource(R.string.continue_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLanguage = null }) {
                    Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
                }
            }
        )
    }
}
