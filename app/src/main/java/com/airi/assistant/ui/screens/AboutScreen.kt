package com.airi.assistant.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.BuildConfig
import com.airi.assistant.R
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    Scaffold(
        containerColor = AiriTheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.about_airi_title),
                        color      = AiriTheme.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // App identity block — uses the AIRI letterform orb (no robot icon)
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(AIRIShapes.xl)
                    .background(
                        Brush.radialGradient(
                            listOf(CosmicAccent.copy(0.28f), SurfaceRaised)
                        )
                    )
                    .border(1.dp, CosmicAccent.copy(0.35f), AIRIShapes.xl),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "A",
                    color      = CosmicAccent,
                    fontSize   = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-2).sp
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    fontSize   = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color      = AiriTheme.onBackground,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    stringResource(R.string.about_tagline),
                    fontSize  = 14.sp,
                    color     = AiriTheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Version chip row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VersionChip(label = stringResource(R.string.about_version), value = BuildConfig.VERSION_NAME)
                VersionChip(label = stringResource(R.string.about_build),   value = BuildConfig.VERSION_CODE.toString())
            }

            HorizontalDivider(color = DividerColor)

            // Info cards
            AboutInfoCard(
                icon   = Icons.Outlined.Memory,
                title  = stringResource(R.string.about_runtime_title),
                body   = "Local-first hybrid inference using llama.cpp GGUF runtime with cloud failover via Anthropic, Gemini, and OpenAI adapters."
            )
            AboutInfoCard(
                icon   = Icons.Outlined.Mic,
                title  = stringResource(R.string.about_voice_title),
                body   = "On-device speech recognition via Vosk. Wake-word detection via Porcupine. Full-duplex VAD for barge-in interruption during generation."
            )
            AboutInfoCard(
                icon   = Icons.Outlined.Lock,
                title  = stringResource(R.string.about_privacy_title),
                body   = "All local inference stays entirely on-device. Cloud requests pass through a privacy sanitisation gate before transmission."
            )
            AboutInfoCard(
                icon   = Icons.Outlined.Code,
                title  = stringResource(R.string.about_stack_title),
                body   = "Kotlin · Jetpack Compose · Coroutines · StateFlow · Room · JNI/NDK · llama.cpp · Material 3"
            )

            HorizontalDivider(color = DividerColor)

            // Navigation links
            AboutLinkRow(
                icon    = Icons.Outlined.Info,
                label   = stringResource(R.string.about_technical_details),
                onClick = { onNavigate(AiriRoute.APP_INFO) }
            )
            AboutLinkRow(
                icon    = Icons.Outlined.Description,
                label   = stringResource(R.string.about_licenses),
                onClick = { onNavigate(AiriRoute.APP_INFO) }
            )
            AboutLinkRow(
                icon    = Icons.Outlined.PrivacyTip,
                label   = stringResource(R.string.about_privacy),
                onClick = {}
            )

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.copyright_notice),
                fontSize  = 12.sp,
                color     = AiriTheme.outline,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun VersionChip(label: String, value: String) {
    Surface(
        shape  = AIRIShapes.xl,
        color  = SurfaceRaised,
        border = BorderStroke(0.5.dp, DividerColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(label, fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
            Text(value, fontSize = 11.sp, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AboutInfoCard(icon: ImageVector, title: String, body: String) {
    Surface(
        shape    = AIRIShapes.md,
        color    = SurfaceRaised,
        border   = BorderStroke(0.5.dp, DividerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(AIRIShapes.sm)
                    .background(CosmicAccent.copy(0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                Text(body,  fontSize = 13.sp, color = AiriTheme.onSurfaceVariant, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun AboutLinkRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick  = onClick,
        shape    = AIRIShapes.md,
        color    = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 14.sp, color = CosmicAccent, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}
