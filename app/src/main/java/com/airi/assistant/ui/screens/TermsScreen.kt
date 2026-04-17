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
fun TermsScreen(onBack: () -> Unit) {
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
                    Text("Terms of Service", fontWeight = FontWeight.Bold, color = Color.White)
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
                    "By using AIRI, you agree to these Terms of Service. Please read them carefully."
                )
            }

            LegalCard {
                LegalSectionTitle("1. Acceptance of Terms")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "By installing or using AIRI, you agree to be bound by these Terms of Service and our Privacy Policy. If you do not agree, do not use the application."
                )
            }

            LegalCard {
                LegalSectionTitle("2. Description of Service")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "AIRI is an on-device AI assistant for Android that performs local AI inference using language models. It may also interact with third-party services (GitHub, Google, Telegram) through optional integrations you configure."
                )
            }

            LegalCard {
                LegalSectionTitle("3. User Responsibilities")
                Spacer(Modifier.height(8.dp))
                LegalBody("• You are responsible for all actions performed by AIRI on your behalf")
                LegalBody("• Do not use AIRI for illegal, harmful, or deceptive purposes")
                LegalBody("• You are responsible for the AI model files you load into AIRI")
                LegalBody("• When using integrations, you must comply with those services' own Terms of Service")
                LegalBody("• Keep your account credentials secure")
            }

            LegalCard {
                LegalSectionTitle("4. AI Disclaimer")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "AIRI uses on-device language models that may produce inaccurate, incomplete, or inappropriate responses. AI outputs are not professional advice (medical, legal, financial, etc.). Always verify important information from authoritative sources."
                )
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "The Accessibility Service feature allows AIRI to interact with other apps on your device. You grant this permission voluntarily and remain responsible for reviewing AIRI's actions before confirming sensitive operations."
                )
            }

            LegalCard {
                LegalSectionTitle("5. Third-Party Integrations")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "AIRI may connect to third-party services including GitHub, Google (Gmail, Calendar, Drive), and Telegram. These integrations are optional and governed by the respective services' terms and privacy policies. AIRI is not affiliated with or endorsed by these services."
                )
            }

            LegalCard {
                LegalSectionTitle("6. Limitation of Liability")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "AIRI is provided 'AS IS' without warranty of any kind. We are not liable for any damages arising from the use of AIRI, including but not limited to data loss, unintended actions taken by the agent, or service interruptions."
                )
            }

            LegalCard {
                LegalSectionTitle("7. Intellectual Property")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "AIRI is released under the MIT License. AI models loaded into AIRI are subject to their own individual licenses. You are responsible for complying with the license terms of any model you use."
                )
            }

            LegalCard {
                LegalSectionTitle("8. Account Termination")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "You may delete your account at any time from Settings → Delete Account. We reserve the right to terminate accounts that violate these Terms."
                )
            }

            LegalCard {
                LegalSectionTitle("9. Changes to Terms")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "We may update these Terms from time to time. Continued use of AIRI after changes constitutes acceptance of the new Terms."
                )
            }

            LegalCard {
                LegalSectionTitle("10. Governing Law")
                Spacer(Modifier.height(8.dp))
                LegalBody(
                    "These Terms are governed by applicable law. Any disputes shall be resolved in accordance with the developer's jurisdiction."
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
