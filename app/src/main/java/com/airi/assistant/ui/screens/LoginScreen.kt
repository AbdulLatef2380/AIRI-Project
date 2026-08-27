package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import com.airi.assistant.ui.theme.AiriTheme
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
import com.google.android.gms.common.api.CommonStatusCodes
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.domain.auth.AuthService
import androidx.compose.ui.res.stringResource

/**
 * LoginScreen — ask 3: AuthService enforcement.
 *
 * All Firebase calls are now routed through [AuthService] injected via the
 * [authService] parameter. No direct [FirebaseAuth.getInstance()] calls remain
 * in this composable, eliminating vendor lock-in risk at the UI layer.
 *
 * The Google ID-token exchange and GitHub OAuth launch are still initiated from
 * the UI (they require Activity context and an Android launcher) but the actual
 * Firebase credential exchange is delegated to [AuthService.signInWithGoogleCredential]
 * and [AuthService.signInWithGitHub] respectively.
 */
@Composable
fun LoginScreen(
    authService: AuthService,
    onSignIn: (String, String, (String?) -> Unit) -> Unit,
    onCreateAccount: (String, String, (String?) -> Unit) -> Unit,
    onGoogleLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<LoginFeedback?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isSignUp by remember { mutableStateOf(false) }
    var showEmailForm by remember { mutableStateOf(false) }
    val googleWebClientId = remember {
        context.getString(R.string.default_web_client_id)
            .takeIf { it.isNotBlank() && !it.startsWith("REPLACE_WITH_") }
    }
    val googleSignInClient = remember(googleWebClientId) {
        googleWebClientId?.let { clientId ->
            runCatching {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(clientId)
                    .requestEmail()
                    .build()
                GoogleSignIn.getClient(context, gso)
            }.getOrNull()
        }
    }

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cancelled = result.resultCode != Activity.RESULT_OK
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            val validation = LoginFeedbackPolicy.googleProviderResult(
                wasCancelled = cancelled,
                hasIdToken = !account?.idToken.isNullOrBlank(),
            )
            if (validation != null) {
                feedback = validation
            } else {
                isLoading = true
                authService.signInWithGoogleCredential(requireNotNull(account?.idToken)) { error ->
                    isLoading = false
                    if (error == null) {
                        AnalyticsService.login("google")
                        onGoogleLoginSuccess()
                    } else {
                        feedback = LoginFeedback.GOOGLE_EXCHANGE_FAILED
                    }
                }
            }
        } catch (exception: ApiException) {
            feedback = LoginFeedbackPolicy.googleApiFailure(
                wasCancelled = cancelled || exception.statusCode == CommonStatusCodes.CANCELED,
            )
        }
    }
    fun signInWithGitHub() {
        if (activity == null) {
            feedback = LoginFeedback.GITHUB_CONTEXT_UNAVAILABLE
            return
        }
        isLoading = true
        feedback = null
        authService.signInWithGitHub(
            activity  = activity,
            onSuccess = {
                isLoading = false
                AnalyticsService.login("github")
                onGoogleLoginSuccess()
            },
            onFailure = {
                isLoading = false
                feedback = LoginFeedback.GITHUB_FAILED
            }
        )
    }
    fun validateInputs(): Boolean {
        val validation = LoginFeedbackPolicy.emailValidation(email, password)
        feedback = validation
        return validation == null
    }
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
            Spacer(Modifier.height(60.dp))
            Image(
                painter            = painterResource(id = R.drawable.ic_launcher_fg),
                contentDescription = "AIRI",
                modifier           = Modifier
                    .size(100.dp)
                    .clip(AIRIShapes.xl)
            )
            Spacer(Modifier.height(32.dp))
            Text(
                text       = stringResource(R.string.welcome_greeting),
                fontSize   = 28.sp,
                fontWeight = FontWeight.Bold,
                color      = AiriTheme.onBackground,
                textAlign  = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            feedback?.let { LoginFeedbackBanner(it) }
            Spacer(Modifier.height(if (feedback == null) 16.dp else 12.dp))
            LoginButton(
                onClick  = { signInWithGitHub() },
                enabled  = !isLoading,
                bgColor  = buttonBg,
                iconResId = R.drawable.ic_github,
                text     = stringResource(R.string.continue_with_github)
            )
            Spacer(Modifier.height(12.dp))
            LoginButton(
                onClick = {
                    feedback = null
                    if (googleSignInClient != null) {
                        googleLauncher.launch(googleSignInClient.signInIntent)
                    } else {
                        feedback = LoginFeedback.GOOGLE_NOT_CONFIGURED
                    }
                },
                enabled   = !isLoading,
                bgColor   = buttonBg,
                iconResId = R.drawable.ic_google,
                text      = stringResource(R.string.continue_with_google)
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Divider(
                    modifier = Modifier.weight(1f),
                    color    = dividerLine
                )
                Text(
                    text     = stringResource(R.string.login_or_divider),
                    color    = AiriTheme.onBackground.copy(alpha = 0.45f),
                    fontSize = 13.sp
                )
                Divider(
                    modifier = Modifier.weight(1f),
                    color    = dividerLine
                )
            }
            Spacer(Modifier.height(24.dp))
            LoginButton(
                onClick   = { showEmailForm = !showEmailForm; feedback = null },
                enabled   = !isLoading,
                bgColor   = buttonBg,
                icon      = Icons.Outlined.Email,
                text      = stringResource(R.string.continue_with_email)
            )
            if (showEmailForm) {
                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value         = email,
                    onValueChange = { email = it; feedback = null },
                    label         = { Text(stringResource(R.string.login_email_label)) },
                    leadingIcon   = {
                        Icon(Icons.Outlined.Email, contentDescription = null,
                            tint = accentColor.copy(alpha = 0.8f))
                    },
                    modifier    = Modifier.fillMaxWidth(),
                    singleLine  = true,
                    shape       = AIRIShapes.md,
                    colors      = loginFieldColors(accentColor)
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value         = password,
                    onValueChange = { password = it; feedback = null },
                    label         = { Text(stringResource(R.string.login_password_label)) },
                    leadingIcon   = {
                        Icon(Icons.Outlined.Lock, contentDescription = null,
                            tint = accentColor.copy(alpha = 0.8f))
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Outlined.Visibility
                                              else Icons.Outlined.VisibilityOff,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.login_hide_password else R.string.login_show_password
                                ),
                                tint = AiriTheme.onSurfaceVariant
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    modifier   = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape      = AIRIShapes.md,
                    colors     = loginFieldColors(accentColor)
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (!validateInputs()) return@Button
                        isLoading = true
                        feedback = null
                        if (isSignUp) {
                            onCreateAccount(email, password) { error ->
                                isLoading = false
                                feedback = if (error == null) null else LoginFeedback.EMAIL_AUTH_FAILED
                            }
                        } else {
                            onSignIn(email, password) { error ->
                                isLoading = false
                                feedback = if (error == null) null else LoginFeedback.EMAIL_AUTH_FAILED
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = !isLoading,
                    shape   = AIRIShapes.md,
                    colors  = ButtonDefaults.buttonColors(
                        containerColor         = accentColor,
                        contentColor           = AiriTheme.onSurface,
                        disabledContainerColor = accentColor.copy(alpha = 0.4f)
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color       = AiriTheme.onBackground,
                            strokeWidth = 2.dp,
                            modifier    = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            text       = stringResource(if (isSignUp) R.string.create_account else R.string.sign_in),
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 16.sp
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                TextButton(onClick = { isSignUp = !isSignUp; feedback = null }) {
                    Text(
                        text       = stringResource(
                            if (isSignUp) R.string.login_existing_account else R.string.login_new_account
                        ),
                        fontSize   = 14.sp,
                        color      = accentColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
            Text(
                text      = stringResource(R.string.login_terms_privacy),
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
private fun LoginFeedbackBanner(feedback: LoginFeedback) {
    val message = when (feedback) {
        LoginFeedback.GOOGLE_NOT_CONFIGURED -> stringResource(R.string.login_google_not_configured)
        LoginFeedback.GOOGLE_CANCELLED -> stringResource(R.string.login_google_cancelled)
        LoginFeedback.GOOGLE_NO_ID_TOKEN -> stringResource(R.string.login_google_no_id_token)
        LoginFeedback.GOOGLE_EXCHANGE_FAILED -> stringResource(R.string.login_google_exchange_failed)
        LoginFeedback.GITHUB_CONTEXT_UNAVAILABLE -> stringResource(R.string.login_github_context_unavailable)
        LoginFeedback.GITHUB_FAILED -> stringResource(R.string.login_github_failed)
        LoginFeedback.EMAIL_REQUIRED -> stringResource(R.string.login_email_required)
        LoginFeedback.EMAIL_INVALID -> stringResource(R.string.login_email_invalid)
        LoginFeedback.PASSWORD_TOO_SHORT -> stringResource(R.string.login_password_too_short)
        LoginFeedback.EMAIL_AUTH_FAILED -> stringResource(R.string.login_email_auth_failed)
    }
    Surface(
        color = AiriTheme.error.copy(alpha = 0.14f),
        contentColor = AiriTheme.onBackground,
        shape = AIRIShapes.md,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            color = AiriTheme.error,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
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
        shape   = AIRIShapes.md,
        colors  = ButtonDefaults.buttonColors(
            containerColor         = bgColor,
            contentColor           = AiriTheme.onSurface,
            disabledContainerColor = bgColor.copy(alpha = 0.5f),
            disabledContentColor   = AiriTheme.onSurface.copy(alpha = 0.5f)
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
                    tint               = AiriTheme.onBackground.copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text       = text,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 16.sp,
                color      = AiriTheme.onBackground
            )
        }
    }
}

@Composable
private fun loginFieldColors(accentColor: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor     = accentColor,
    unfocusedBorderColor   = AiriTheme.onSurface.copy(alpha = 0.15f),
    focusedTextColor       = AiriTheme.onSurface,
    unfocusedTextColor     = AiriTheme.onSurface,
    focusedLabelColor      = accentColor,
    unfocusedLabelColor    = AiriTheme.onSurface.copy(alpha = 0.45f),
    cursorColor            = accentColor,
    focusedContainerColor  = Color(0xFF0D0D0D),
    unfocusedContainerColor = Color(0xFF0D0D0D)
)
