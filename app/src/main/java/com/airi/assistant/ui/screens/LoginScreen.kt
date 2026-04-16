package com.airi.assistant.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
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
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isSignUp by remember { mutableStateOf(false) }
    var showFacebookDialog by remember { mutableStateOf(false) }
    var showEmailForm by remember { mutableStateOf(false) }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A0E27), Color(0xFF1A0D2E), Color(0xFF0D1A2E))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF13182D)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Text(
                        text = "Welcome to AIRI",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Sign in to continue",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(32.dp))

                    SocialButton(
                        onClick = {
                            errorMessage = null
                            googleLauncher.launch(googleSignInClient.signInIntent)
                        },
                        enabled = !isLoading,
                        containerColor = Color.White,
                        contentColor = Color(0xFF3C4043),
                        iconResId = R.drawable.ic_google,
                        text = stringResource(R.string.continue_with_google)
                    )

                    Spacer(Modifier.height(12.dp))

                    SocialButton(
                        onClick = { showFacebookDialog = true },
                        enabled = !isLoading,
                        containerColor = Color(0xFF1877F2),
                        contentColor = Color.White,
                        iconResId = R.drawable.ic_facebook,
                        text = stringResource(R.string.continue_with_facebook)
                    )

                    Spacer(Modifier.height(12.dp))

                    SocialButton(
                        onClick = { showEmailForm = !showEmailForm; errorMessage = null },
                        enabled = !isLoading,
                        containerColor = Color(0xFF1E2540),
                        contentColor = Color.White,
                        iconResId = R.drawable.ic_email,
                        text = "Continue with Email"
                    )

                    if (showEmailForm) {
                        Spacer(Modifier.height(24.dp))

                        AuthDivider()

                        Spacer(Modifier.height(24.dp))

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; errorMessage = null },
                            label = { Text("Email") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Email,
                                    contentDescription = null,
                                    tint = Color(0xFF7C3AED).copy(alpha = 0.8f)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF7C3AED),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedLabelColor = Color(0xFF7C3AED),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.45f),
                                cursorColor = Color(0xFF7C3AED)
                            )
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; errorMessage = null },
                            label = { Text("Password") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = Color(0xFF7C3AED).copy(alpha = 0.8f)
                                )
                            },
                            trailingIcon = {
                                TextButton(
                                    onClick = { passwordVisible = !passwordVisible },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text(
                                        if (passwordVisible) "Hide" else "Show",
                                        fontSize = 12.sp,
                                        color = Color(0xFF7C3AED)
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF7C3AED),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedLabelColor = Color(0xFF7C3AED),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.45f),
                                cursorColor = Color(0xFF7C3AED)
                            )
                        )

                        errorMessage?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                it,
                                color = Color(0xFFFF6B6B),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(Modifier.height(20.dp))

                        Button(
                            onClick = {
                                if (!validateInputs()) return@Button
                                isLoading = true
                                errorMessage = null
                                if (isSignUp) {
                                    onCreateAccount(email, password) { error ->
                                        isLoading = false
                                        errorMessage = error
                                    }
                                } else {
                                    onSignIn(email, password) { error ->
                                        isLoading = false
                                        errorMessage = error
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            enabled = !isLoading,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF7C3AED),
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFF7C3AED).copy(alpha = 0.5f)
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Text(
                                    if (isSignUp) stringResource(R.string.create_account) else stringResource(R.string.sign_in),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        TextButton(
                            onClick = { isSignUp = !isSignUp; errorMessage = null }
                        ) {
                            Text(
                                if (isSignUp) "Already have an account? Sign in"
                                else "Don't have an account? Sign up",
                                fontSize = 14.sp,
                                color = Color(0xFF7C3AED),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFacebookDialog) {
        AlertDialog(
            onDismissRequest = { showFacebookDialog = false },
            containerColor = Color(0xFF13182D),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.75f),
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    stringResource(R.string.facebook_sdk_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = { Text(stringResource(R.string.facebook_sdk_message)) },
            confirmButton = {
                Button(
                    onClick = { showFacebookDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1877F2),
                        contentColor = Color.White
                    )
                ) { Text(stringResource(R.string.ok)) }
            }
        )
    }
}

@Composable
private fun SocialButton(
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    iconResId: Int,
    text: String
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.5f),
            disabledContentColor = contentColor.copy(alpha = 0.5f)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
        border = if (containerColor == Color.White)
            BorderStroke(1.dp, Color(0xFFDDDDDD))
        else null
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(id = iconResId),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.Unspecified
            )
            Text(
                text = text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun AuthDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = Color.White.copy(alpha = 0.12f)
        )
        Text(
            "OR",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.4f),
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = Color.White.copy(alpha = 0.12f)
        )
    }
}
