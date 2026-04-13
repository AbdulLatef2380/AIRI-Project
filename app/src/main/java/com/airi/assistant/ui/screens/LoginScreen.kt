package com.airi.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.airi.assistant.ui.components.GlassCard
import com.airi.assistant.ui.theme.CosmicAccent

@Composable
fun LoginScreen(
    onLogin: (String, String, (String?) -> Unit) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(80.dp))

        AnimatedVisibility(visible) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassCard {
                    Text("🚀 سرعة فائقة", color = CosmicAccent)
                    Text("معالجة محلية بدون تأخير", color = Color.LightGray)
                }

                GlassCard {
                    Text("🛡️ خصوصية مطلقة", color = CosmicAccent)
                    Text("بياناتك تبقى داخل جهازك", color = Color.LightGray)
                }

                GlassCard {
                    Text("🧠 ذكاء متطور", color = CosmicAccent)
                    Text("نماذج حديثة تفهمك", color = Color.LightGray)
                }
            }
        }

        Spacer(Modifier.height(30.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("البريد الإلكتروني") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("كلمة المرور") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(10.dp))

        // 🔴 عرض الخطأ
        errorMessage?.let {
            Text(
                text = it,
                color = Color.Red,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                // Validation
                if (email.isBlank()) {
                    errorMessage = "البريد الإلكتروني مطلوب"
                    return@Button
                }

                if (password.length < 6) {
                    errorMessage = "كلمة المرور يجب أن تكون 6 أحرف على الأقل"
                    return@Button
                }

                isLoading = true
                errorMessage = null

                onLogin(email, password) { result ->
                    isLoading = false
                    errorMessage = result
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.Black,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text("دخول", color = Color.Black)
            }
        }
    }
}
