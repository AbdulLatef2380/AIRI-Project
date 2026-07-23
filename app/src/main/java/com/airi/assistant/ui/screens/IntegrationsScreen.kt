package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.AiriTheme
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.R
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
                    containerColor = AiriTheme.background.copy(alpha = 0.65f)
                ),
                title = {
                    Text(
                        stringResource(R.string.integrations_title),
                        color = AiriTheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = AiriTheme.onBackground
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
                        stringResource(R.string.integrations_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AiriTheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.integrations_section_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = AiriTheme.onSurfaceVariant,
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
    if (dialog is IntegrationsViewModel.DialogState.Github) {
        val state = dialog as IntegrationsViewModel.DialogState.Github
        TokenDialog(
            title = stringResource(R.string.integration_github_dialog_title),
            emoji = "🐙",
            // Token-acquisition steps stay in English because every label they
            // reference (GitHub menu names, scopes) is itself English in the
            // GitHub UI — translating them would make the steps unfollowable.
            steps = stringArrayResource(R.array.integration_github_steps).toList(),
            inputLabel = stringResource(R.string.integration_github_token_label),
            inputHint = "ghp_xxxxxxxxxxxxxxxxxxxx",
            token = state.token,
            loading = state.loading,
            error = state.error,
            onTokenChange = { vm.updateGithubToken(it) },
            onConfirm = { vm.connectGithub() },
            onDismiss = { vm.closeDialog() }
        )
    }
    if (dialog is IntegrationsViewModel.DialogState.Telegram) {
        val state = dialog as IntegrationsViewModel.DialogState.Telegram
        TokenDialog(
            title = stringResource(R.string.integration_telegram_dialog_title),
            emoji = "✈️",
            steps = stringArrayResource(R.array.integration_telegram_steps).toList(),
            inputLabel = stringResource(R.string.integration_telegram_token_label),
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
@Composable
private fun IntegrationCard(
    item: IntegrationsViewModel.IntegrationItem,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val connected = item.isConnected
    val cardAlpha = if (connected) 0.10f else 0.04f
    val borderColor = if (connected) Color(0xFF4ADE80).copy(alpha = 0.35f)
    else AiriTheme.outline

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, borderColor, AIRIShapes.xl),
        shape = AIRIShapes.xl,
        color = AiriTheme.onBackground.copy(alpha = cardAlpha),
        contentColor = AiriTheme.onSurface,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val iconResId: Int? = when (item.id) {
                    "github"   -> com.airi.assistant.R.drawable.ic_integration_github
                    "telegram" -> com.airi.assistant.R.drawable.ic_integration_telegram
                    "google"   -> com.airi.assistant.R.drawable.ic_integration_google
                    else       -> null
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AiriTheme.outline)
                        .border(0.5.dp, AiriTheme.onSurface.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconResId != null) {
                        // Clip the brand-icon PNG itself to a circle as well so any
                        // white square background (e.g. the Google "G" sheet) is
                        // masked instead of bleeding into the dark surface.
                        Image(
                            painter = painterResource(id = iconResId),
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
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
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp
                    )
                    Text(
                        item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AiriTheme.onBackground.copy(alpha = 0.60f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                StatusBadge(connected)
            }
            AnimatedVisibility(
                visible = connected && item.connectedAs.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Divider(color = AiriTheme.onBackground.copy(alpha = 0.08f))
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.integration_connected_as),
                            style = MaterialTheme.typography.labelSmall,
                            color = AiriTheme.onBackground.copy(alpha = 0.42f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4ADE80),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        // Force LTR for the connected-as identifier so usernames
                        // such as "@octocat" render in their natural direction
                        // even when the device is in an RTL locale.
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                item.connectedAs,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF4ADE80),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        }
                    }
                    if (item.lastUpdated > 0L) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.integration_last_updated, formatTime(item.lastUpdated)),
                            style = MaterialTheme.typography.labelSmall,
                            color = AiriTheme.outline,
                            modifier = Modifier.padding(start = 20.dp)
                        )
                    }
                }
            }
            // Use a full-width button (instead of Arrangement.End) so the
            // "Connect" / "Disconnect" affordance is reliably tappable even on
            // small screens and in RTL layouts.
            Spacer(Modifier.height(14.dp))
            if (connected) {
                OutlinedButton(
                    onClick  = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFF6B6B)
                    ),
                    shape = AIRIShapes.md
                ) {
                    Text(stringResource(R.string.integration_disconnect))
                }
            } else {
                Button(
                    onClick  = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = CosmicAccent
                    ),
                    shape = AIRIShapes.md
                ) {
                    Text(stringResource(R.string.integration_connect), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
@Composable
private fun StatusBadge(connected: Boolean) {
    val bgColor = if (connected) Color(0xFF4ADE80).copy(alpha = 0.15f)
    else AiriTheme.outline
    val textColor = if (connected) Color(0xFF4ADE80) else AiriTheme.onSurface.copy(alpha = 0.45f)
    val transition = rememberInfiniteTransition(label = "integration_status")
    val pulse = transition.animateFloat(
        initialValue = 1f,
        targetValue = if (connected) 1.5f else 1f,
        animationSpec = infiniteRepeatable(animation = tween(1200), repeatMode = RepeatMode.Reverse),
        label = "status_pulse"
    ).value
    val label = if (connected) stringResource(R.string.integration_status_connected)
                else stringResource(R.string.integration_status_offline)

    Row(
        modifier = Modifier
            .clip(AIRIShapes.xl)
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (connected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .scale(pulse)
                        .clip(CircleShape)
                        .background(Color(0xFF4ADE80).copy(alpha = 0.22f))
                )
            }
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (connected) Color(0xFF4ADE80) else AiriTheme.onSurface.copy(alpha = 0.35f))
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}
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
        containerColor = AiriTheme.surface,
        shape = AIRIShapes.xl,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    color = AiriTheme.onBackground,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column {
                // Instructions
                Text(
                    stringResource(R.string.integration_dialog_steps_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = AiriTheme.onSurfaceVariant
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
                                color = AiriTheme.onBackground
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            step,
                            style = MaterialTheme.typography.bodySmall,
                            color = AiriTheme.onBackground.copy(alpha = 0.82f),
                            lineHeight = 18.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Divider(color = AiriTheme.onBackground.copy(alpha = 0.08f))
                Spacer(Modifier.height(16.dp))

                // Token input — forced to LTR so the placeholder examples
                // ("ghp_…", "123456789:AA…") and any pasted token render in
                // their natural left-to-right form even on RTL devices.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = onTokenChange,
                        label = { Text(inputLabel, color = AiriTheme.onSurfaceVariant) },
                        placeholder = {
                            Text(
                                inputHint,
                                color = AiriTheme.outline,
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
                            focusedTextColor = AiriTheme.onSurface,
                            unfocusedTextColor = AiriTheme.onSurface,
                            focusedBorderColor = Color(0xFF7C3AED),
                            unfocusedBorderColor = AiriTheme.onSurface.copy(alpha = 0.25f),
                            errorBorderColor = Color(0xFFFF6B6B),
                            cursorColor = Color(0xFF7C3AED)
                        )
                    )
                }

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
                            stringResource(R.string.integration_dialog_validating),
                            style = MaterialTheme.typography.labelSmall,
                            color = AiriTheme.onSurfaceVariant
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
                    containerColor = CosmicAccent,
                    disabledContainerColor = Color(0xFF6D28D9).copy(alpha = 0.35f)
                )
            ) {
                Text(stringResource(R.string.integration_connect), color = AiriTheme.onBackground)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !loading
            ) {
                Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
            }
        }
    )
}
private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
