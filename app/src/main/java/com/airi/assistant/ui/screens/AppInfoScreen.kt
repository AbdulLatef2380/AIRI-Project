package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

@Composable
fun AppInfoScreen(onBack: () -> Unit) = AppInfoScreenContent(onBack)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoScreenContent(onBack: () -> Unit) {
    val context = LocalContext.current

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
                    Text(stringResource(R.string.about_airi_title), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AIRIShapes.xl)
                    .background(CosmicAccent.copy(alpha = 0.07f))
                    .border(1.dp, CosmicAccent.copy(alpha = 0.22f), AIRIShapes.xl)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Memory,
                        contentDescription = null,
                        tint     = CosmicAccent,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "AIRI",
                        color      = AiriTheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 26.sp
                    )
                    Text(
                        "Android Artificial Intelligence Runtime Interface",
                        color     = AiriTheme.onBackground.copy(alpha = 0.5f),
                        fontSize  = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = AIRIShapes.xl,
                        color = CosmicAccent.copy(alpha = 0.15f),
                        modifier = Modifier
                            .border(1.dp, CosmicAccent.copy(alpha = 0.4f), AIRIShapes.xl)
                    ) {
                        Text(
                            "Version 1.0",
                            color    = CosmicAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            AboutCard(icon = Icons.Outlined.Info, title = stringResource(R.string.app_info)) {
                AboutRow(stringResource(R.string.name),   "com.airi.assistant")
                AboutRow(stringResource(R.string.version),   "1.0")
                AboutRow(stringResource(R.string.engine),    "llama.cpp via JNI (local, on-device)")
                AboutRow(stringResource(R.string.ui), "Kotlin · Jetpack Compose")
                AboutRow(stringResource(R.string.database),  "Room (local SQLite)")
                AboutRow(stringResource(R.string.auth),      "Firebase Authentication")
                AboutRow(stringResource(R.string.about_runtime_title),   "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                AboutRow(stringResource(R.string.about_device),    "${Build.MANUFACTURER} ${Build.MODEL}")
            }
            AboutCard(icon = Icons.Outlined.Shield, title = stringResource(R.string.about_privacy_title)) {
                Text(stringResource(R.string.about_privacy_full), color = AiriTheme.onBackground.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 20.sp)
            }
            AboutCard(icon = Icons.Outlined.Gavel, title = stringResource(R.string.about_terms)) {
                Text(stringResource(R.string.about_terms_full), color = AiriTheme.onBackground.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 20.sp)
            }
            AboutCard(icon = Icons.Outlined.Code, title = stringResource(R.string.about_acknowledgements)) {
                Text(
                    stringResource(R.string.about_acknowledgements_intro),
                    color    = AiriTheme.onBackground.copy(alpha = 0.55f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                val oss = listOf(
                    "llama.cpp"           to "MIT License — Georgi Gerganov",
                    "Vosk"                to "Apache 2.0 — Alpha Cephei",
                    "Silero VAD"          to "Apache 2.0 — snakers4",
                    "Jetpack Compose"     to "Apache 2.0 — Google",
                    "Firebase"            to "Firebase TOS — Google",
                    "Coil"                to "Apache 2.0 — coil-kt",
                    "OkHttp"              to "Apache 2.0 — Square"
                )
                oss.forEach { (lib, license) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(lib,     color = AiriTheme.onBackground.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(license, color = AiriTheme.onBackground.copy(alpha = 0.38f), fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            Text(
                "© 2025 AIRI Project. All rights reserved.",
                color     = AiriTheme.onBackground.copy(alpha = 0.22f),
                fontSize  = 11.sp,
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}
// Private helpers for AboutScreen
@Composable
private fun AboutCard(
    icon:    ImageVector,
    title:   String,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AIRIShapes.lg)
            .background(AiriTheme.onSurface.copy(alpha = 0.05f))
            .border(1.dp, AiriTheme.outline, AIRIShapes.lg)
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, color = CosmicAccent, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, color = AiriTheme.onBackground.copy(alpha = 0.45f), fontSize = 13.sp)
        Text(value, color = AiriTheme.onBackground.copy(alpha = 0.78f), fontSize = 13.sp)
    }
}

@Composable
private fun TermsSection(heading: String, body: () -> String) {
    Spacer(Modifier.height(8.dp))
    Text(heading, color = CosmicAccent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    Spacer(Modifier.height(3.dp))
    Text(body(), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
}

// Legacy composables kept for backward compatibility if any old route still references them.
// The canonical entry point is now AboutScreen.
@Composable
fun AppInfoSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(AIRIShapes.md)
            .background(AiriTheme.onSurface.copy(alpha = 0.05f))
            .border(1.dp, AiriTheme.outline, AIRIShapes.md)
            .padding(16.dp)
    ) {
        Column {
            Text(title, color = CosmicAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = CosmicAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(value, color = AiriTheme.onBackground.copy(alpha = 0.75f), fontSize = 12.sp)
    }
}
