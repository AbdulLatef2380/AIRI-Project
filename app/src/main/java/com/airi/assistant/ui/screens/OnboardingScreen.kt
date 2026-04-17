package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.domain.growth.OnboardingManager
import com.airi.assistant.ui.theme.CosmicAccent

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit
) {
    var page by remember { mutableStateOf(0) }
    val pages = remember {
        listOf(
            OnboardingPage(Icons.Outlined.SmartToy, "AIRI can chat, automate tasks, and act as your AI assistant", "Your private assistant helps with conversations, app actions, and useful work on device."),
            OnboardingPage(Icons.Outlined.Mic, "Use voice, agents, and smart tools", "Speak naturally, run agents, and connect tools when you need AIRI to do more than reply."),
            OnboardingPage(Icons.Outlined.CheckCircle, "Get started instantly", "Sign in, choose a model, and start building your AI workflow.")
        )
    }

    LaunchedEffect(Unit) {
        OnboardingManager.start()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF050816), Color(0xFF101633), Color(0xFF050816))))
            .padding(24.dp)
    ) {
        TextButton(
            onClick = {
                OnboardingManager.skip()
                onSkip()
            },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Text("Skip", color = Color.White.copy(alpha = 0.7f))
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(CosmicAccent.copy(alpha = 0.14f))
                    .border(1.5.dp, CosmicAccent.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(pages[page].icon, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(42.dp))
            }

            Text(
                text = pages[page].title,
                color = Color.White,
                fontSize = 27.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center
            )

            Text(
                text = pages[page].subtitle,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )

            SocialProofStrip()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .width(if (index == page) 28.dp else 8.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (index == page) CosmicAccent else Color.White.copy(alpha = 0.18f))
                    )
                }
            }
        }

        Button(
            onClick = {
                if (page < pages.lastIndex) {
                    page += 1
                } else {
                    OnboardingManager.complete()
                    onComplete()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.Black)
        ) {
            Text(if (page < pages.lastIndex) "Continue" else "Get Started", fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun SocialProofStrip() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(16.dp))
        Text("Used by AI enthusiasts", color = Color.White.copy(alpha = 0.76f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val subtitle: String
)