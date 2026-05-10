package com.airi.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.components.NeuralAccentButton
import com.airi.assistant.ui.theme.*

@Composable
fun LoginScreen(
    onSignIn: (email: String, password: String, onResult: (String?) -> Unit) -> Unit,
    onCreateAccount: (email: String, password: String, onResult: (String?) -> Unit) -> Unit,
    onGoogleLoginSuccess: () -> Unit
) {
    var isSignIn  by remember { mutableStateOf(true) }
    var email     by remember { mutableStateOf("") }
    var password  by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg  by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(PrimaryAccent.copy(0.12f), Surface0, Surface0),
                    center = androidx.compose.ui.geometry.Offset.Unspecified,
                    radius = 800f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(60.dp))

            // Logo
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(PrimaryAccent.copy(0.35f), PrimaryAccent.copy(0.08f))))
                    .border(1.5.dp, PrimaryAccent.copy(0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = PrimaryAccent, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Text("AIRI", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Text("نظام تشغيل الذكاء الاصطناعي", color = TextSecondary, fontSize = 14.sp)

            Spacer(Modifier.height(40.dp))

            // Tab switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface2)
                    .border(1.dp, BorderLight, RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("تسجيل الدخول" to true, "إنشاء حساب" to false).forEach { (label, forSignIn) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSignIn == forSignIn) PrimaryAccent else Color.Transparent)
                            .clickable { isSignIn = forSignIn; errorMsg = null }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (isSignIn == forSignIn) Color.White else TextSecondary, fontWeight = if (isSignIn == forSignIn) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Email field
            NeuralTextField(
                value = email,
                onValueChange = { email = it; errorMsg = null },
                label = "البريد الإلكتروني",
                icon = Icons.Outlined.Email,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            )
            Spacer(Modifier.height(12.dp))

            // Password field
            NeuralTextField(
                value = password,
                onValueChange = { password = it; errorMsg = null },
                label = "كلمة المرور",
                icon = Icons.Outlined.Lock,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passVisible = !passVisible }) {
                        Icon(if (passVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(18.dp))
                    }
                }
            )

            AnimatedVisibility(visible = errorMsg != null) {
                Text(
                    errorMsg ?: "",
                    color = SemanticError,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            NeuralAccentButton(
                text = if (isSignIn) "دخول" else "إنشاء حساب",
                onClick = {
                    if (email.isBlank() || password.isBlank()) { errorMsg = "يرجى ملء جميع الحقول"; return@NeuralAccentButton }
                    isLoading = true
                    val action = if (isSignIn) onSignIn else onCreateAccount
                    action(email.trim(), password) { err ->
                        isLoading = false
                        errorMsg = err
                    }
                },
                enabled = !isLoading && email.isNotBlank() && password.isNotBlank()
            )

            if (isLoading) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(color = PrimaryAccent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun NeuralTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextTertiary, fontSize = 13.sp) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(18.dp)) },
        trailingIcon = trailingIcon,
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = PrimaryAccent,
            unfocusedBorderColor = BorderLight,
            focusedTextColor     = TextPrimary,
            unfocusedTextColor   = TextPrimary,
            cursorColor          = PrimaryAccent,
            focusedContainerColor   = Surface2,
            unfocusedContainerColor = Surface2,
            focusedLabelColor    = PrimaryAccent
        ),
        shape = RoundedCornerShape(14.dp)
    )
}
