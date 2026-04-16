package com.airi.assistant.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.components.GlassCard
import com.airi.assistant.ui.theme.CosmicAccent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

@Composable
fun LoginScreen(
    onSignIn: (String, String, (String?) -> Unit) -> Unit,
    onCreateAccount: (String, String, (String?) -> Unit) -> Unit,
    onGoogleLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showFacebookDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { visible = true }

    val googleSignInClient = remember {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (idToken != null) {
                isLoading = true
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                FirebaseAuth.getInstance().signInWithCredential(credential)
                    .addOnCompleteListener { authTask ->
                        isLoading = false
                        if (authTask.isSuccessful) {
                            onGoogleLoginSuccess()
                        } else {
                            errorMessage = authTask.exception?.localizedMessage ?: "Google sign-in failed"
                        }
                    }
            } else {
                errorMessage = "Google sign-in failed: no ID token"
            }
        } catch (e: ApiException) {
            errorMessage = "Google sign-in cancelled"
        }
    }

    fun validateInputs(): Boolean {
        return when {
            email.isBlank() -> { errorMessage = "Email is required"; false }
            !email.contains("@") -> { errorMessage = "Enter a valid email address"; false }
            password.length < 6 -> { errorMessage = "Password must be at least 6 characters"; false }
            else -> true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(60.dp))

        Text(
            "AIRI",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 36.sp,
            color = CosmicAccent
        )
        Text(
            "Android AI Runtime Interface",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.5f)
        )

        Spacer(Modifier.height(28.dp))

        AnimatedVisibility(visible) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassCard {
                    Text("⚡ On-Device Speed", color = CosmicAccent, fontWeight = FontWeight.SemiBold)
                    Text("Local processing — no cloud delay", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                GlassCard {
                    Text("🔒 Full Privacy", color = CosmicAccent, fontWeight = FontWeight.SemiBold)
                    Text("Your data stays on your device", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; errorMessage = null },
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null, tint = CosmicAccent.copy(alpha = 0.7f)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CosmicAccent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedLabelColor = CosmicAccent,
                unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
            )
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; errorMessage = null },
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null, tint = CosmicAccent.copy(alpha = 0.7f)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CosmicAccent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedLabelColor = CosmicAccent,
                unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
            )
        )

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    if (!validateInputs()) return@Button
                    isLoading = true
                    errorMessage = null
                    onSignIn(email, password) { error ->
                        isLoading = false
                        errorMessage = error
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isLoading,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CosmicAccent,
                    contentColor = Color.Black
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text(stringResource(R.string.sign_in), fontWeight = FontWeight.Bold)
                }
            }

            OutlinedButton(
                onClick = {
                    if (!validateInputs()) return@OutlinedButton
                    isLoading = true
                    errorMessage = null
                    onCreateAccount(email, password) { error ->
                        isLoading = false
                        errorMessage = error
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isLoading,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CosmicAccent),
                border = androidx.compose.foundation.BorderStroke(1.dp, CosmicAccent.copy(alpha = 0.6f))
            ) {
                Text(stringResource(R.string.create_account), fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(16.dp))

        HorizontalDivider(
            color = Color.White.copy(alpha = 0.12f),
            modifier = Modifier.padding(vertical = 4.dp)
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                errorMessage = null
                googleLauncher.launch(googleSignInClient.signInIntent)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF3C4043)
            )
        ) {
            Text(
                stringResource(R.string.continue_with_google),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = { showFacebookDialog = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1877F2),
                contentColor = Color.White
            )
        ) {
            Text(
                stringResource(R.string.continue_with_facebook),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showFacebookDialog) {
        AlertDialog(
            onDismissRequest = { showFacebookDialog = false },
            containerColor = Color(0xFF12162E),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.75f),
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.facebook_sdk_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.facebook_sdk_message)) },
            confirmButton = {
                Button(
                    onClick = { showFacebookDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.Black)
                ) { Text(stringResource(R.string.ok)) }
            }
        )
    }
}
