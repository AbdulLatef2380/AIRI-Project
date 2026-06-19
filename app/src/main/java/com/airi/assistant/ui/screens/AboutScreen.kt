package com.airi.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.BuildConfig
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SurfaceRaised
import com.airi.assistant.ui.theme.AiriTheme
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_airi_title), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color(0xFF070C1A)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.app_name), fontSize = 48.sp, fontWeight = FontWeight.Bold, color = CosmicAccent)
            Text(
                "Autonomous AI Operating System",
                fontSize = 16.sp, color = AiriTheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                fontSize = 13.sp, color = AiriTheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Divider(color = AiriTheme.outline.copy(alpha = 0.08f))
            AboutInfoCard("Runtime", "Local-first hybrid inference with llama.cpp GGUF runtime and cloud failover via Anthropic, Gemini, and OpenAI adapters.")
            AboutInfoCard("Voice", "On-device STT via Vosk. Wake-word detection via Porcupine. Full-duplex VAD for barge-in interruption.")
            AboutInfoCard("Privacy", "All local inference stays on-device. Cloud requests pass through a privacy sanitisation gate.")
            AboutInfoCard("Stack", "Kotlin · Jetpack Compose · Coroutines · StateFlow · Room · JNI/NDK · llama.cpp")
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.copyright_notice), fontSize = 12.sp, color = AiriTheme.outline)
        }
    }
}

@Composable
private fun AboutInfoCard(title: String, body: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = AiriTheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CosmicAccent)
            Text(body, fontSize = 13.sp, color = AiriTheme.onSurfaceVariant, lineHeight = 19.sp)
        }
    }
}
