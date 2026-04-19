package com.airi.assistant.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.ui.viewmodel.IntegrationsViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: IntegrationsViewModel = viewModel()
    val items by vm.items.collectAsState()
    val dialog by vm.dialog.collectAsState()

    // ─── Google Sign-In launcher ───────────────────────────────────────────────
    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                vm.onGoogleSignInSuccess(account)
            } catch (e: ApiException) {
                vm.onGoogleSignInFailed()
            }
        } else {
            vm.onGoogleSignInFailed()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.65f)
                ),
                title = {
                    Text(
                        "Integrations",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text(
                        "Connected tools",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Connect external services using your own credentials. All tokens are stored securely on-device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f),
                        lineHeight = 18.sp
                    )
                }
            }

            items(items, key = { it.id }) { item ->
                IntegrationCard(
                    item = item,
                    onConnect = {
                        when (item.id) {
                            "github" -> vm.openGithubDialog()
                            "telegram" -> vm.openTelegramDialog()
                            "google" -> googleLauncher.launch(
                                vm.getGoogleSignInIntent()
                            )
                        }
                    },
                    onDisconnect = { vm.disconnect(item.id) }
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ─── GitHub Dialog ─────────────────────────────────────────────────────────
    if (dialog is IntegrationsViewModel.DialogState.Github) {
        val state = dialog as IntegrationsViewModel.DialogState.Github
        TokenDialog(
            title = "Connect GitHub",
            emoji = "🐙",
            steps = listOf(
                "Open github.com and sign in",
                "Go to Settings → Developer Settings",
                "Select Personal access tokens → Tokens (classic)",
                "Click Generate new token (classic)",
                "Enable scopes: repo, read:user",
                "Generate and copy the token"
            ),
            inputLabel = "Personal Access Token",
            inputHint = "ghp_xxxxxxxxxxxxxxxxxxxx",
            token = state.token,
            loading = state.loading,
            error = state.error,
            onTokenChange = { vm.updateGithubToken(it) },
            onConfirm = { vm.connectGithub() },
            onDismiss = { vm.closeDialog() }
        )
    }

    // ─── Telegram Dialog ───────────────────────────────────────────────────────
    if (dialog is IntegrationsViewModel.DialogState.Telegram) {
        val state = dialog as IntegrationsViewModel.DialogState.Telegram
        TokenDialog(
            title = "Connect Telegram",
            emoji = "✈️",
            steps = listOf(
                "Open Telegram and search for @BotFather",
                "Send the command /newbot",
                "Follow the steps to name and create your bot",
                "BotFather will send you a token",
                "Copy the token and paste it below"
            ),
            inputLabel = "Bot Token",
            inputHint = "123456789:AAxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            token = state.token,
            loading = state.loading,
            error = state.error,
            onTokenChange = { vm.updateTelegramToken(it) },
            onConfirm = { vm.connectTelegram() },
            onDismiss = { vm.closeDialog() }
        )
    }
}

// ─── Integration Card ──────────────────────────────────────────────────────────

@Composable
private fun IntegrationCard(
    item: IntegrationsViewModel.IntegrationItem,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val connected = item.isConnected
    val cardAlpha = if (connected) 0.10f else 0.04f
    val borderColor = if (connected) Color(0xFF4ADE80).copy(alpha = 0.35f)
    else Color.White.copy(alpha = 0.08f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = cardAlpha),
        contentColor = Color.White,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header ─────────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                val iconResId: Int? = when (item.id) {
                    "github"   -> com.airi.assistant.R.drawable.ic_integration_github
                    "telegram" -> com.airi.assistant.R.drawable.ic_integration_telegram
                    "google"   -> com.airi.assistant.R.drawable.ic_integration_google
                    else       -> null
                }
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconResId != null) {
                        Image(
                            painter = painterResource(id = iconResId),
                            contentDescription = item.name,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Text(item.emoji, fontSize = 24.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.60f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusBadge(connected)
            }

            // ── Connected-as ───────────────────────────────────────────────────
            AnimatedVisibility(
                visible = connected && item.connectedAs.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Divider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4ADE80),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Connected as ${item.connectedAs}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF4ADE80)
                        )
                    }
                    if (item.lastUpdated > 0L) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Last updated ${formatTime(item.lastUpdated)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.padding(start = 20.dp)
                        )
                    }
                }
            }

            // ── Action button ──────────────────────────────────────────────────
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (connected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFF6B6B)
                        )
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6D28D9)
                        )
                    ) {
                        Text("Connect")
                    }
                }
            }
        }
    }
}

// ─── Status Badge ──────────────────────────────────────────────────────────────

@Composable
private fun StatusBadge(connected: Boolean) {
    val bgColor = if (connected) Color(0xFF4ADE80).copy(alpha = 0.15f)
    else Color.White.copy(alpha = 0.07f)
    val textColor = if (connected) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.45f)
    val label = if (connected) "● Connected" else "○ Not connected"

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ─── Token Input Dialog ────────────────────────────────────────────────────────

@Composable
private fun TokenDialog(
    title: String,
    emoji: String,
    steps: List<String>,
    inputLabel: String,
    inputHint: String,
    token: String,
    loading: Boolean,
    error: String?,
    onTokenChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        containerColor = Color(0xFF1A1625),
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column {
                // Instructions
                Text(
                    "Follow these steps to get your token:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(10.dp))

                steps.forEachIndexed { index, step ->
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF6D28D9).copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${index + 1}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            step,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.82f),
                            lineHeight = 18.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Divider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(16.dp))

                // Token input
                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChange,
                    label = { Text(inputLabel, color = Color.White.copy(alpha = 0.6f)) },
                    placeholder = {
                        Text(
                            inputHint,
                            color = Color.White.copy(alpha = 0.25f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !loading,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF7C3AED),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                        errorBorderColor = Color(0xFFFF6B6B),
                        cursorColor = Color(0xFF7C3AED)
                    )
                )

                // Error message
                AnimatedVisibility(visible = error != null) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFF6B6B),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            error ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFF6B6B)
                        )
                    }
                }

                // Loading indicator
                AnimatedVisibility(visible = loading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF7C3AED)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Validating token…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !loading && token.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6D28D9),
                    disabledContainerColor = Color(0xFF6D28D9).copy(alpha = 0.35f)
                )
            ) {
                Text("Connect", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !loading
            ) {
                Text("Cancel", color = Color.White.copy(alpha = 0.55f))
            }
        }
    )
}

// ─── Helpers ───────────────────────────────────────────────────────────────────

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
