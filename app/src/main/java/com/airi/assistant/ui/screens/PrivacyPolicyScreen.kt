package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.CosmicAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.65f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = {
                    Text("Privacy Policy", fontWeight = FontWeight.Bold, color = Color.White)
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LegalCard {
                LegalSectionTitle("Last Updated: April 17, 2026")
                Spacer(Modifier.height(4.dp))
                LegalBody(
                    "AIRI (Android Artificial Intelligence Runtime Interface) is committed to protecting your privacy. This policy explains what data we collect, how we use it, and your rights."
                )
            }

            LegalCard {
                LegalSectionTitle("1. Data We Collect")
                Spacer(Modifier.height(8.dp))
                LegalSubTitle("Authentication Data")
                LegalBody("When you create an account, we collect your email address via Firebase Authentication. If you sign in with Google, we receive your name, email, and Google profile ID.")
                Spacer(Modifier.height(8.dp))
                LegalSubTitle("On-Device AI Processing")
                LegalBody("All AI inference runs locally on your device using llama.cpp. Your conversations, commands, and AI responses are stored in the local Room database on your device only. No conversation data is sent to external servers.")
                Spacer(Modifier.height(8.dp))
                LegalSubTitle("Crash Reports")
                LegalBody("We use Firebase Crashlytics to collect anonymous crash reports and error logs to improve app stability. This data does not include your conversations.")
                Spacer(Modifier.height(8.dp))
                LegalSubTitle("Integration Data")
                LegalBody("If you connect third-party integrations (GitHub, Google, Telegram), your API tokens are stored securely in EncryptedSharedPreferences on your device. AIRI only accesses these services when you explicitly request it.")
            }

            LegalCard {
                LegalSectionTitle("2. How We Use Your Data")
                Spacer(Modifier.height(8.dp))
                LegalBody("• Email: For account authentication and email verification only")
                LegalBody("• Conversations: Stored locally for your session history and AI context memory")
                LegalBody("• Crash data: To identify and fix bugs (anonymous, no personal content)")
                LegalBody("• Integration tokens: To execute actions you explicitly request")
            }

            LegalCard {
                LegalSectionTitle("3. Authentication (Google / Firebase)")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "We use Google Firebase Authentication to manage user accounts securely. When you sign in with Google, authentication is handled entirely by Google's OAuth 2.0 flow. We do not store your password — Firebase handles credential management. Your Google account data is governed by Google's Privacy Policy."
                )
            }

            LegalCard {
                LegalSectionTitle("4. AI Processing Disclaimer")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "AIRI processes all AI tasks on-device using locally stored language models (GGUF format). No query, conversation, or AI output leaves your device during inference. Your prompts are never sent to cloud AI services."
                )
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "When using third-party skills (Gmail, Calendar, GitHub, Telegram), AIRI connects to those services' APIs directly from your device. These connections are governed by the respective service's privacy policy."
                )
            }

            LegalCard {
                LegalSectionTitle("5. Data Storage and Security")
                Spacer(Modifier.height(8.dp))
                LegalBody("• All local data is stored in your device's private app storage")
                LegalBody("• API tokens are encrypted using Android's EncryptedSharedPreferences (AES-256)")
                LegalBody("• No conversation data is uploaded to external servers")
                LegalBody("• You can delete all local data at any time from Settings → Clear All Memory")
            }

            LegalCard {
                LegalSectionTitle("6. Your Rights")
                Spacer(Modifier.height(8.dp))
                LegalBody("• Access: Your data is stored locally and accessible in the app")
                LegalBody("• Delete: Clear all memory from Settings, or delete your account")
                LegalBody("• Control: Disable background agent, disconnect integrations at any time")
                LegalBody("• Export: Export your chat history from Settings → Export Chats")
            }

            LegalCard {
                LegalSectionTitle("7. Children's Privacy")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "AIRI is not intended for use by children under 13. We do not knowingly collect personal information from children."
                )
            }

            LegalCard {
                LegalSectionTitle("8. Contact")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "For privacy questions, contact the developer through the app's GitHub repository or support channel."
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LegalCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(16.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun LegalSectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, color = CosmicAccent, fontSize = 14.sp)
}

@Composable
private fun LegalSubTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
}

@Composable
private fun LegalBody(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 20.sp)
}
