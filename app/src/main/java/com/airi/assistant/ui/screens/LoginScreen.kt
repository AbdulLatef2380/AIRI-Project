package com.airi.assistant.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
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
import com.airi.assistant.analytics.AnalyticsService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider

@Composable
fun LoginScreen(
    onSignIn: (String, String, (String?) -> Unit) -> Unit,
    onCreateAccount: (String, String, (String?) -> Unit) -> Unit,
    onGoogleLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isSignUp by remember { mutableStateOf(false) }
    var showEmailForm by remember { mutableStateOf(false) }

    val auth = remember { FirebaseAuth.getInstance() }

    // ── Google Sign-In ────────────────────────────────────────────────────────
    val googleSignInClient = remember {
        runCatching {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(context.getString(R.string.default_web_client_id))
                .requestEmail()
                .build()
            GoogleSignIn.getClient(context, gso)
        }.getOrNull()
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
                auth.signInWithCredential(credential)
                    .addOnCompleteListener { authTask ->
                        isLoading = false
                        if (authTask.isSuccessful) {
                            AnalyticsService.login("google")
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

    // ── GitHub Sign-In ────────────────────────────────────────────────────────
    fun signInWithGitHub() {
        if (activity == null) { errorMessage = "Cannot open sign-in from this context"; return }
        isLoading = true
        errorMessage = null
        val provider = OAuthProvider.newBuilder("github.com")
        auth.startActivityForSignInWithProvider(activity, provider.build())
            .addOnSuccessListener {
                isLoading = false
                AnalyticsService.login("github")
                onGoogleLoginSuccess()
            }
            .addOnFailureListener { e ->
                isLoading = false
                errorMessage = when {
                    e.message?.contains("cancelled", ignoreCase = true) == true -> "GitHub sign-in cancelled"
                    e.message?.contains("network", ignoreCase = true) == true -> "Network error. Check your connection."
                    else -> "GitHub sign-in failed: ${e.localizedMessage}"
                }
            }
    }

    // ── Input validation ──────────────────────────────────────────────────────
    fun validateInputs(): Boolean = when {
        email.isBlank()       -> { errorMessage = "Email is required"; false }
        !email.contains("@") -> { errorMessage = "Enter a valid email address"; false }
        password.length < 6  -> { errorMessage = "Password must be at least 6 characters"; false }
        else -> true
    }

    // ── Colors ────────────────────────────────────────────────────────────────
    val bgColor     = Color(0xFF000000)
    val buttonBg    = Color(0xFF1A1A1A)
    val accentColor = Color(0xFF7C3AED)
    val dividerLine = Color(0xFF333333)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Logo ───────────────────────────────────────────────────────────
            Spacer(Modifier.height(60.dp))
            Image(
                painter            = painterResource(id = R.drawable.ic_launcher_fg),
                contentDescription = "AIRI",
                modifier           = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(24.dp))
            )

            // ── Welcome Title ──────────────────────────────────────────────────
            Spacer(Modifier.height(32.dp))
            Text(
                text       = "مرحباً بك في AIRI",
                fontSize   = 28.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
                textAlign  = TextAlign.Center
            )

            // ── GitHub Button ──────────────────────────────────────────────────
            Spacer(Modifier.height(40.dp))
            LoginButton(
                onClick  = { signInWithGitHub() },
                enabled  = !isLoading,
                bgColor  = buttonBg,
                iconResId = R.drawable.ic_github,
                text     = "Continue with GitHub"
            )

            // ── Google Button ──────────────────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            LoginButton(
                onClick = {
                    errorMessage = null
                    if (googleSignInClient != null) {
                        googleLauncher.launch(googleSignInClient.signInIntent)
                    } else {
                        errorMessage = "Google Sign-In is not configured for this build"
                    }
                },
                enabled   = !isLoading,
                bgColor   = buttonBg,
                iconResId = R.drawable.ic_google,
                text      = "Continue with Google"
            )

            // ── Divider ────────────────────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color    = dividerLine
                )
                Text(
                    text     = "  أو  ",
                    color    = Color.White.copy(alpha = 0.45f),
                    fontSize = 13.sp
                )
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color    = dividerLine
                )
            }
            Spacer(Modifier.height(24.dp))

            // ── Email Button ───────────────────────────────────────────────────
            LoginButton(
                onClick   = { showEmailForm = !showEmailForm; errorMessage = null },
                enabled   = !isLoading,
                bgColor   = buttonBg,
                icon      = Icons.Outlined.Email,
                text      = "Continue with Email"
            )

            // ── Email Form ─────────────────────────────────────────────────────
            if (showEmailForm) {
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value         = email,
                    onValueChange = { email = it; errorMessage = null },
                    label         = { Text("Email") },
                    leadingIcon   = {
                        Icon(Icons.Outlined.Email, contentDescription = null,
                            tint = accentColor.copy(alpha = 0.8f))
                    },
                    modifier    = Modifier.fillMaxWidth(),
                    singleLine  = true,
                    shape       = RoundedCornerShape(16.dp),
                    colors      = loginFieldColors(accentColor)
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value         = password,
                    onValueChange = { password = it; errorMessage = null },
                    label         = { Text("Password") },
                    leadingIcon   = {
                        Icon(Icons.Outlined.Lock, contentDescription = null,
                            tint = accentColor.copy(alpha = 0.8f))
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Outlined.Visibility
                                              else Icons.Outlined.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide" else "Show",
                                tint = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    modifier   = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape      = RoundedCornerShape(16.dp),
                    colors     = loginFieldColors(accentColor)
                )

                errorMessage?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text       = err,
                        color      = Color(0xFFFF6B6B),
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier   = Modifier.fillMaxWidth()
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
                        .height(56.dp),
                    enabled = !isLoading,
                    shape   = RoundedCornerShape(16.dp),
                    colors  = ButtonDefaults.buttonColors(
                        containerColor         = accentColor,
                        contentColor           = Color.White,
                        disabledContainerColor = accentColor.copy(alpha = 0.4f)
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color       = Color.White,
                            strokeWidth = 2.dp,
                            modifier    = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            text       = if (isSignUp) "Create Account" else "Sign In",
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 16.sp
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                TextButton(onClick = { isSignUp = !isSignUp; errorMessage = null }) {
                    Text(
                        text       = if (isSignUp) "Already have an account? Sign in"
                                     else "Don't have an account? Sign up",
                        fontSize   = 14.sp,
                        color      = accentColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // ── Footer ─────────────────────────────────────────────────────────
            Spacer(Modifier.height(40.dp))
            Text(
                text      = "By continuing, you agree to our Terms of Service and Privacy Policy",
                fontSize  = 11.sp,
                color     = Color(0xFF888888),
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LoginButton(
    onClick: () -> Unit,
    enabled: Boolean,
    bgColor: Color,
    iconResId: Int? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    text: String
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = enabled,
        shape   = RoundedCornerShape(16.dp),
        colors  = ButtonDefaults.buttonColors(
            containerColor         = bgColor,
            contentColor           = Color.White,
            disabledContainerColor = bgColor.copy(alpha = 0.5f),
            disabledContentColor   = Color.White.copy(alpha = 0.5f)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        Row(
            verticalAlignment      = Alignment.CenterVertically,
            horizontalArrangement  = Arrangement.Center,
            modifier               = Modifier.fillMaxWidth()
        ) {
            if (iconResId != null) {
                Icon(
                    painter            = painterResource(id = iconResId),
                    contentDescription = null,
                    modifier           = Modifier.size(20.dp),
                    tint               = Color.Unspecified
                )
            } else if (icon != null) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    modifier           = Modifier.size(20.dp),
                    tint               = Color.White.copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text       = text,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 16.sp,
                color      = Color.White
            )
        }
    }
}

@Composable
private fun loginFieldColors(accentColor: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor     = accentColor,
    unfocusedBorderColor   = Color.White.copy(alpha = 0.15f),
    focusedTextColor       = Color.White,
    unfocusedTextColor     = Color.White,
    focusedLabelColor      = accentColor,
    unfocusedLabelColor    = Color.White.copy(alpha = 0.45f),
    cursorColor            = accentColor,
    focusedContainerColor  = Color(0xFF0D0D0D),
    unfocusedContainerColor = Color(0xFF0D0D0D)
)
