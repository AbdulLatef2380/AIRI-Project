package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.AiriTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.ThemeMode
import com.airi.assistant.ui.theme.ThemePreferences
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.profile.UserPreferenceProfileStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizationSettingsScreen(
    viewModel:  ChatViewModel,
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit
) {
    val context            = LocalContext.current
    val themePrefs         = remember { ThemePreferences.get(context) }
    val themeMode          by themePrefs.themeMode.collectAsState()
    val preferenceStore    = remember { UserPreferenceProfileStore(context.applicationContext) }
    val preferenceProfile  by preferenceStore.profile.collectAsState()

    val systemPrompt       by viewModel.systemPrompt.collectAsState()
    val responseStyleState by viewModel.responseStyle.collectAsState()
    var customInstructions by rememberSaveable { mutableStateOf(systemPrompt) }
    var responseStyle      by rememberSaveable { mutableStateOf(responseStyleState) }

    val responseStyles = listOf(
        "concise"  to stringResource(R.string.style_concise),
        "balanced" to stringResource(R.string.style_balanced),
        "detailed" to stringResource(R.string.style_detailed)
    )

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AiriTheme.background.copy(alpha = 0.65f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Text(stringResource(R.string.customization_title), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
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
                    icon  = Icons.Outlined.Palette,
                    title = stringResource(R.string.appearance)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.customization_display_mode),
                    fontSize = 13.sp,
                    color    = AiriTheme.onBackground.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val modes = listOf(
                        ThemeMode.DARK   to stringResource(R.string.theme_dark),
                        ThemeMode.LIGHT  to stringResource(R.string.theme_light),
                        ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                        ThemeMode.AMOLED to "AMOLED"   // : pure black for OLED
                    )
                    modes.forEach { (mode, label) ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick  = { themePrefs.mode = mode },
                            label    = { Text(label, fontSize = 12.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CosmicAccent.copy(alpha = 0.2f),
                                selectedLabelColor     = CosmicAccent,
                                containerColor         = AiriTheme.surfaceVariant,
                                labelColor             = AiriTheme.onSurface.copy(alpha = 0.6f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled             = true,
                                selected            = themeMode == mode,
                                borderColor         = AiriTheme.onSurface.copy(alpha = 0.1f),
                                selectedBorderColor = CosmicAccent.copy(alpha = 0.4f)
                            )
                        )
                    }
                }
            }

            SettingsSurface {
                Text(
                    stringResource(R.string.custom_instructions),
                    fontSize = 13.sp,
                    color    = AiriTheme.onBackground.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value         = customInstructions,
                    onValueChange = {
                        customInstructions = it
                        viewModel.setSystemPrompt(it)
                    },
                    placeholder = {
                        Text(
                            stringResource(R.string.custom_instructions_placeholder),
                            color    = AiriTheme.onBackground.copy(alpha = 0.28f),
                            fontSize = 12.sp
                        )
                    },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = CosmicAccent,
                        unfocusedBorderColor = AiriTheme.onSurface.copy(alpha = 0.12f),
                        focusedTextColor     = AiriTheme.onSurface,
                        unfocusedTextColor   = AiriTheme.onSurface
                    )
                )
                Text(
                    stringResource(R.string.applied_next_session),
                    fontSize = 10.sp,
                    color    = AiriTheme.onBackground.copy(alpha = 0.3f),
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.response_style),
                    fontSize = 13.sp,
                    color    = AiriTheme.onBackground.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    responseStyles.forEach { (code, label) ->
                        val selected = responseStyle == code
                        FilterChip(
                            selected = selected,
                            onClick  = {
                                responseStyle = code
                                viewModel.setResponseStyle(code)
                            },
                            label  = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CosmicAccent.copy(alpha = 0.2f),
                                selectedLabelColor     = CosmicAccent,
                                containerColor         = AiriTheme.surfaceVariant,
                                labelColor             = AiriTheme.onSurface.copy(alpha = 0.6f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled             = true,
                                selected            = selected,
                                borderColor         = AiriTheme.onSurface.copy(alpha = 0.1f),
                                selectedBorderColor = CosmicAccent.copy(alpha = 0.4f)
                            )
                        )
                    }
                }
            }

            SettingsSurface {
                SettingsCategoryHeader(
                    icon = Icons.Outlined.Person,
                    title = stringResource(R.string.personal_context_title)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.personal_context_privacy),
                    fontSize = 12.sp,
                    color = AiriTheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = preferenceProfile.workContext,
                    onValueChange = { value ->
                        preferenceStore.update { it.copy(workContext = value) }
                    },
                    label = { Text(stringResource(R.string.personal_context_work_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CosmicAccent,
                        unfocusedBorderColor = AiriTheme.onSurface.copy(alpha = 0.12f),
                        focusedTextColor = AiriTheme.onSurface,
                        unfocusedTextColor = AiriTheme.onSurface
                    )
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = preferenceProfile.currentGoal,
                    onValueChange = { value ->
                        preferenceStore.update { it.copy(currentGoal = value) }
                    },
                    label = { Text(stringResource(R.string.personal_context_goal_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CosmicAccent,
                        unfocusedBorderColor = AiriTheme.onSurface.copy(alpha = 0.12f),
                        focusedTextColor = AiriTheme.onSurface,
                        unfocusedTextColor = AiriTheme.onSurface
                    )
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.personal_context_share_label),
                            fontSize = 13.sp,
                            color = AiriTheme.onBackground
                        )
                        Text(
                            stringResource(R.string.personal_context_share_description),
                            fontSize = 11.sp,
                            color = AiriTheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = preferenceProfile.shareWithResponses,
                        onCheckedChange = { enabled ->
                            preferenceStore.update { it.copy(shareWithResponses = enabled) }
                        }
                    )
                }
            }

            SettingsSurface {
                SettingsCategoryHeader(
                    icon  = Icons.Outlined.Storage,
                    title = stringResource(R.string.memory)
                )
                Spacer(Modifier.height(12.dp))
                SettingsNavigationRow(
                    label    = stringResource(R.string.view_stored_memory),
                    sublabel = stringResource(R.string.browse_conversation_history)
                ) { onNavigate(AiriRoute.MEMORY) }
                Divider(
                    color    = AiriTheme.onBackground.copy(alpha = 0.06f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                SettingsActionRow(
                    label       = stringResource(R.string.clear_all_memory),
                    sublabel    = stringResource(R.string.reset_ai_context),
                    destructive = true
                ) { onNavigate(AiriRoute.MEMORY) }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
