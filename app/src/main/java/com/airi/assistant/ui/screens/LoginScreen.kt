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
fun LoginScreen(onLoginSuccess: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

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
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = CosmicAccent,
                unfocusedBorderColor = Color.Gray
            )
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("كلمة المرور") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = CosmicAccent,
                unfocusedBorderColor = Color.Gray
            )
        )

        Spacer(Modifier.height(15.dp))

        Button(
            onClick = onLoginSuccess,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = CosmicAccent
            )
        ) {
            Text("دخول", color = Color.Black)
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = { /* Google Sign In */ },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("التسجيل عبر Google", color = Color.White)
        }
    }
}
