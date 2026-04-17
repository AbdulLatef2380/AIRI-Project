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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.integrations.github.GithubService
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthCredential
import com.google.firebase.auth.OAuthProvider
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onSignIn: (String, String, (String?) -> Unit) -> Unit,
    onCreateAccount: (String, String, (String?) -> Unit) -> Unit,
    onGithubLoginSuccess: () -> Unit,
    onGoogleLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isSignUp by remember { mutableStateOf(false) }
    var showEmailForm by remember { mutableStateOf(false) }

    val githubService = remember(context.applicationContext) {
        GithubService(SecureStorage(context.applicationContext))
    }

    val githubProvider = remember {
        val provider = OAuthProvider.newBuilder("github.com")
        provider.scopes = listOf("read:user", "user:email", "repo")
        provider.build()
    }

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

    fun signInWithGithub() {
        val activity = context as? Activity
        if (activity == null) {
            errorMessage = "GitHub sign-in requires an active Android screen"
            return
        }

        isLoading = true
        errorMessage = null

        val auth = FirebaseAuth.getInstance()
        val authTask = auth.pendingAuthResult
            ?: auth.startActivityForSignInWithProvider(activity, githubProvider)

        authTask.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                isLoading = false
                errorMessage = task.exception?.localizedMessage ?: "GitHub sign-in failed"
                return@addOnCompleteListener
            }

            val accessToken = (task.result?.credential as? OAuthCredential)?.accessToken
            if (accessToken.isNullOrBlank()) {
                isLoading = false
                onGithubLoginSuccess()
                return@addOnCompleteListener
            }

            scope.launch {
                githubService.validateAndConnect(accessToken)
                isLoading = false
                onGithubLoginSuccess()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))

            Image(
                painter = painterResource(id = R.drawable.airi_logo),
                contentDescription = "AIRI",
                modifier = Modifier.size(100.dp)
            )

            Spacer(Modifier.height(32.dp))

            Text(
                text = "مرحباً بك في AIRI",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(40.dp))

            AuthSocialButton(
                onClick = { signInWithGithub() },
                enabled = !isLoading,
                iconResId = R.drawable.ic_github,
                text = "Continue with GitHub",
                iconTint = Color.White
            )

            Spacer(Modifier.height(12.dp))

            AuthSocialButton(
                onClick = {
                    errorMessage = null
                    if (googleSignInClient != null) {
                        googleLauncher.launch(googleSignInClient.signInIntent)
                    } else {
                        errorMessage = "Google Sign-In is not configured for this build"
                    }
                },
                enabled = !isLoading,
                iconResId = R.drawable.ic_google,
                text = stringResource(R.string.continue_with_google),
                iconTint = Color.Unspecified
            )

            AuthDivider()

            AuthSocialButton(
                onClick = { showEmailForm = !showEmailForm; errorMessage = null },
                enabled = !isLoading,
                iconResId = R.drawable.ic_email,
                text = "متابعة باستخدام البريد الإلكتروني",
                iconTint = Color.White
            )

            if (showEmailForm) {
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; errorMessage = null },
                    label = { Text("Email") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Email,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = authFieldColors()
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
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = authFieldColors()
                )

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
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A1A1A),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF1A1A1A).copy(alpha = 0.5f),
                        disabledContentColor = Color.White.copy(alpha = 0.5f)
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
                            text = if (isSignUp) stringResource(R.string.create_account) else stringResource(R.string.sign_in),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                TextButton(onClick = { isSignUp = !isSignUp; errorMessage = null }) {
                    Text(
                        text = if (isSignUp) "Already have an account? Sign in" else "Don't have an account? Sign up",
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            errorMessage?.let { err ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = err,
                    color = Color(0xFFFF6B6B),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                text = "بالمتابعة، فإنك توافق على شروط الخدمة وتقر بأنك قد قرأت سياسة الخصوصية.",
                fontSize = 10.sp,
                color = Color(0xFF888888),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "AIRI Agent © 2026",
                fontSize = 10.sp,
                color = Color(0xFF888888),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AuthSocialButton(
    onClick: () -> Unit,
    enabled: Boolean,
    iconResId: Int,
    text: String,
    iconTint: Color
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1A1A1A),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF1A1A1A).copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.5f)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = iconResId),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(24.dp),
                    tint = iconTint
                )
                Text(
                    text = text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun AuthDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF333333))
        Text(
            text = "أو",
            fontSize = 16.sp,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF333333))
    }
}

@Composable
private fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF333333),
    unfocusedBorderColor = Color(0xFF333333),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = Color.White,
    unfocusedLabelColor = Color(0xFF888888),
    cursorColor = Color.White
)
