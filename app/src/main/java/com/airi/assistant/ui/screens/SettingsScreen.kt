package com.airi.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var darkMode by rememberSaveable { mutableStateOf(true) }
    var arabic by rememberSaveable { mutableStateOf(true) }
    var compactMode by rememberSaveable { mutableStateOf(false) }
    var glassEffect by rememberSaveable { mutableStateOf(true) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                )
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
            SettingsCategory(title = "Appearance") {
                SettingsSwitchRow("Dark mode", darkMode) { darkMode = it }
                SettingsSwitchRow("Glass depth", glassEffect) { glassEffect = it }
                SettingsSwitchRow("Compact layout", compactMode) { compactMode = it }
            }

            SettingsCategory(title = "Language") {
                SettingsSwitchRow("Arabic interface", arabic) { arabic = it }
                Text(
                    text = if (arabic) "Current language: Arabic" else "Current language: English",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsCategory(title = "App Info") {
                SettingsInfoLine("Name", "Android Artificial Intelligence Runtime Interface")
                SettingsInfoLine("Version", "1.0")
                SettingsInfoLine("Package", "com.airi.assistant")
                SettingsInfoLine("UI", "Kotlin + Jetpack Compose + Navigation Compose")
                SettingsInfoLine("Inference", "LlamaManager + ModelManager + GGUF local files")
                SettingsInfoLine("Storage", "SharedPreferences for active model path, Room for memory")
            }
        }
    }
}

@Composable
fun SettingsCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun SettingsSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingsInfoLine(
    label: String,
    value: String
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(value)
    }
}
