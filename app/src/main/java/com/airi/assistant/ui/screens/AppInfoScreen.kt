package com.airi.assistant.ui.screens

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
                    containerColor = Color.Black.copy(alpha = 0.65f)
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
            // ── App identity card ──────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CosmicAccent.copy(alpha = 0.07f))
                    .border(1.dp, CosmicAccent.copy(alpha = 0.22f), RoundedCornerShape(20.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.SmartToy,
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
                        shape = RoundedCornerShape(20.dp),
                        color = CosmicAccent.copy(alpha = 0.15f),
                        modifier = Modifier
                            .border(1.dp, CosmicAccent.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
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

            // ── Technical details ──────────────────────────────────────────────
            AboutCard(icon = Icons.Outlined.Info, title = "App Details") {
                AboutRow("Package",   "com.airi.assistant")
                AboutRow("Version",   "1.0")
                AboutRow("Engine",    "llama.cpp via JNI (local, on-device)")
                AboutRow("Interface", "Kotlin · Jetpack Compose")
                AboutRow("Database",  "Room (local SQLite)")
                AboutRow("Auth",      "Firebase Authentication")
                AboutRow("Runtime",   "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                AboutRow("Device",    "${Build.MANUFACTURER} ${Build.MODEL}")
            }

            // ── Privacy at a glance ────────────────────────────────────────────
            AboutCard(icon = Icons.Outlined.Shield, title = "Privacy") {
                Text(
                    "AIRI is designed with privacy first. All AI inference runs locally on your " +
                    "device by default — no conversation data is sent anywhere without your " +
                    "explicit permission.\n\n" +
                    "• Local mode: fully offline, zero network access\n" +
                    "• Cloud mode: only activated when you configure an API key and grant internet permission\n" +
                    "• API keys stored in EncryptedSharedPreferences (AES-256 GCM)\n" +
                    "• Chat history stored only on this device\n" +
                    "• No analytics or telemetry sent without your consent",
                    color      = AiriTheme.onBackground.copy(alpha = 0.72f),
                    fontSize   = 13.sp,
                    lineHeight = 20.sp
                )
            }

            // ── Terms of Use ───────────────────────────────────────────────────
            AboutCard(icon = Icons.Outlined.Gavel, title = "Terms of Use") {
                TermsSection("1. Acceptance") {
                    "By using AIRI you agree to these terms. If you do not agree, please uninstall the app."
                }
                TermsSection("2. What AIRI Is") {
                    "AIRI is a local-first AI assistant that runs large language models entirely on " +
                    "your Android device. It is provided for personal, non-commercial use. AIRI is " +
                    "not a medical, legal, or financial advisor."
                }
                TermsSection("3. AI Limitations") {
                    "Responses are generated by machine learning models and may be inaccurate, " +
                    "outdated, or inappropriate. Always verify important information from authoritative " +
                    "sources. Do not rely on AIRI for safety-critical decisions."
                }
                TermsSection("4. User Responsibilities") {
                    "You are responsible for the prompts you provide and how you use AI responses. " +
                    "Do not use AIRI to generate harmful, illegal, deceptive, or abusive content. " +
                    "You must comply with all applicable local laws."
                }
                TermsSection("5. Data & Privacy") {
                    "In local mode, no data leaves your device. If you choose to enable cloud AI " +
                    "providers (OpenAI, Anthropic, Google), your messages are sent to those services " +
                    "under their respective privacy policies. Your Firebase account email is stored " +
                    "securely via Firebase Authentication."
                }
                TermsSection("6. Model Files") {
                    "LLM model files (GGUF format) are downloaded by you from third-party sources. " +
                    "AIRI does not distribute models. You are responsible for ensuring any model " +
                    "you use complies with its license."
                }
                TermsSection("7. No Warranty") {
                    "AIRI is provided \"as is\" without warranties of any kind. We do not guarantee " +
                    "uptime, accuracy, or fitness for any particular purpose."
                }
                TermsSection("8. Limitation of Liability") {
                    "To the maximum extent permitted by law, the AIRI team is not liable for any " +
                    "direct, indirect, incidental, or consequential damages arising from your use " +
                    "of this app."
                }
                TermsSection("9. Changes") {
                    "These terms may be updated. Continued use after changes constitutes acceptance."
                }
                TermsSection("10. Contact") {
                    "Questions or concerns? Reach us at: xwenbrr@gmail.com"
                }
            }

            // ── Open-source acknowledgements ──────────────────────────────────
            AboutCard(icon = Icons.Outlined.Code, title = "Open-Source Acknowledgements") {
                Text(
                    "AIRI is built on the shoulders of open-source giants:",
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

            // ── Report a problem ──────────────────────────────────────────────
            SettingsSurface {
                SettingsCategoryHeader(icon = Icons.Outlined.BugReport, title = "Support")
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    label    = "Report a Problem",
                    sublabel = "Send feedback or bug report by email"
                ) {
                    val deviceInfo = "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                                     "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                                     "App version: 1.0"
                    val body = "Please describe the issue:\n\n\n\n--- Device Info ---\n$deviceInfo"
                    val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        putExtra(Intent.EXTRA_EMAIL,   arrayOf("xwenbrr@gmail.com"))
                        putExtra(Intent.EXTRA_SUBJECT, "[AIRI] Bug Report")
                        putExtra(Intent.EXTRA_TEXT,    body)
                    }
                    runCatching { context.startActivity(emailIntent) }
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

// ─────────────────────────────────────────────────────────────────────────────
// Private helpers for AboutScreen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AboutCard(
    icon:    ImageVector,
    title:   String,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
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
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
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
