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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.viewmodel.ChatViewModel
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIModelsSettingsScreen(
    viewModel:  ChatViewModel,
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit
) {
    val executionMode     by viewModel.executionMode.collectAsState()
    val historicalStats    by viewModel.tokenAccountant.historicalStats.collectAsState()
    val execPrefs         = remember { viewModel.getExecModePrefs() }
    var privacyLevel      by remember { mutableStateOf(execPrefs.privacyLevel) }
    var internetGranted   by remember { mutableStateOf(execPrefs.internetPermissionGranted) }
    var offlineFallback   by remember { mutableStateOf(execPrefs.offlineFallbackEnabled) }
    var preferredProvider by remember { mutableStateOf(execPrefs.preferredProvider) }

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
                    Text(stringResource(R.string.settings_group_ai_models), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
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
            SkillsSection(viewModel = viewModel)

            ApiKeysSection(onNavigate = onNavigate)

            ExecutionModePanel(
                currentMode             = executionMode,
                currentPrivacy          = privacyLevel,
                internetGranted         = internetGranted,
                offlineFallback         = offlineFallback,
                preferredProvider       = preferredProvider,
                cloudTokensUsed         = execPrefs.cloudTokensUsedToday,
                cloudTokensCap          = execPrefs.maxDailyCloudTokens,
                historicalStats          = historicalStats,
                onModeChange            = { viewModel.setExecutionMode(it) },
                onPrivacyChange         = { privacyLevel = it; viewModel.setPrivacyLevel(it) },
                onInternetPermChange    = { internetGranted = it; viewModel.grantInternetPermission(it) },
                onOfflineFallbackChange = { offlineFallback = it; execPrefs.offlineFallbackEnabled = it },
                onProviderChange        = { preferredProvider = it; execPrefs.preferredProvider = it }
            )

            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Memory, title = stringResource(R.string.local_model_title))
                Spacer(Modifier.height(8.dp))
                SettingsNavigationRow(
                    label    = stringResource(R.string.model_settings_title),
                    sublabel = stringResource(R.string.local_model_desc)
                ) { onNavigate(AiriRoute.MODELS) }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
