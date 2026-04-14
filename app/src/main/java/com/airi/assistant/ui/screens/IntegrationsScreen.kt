package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.CosmicAccent
import com.google.firebase.auth.FirebaseAuth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsScreen(onBack: () -> Unit) {
    val user        = remember { FirebaseAuth.getInstance().currentUser }
    val firebaseEmail = user?.email ?: ""
    val isFirebaseConnected = user != null
    val snackbarHost = remember { SnackbarHostState() }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost   = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                },
                title = {
                    Column {
                        Text("Integrations", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Connect AIRI with external services", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
                    }
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
            // Info banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(CosmicAccent.copy(alpha = 0.1f), Color.Transparent)))
                    .border(1.dp, CosmicAccent.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text("Connected Services", fontWeight = FontWeight.Bold, color = CosmicAccent, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Integrations extend AIRI's capabilities. More services will be available as the platform grows.",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            // ── Active ──────────────────────────────────────────────────
            SectionLabel("Active")

            IntegrationCard(
                name        = "Firebase / Google",
                description = if (isFirebaseConnected) firebaseEmail else "Not signed in",
                statusLabel = if (isFirebaseConnected) "Connected" else "Disconnected",
                isConnected = isFirebaseConnected,
                category    = "Auth & Cloud",
                iconLetter  = "G",
                iconColor   = Color(0xFFEA4335),
                actionLabel = if (isFirebaseConnected) "Active" else "Sign In",
                actionEnabled = false
            )

            // ── Coming soon ─────────────────────────────────────────────
            SectionLabel("Coming Soon")

            IntegrationCard(
                name        = "GitHub",
                description = "Browse repos, open issues, read code directly in chat",
                statusLabel = "Not connected",
                isConnected = false,
                category    = "Developer",
                iconLetter  = "GH",
                iconColor   = Color(0xFFE0E0E0),
                actionLabel = "Connect",
                actionEnabled = false,
                comingSoon  = true
            )

            IntegrationCard(
                name        = "Telegram",
                description = "Send messages and receive notifications via Telegram bot",
                statusLabel = "Not connected",
                isConnected = false,
                category    = "Messaging",
                iconLetter  = "TG",
                iconColor   = Color(0xFF26A5E4),
                actionLabel = "Connect",
                actionEnabled = false,
                comingSoon  = true
            )

            IntegrationCard(
                name        = "n8n Automation",
                description = "Trigger and receive n8n workflows from chat",
                statusLabel = "Not connected",
                isConnected = false,
                category    = "Automation",
                iconLetter  = "N8",
                iconColor   = Color(0xFFEA5200),
                actionLabel = "Configure",
                actionEnabled = false,
                comingSoon  = true
            )

            IntegrationCard(
                name        = "Notion",
                description = "Read and write Notion pages and databases",
                statusLabel = "Not connected",
                isConnected = false,
                category    = "Productivity",
                iconLetter  = "NO",
                iconColor   = Color(0xFFE0E0E0),
                actionLabel = "Connect",
                actionEnabled = false,
                comingSoon  = true
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "All integrations run locally on your device — your data never leaves unless you explicitly enable cloud sync.",
                color = Color.White.copy(alpha = 0.3f),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label,
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun IntegrationCard(
    name: String,
    description: String,
    statusLabel: String,
    isConnected: Boolean,
    category: String,
    iconLetter: String,
    iconColor: Color,
    actionLabel: String,
    actionEnabled: Boolean,
    comingSoon: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = if (isConnected) 0.08f else 0.04f))
            .border(
                1.dp,
                if (isConnected) CosmicAccent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.15f))
                    .border(1.dp, iconColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(iconLetter, color = iconColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    if (comingSoon) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White.copy(alpha = 0.08f)
                        ) {
                            Text("soon", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp, color = Color.White.copy(alpha = 0.4f))
                        }
                    }
                }
                Text(category, fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f))
                Spacer(Modifier.height(4.dp))
                Text(description, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f), lineHeight = 17.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (isConnected) Color(0xFF00E676) else Color.White.copy(alpha = 0.2f))
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(statusLabel, fontSize = 11.sp, color = if (isConnected) Color(0xFF00E676) else Color.White.copy(alpha = 0.35f))
                    }
                    OutlinedButton(
                        onClick  = {},
                        enabled  = actionEnabled,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        border   = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isConnected) CosmicAccent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f)
                        ),
                        shape  = RoundedCornerShape(20.dp)
                    ) {
                        if (isConnected) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp), tint = CosmicAccent)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(actionLabel, fontSize = 12.sp, color = if (isConnected) CosmicAccent else Color.White.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}
