package com.airi.assistant.ui.screens

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
import com.airi.assistant.ui.theme.Surface0
import com.airi.assistant.ui.theme.Surface1
import com.airi.assistant.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIModelsSettingsScreen(
    viewModel:  ChatViewModel,
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit
) {
    val executionMode     by viewModel.executionMode.collectAsState()
    val execPrefs         = remember { viewModel.getExecModePrefs() }
    var privacyLevel      by remember { mutableStateOf(execPrefs.privacyLevel) }
    var internetGranted   by remember { mutableStateOf(execPrefs.internetPermissionGranted) }
    var offlineFallback   by remember { mutableStateOf(execPrefs.offlineFallbackEnabled) }
    var preferredProvider by remember { mutableStateOf(execPrefs.preferredProvider) }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Surface1
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = {
                    Text("AI & Models", fontWeight = FontWeight.Bold, color = Color.White)
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

            ApiKeysSection()

            ExecutionModePanel(
                currentMode             = executionMode,
                currentPrivacy          = privacyLevel,
                internetGranted         = internetGranted,
                offlineFallback         = offlineFallback,
                preferredProvider       = preferredProvider,
                cloudTokensUsed         = execPrefs.cloudTokensUsedToday,
                cloudTokensCap          = execPrefs.maxDailyCloudTokens,
                onModeChange            = { viewModel.setExecutionMode(it) },
                onPrivacyChange         = { privacyLevel = it; viewModel.setPrivacyLevel(it) },
                onInternetPermChange    = { internetGranted = it; viewModel.grantInternetPermission(it) },
                onOfflineFallbackChange = { offlineFallback = it; execPrefs.offlineFallbackEnabled = it },
                onProviderChange        = { preferredProvider = it; execPrefs.preferredProvider = it }
            )

            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.Memory, title = "Local Model")
                Spacer(Modifier.height(8.dp))
                SettingsNavigationRow(
                    label    = "Model Settings",
                    sublabel = "Configure local LLM, sampling parameters, and performance mode"
                ) { onNavigate(AiriRoute.MODELS) }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
