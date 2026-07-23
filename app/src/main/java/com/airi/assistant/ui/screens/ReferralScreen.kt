package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.domain.growth.ReferralManager
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.core.ServiceLocator
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferralScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
        val userId = remember { ServiceLocator.authService.currentUser()?.uid }
    var bonus by remember { mutableStateOf(ReferralManager.getBonusMessages()) }
    var codeInput by remember { mutableStateOf("") }
    val code = remember(userId) { ReferralManager.getOrCreateCode(userId) }
    val shareText = remember(userId, code) { ReferralManager.createShareText(userId) }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background.copy(alpha = 0.75f)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = { Text(stringResource(R.string.invite_friends), color = AiriTheme.onBackground, fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF050816), Color(0xFF111733), Color(0xFF050816))))
                .padding(padding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Icon(Icons.Outlined.CardGiftcard, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(54.dp))
            Text(stringResource(R.string.referral_heading), color = AiriTheme.onBackground, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Share AIRI with friends. You get +20 bonus messages for inviting, and new users get +20 welcome messages when they join with your code.",
                color = AiriTheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AIRIShapes.xl)
                    .background(AiriTheme.surfaceVariant)
                    .border(1.dp, AiriTheme.onSurface.copy(alpha = 0.1f), AIRIShapes.xl)
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.referral_code_label), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                Text(code, color = CosmicAccent, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
                Text(stringResource(R.string.referral_bonus_available, bonus), color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
            }

            ReferralButton(Icons.Outlined.Send, "Share on WhatsApp") {
                shareToPackage(context, "com.whatsapp", shareText)
                ReferralManager.onReferralSent("whatsapp", userId)
                bonus = ReferralManager.getBonusMessages()
            }
            ReferralButton(Icons.Outlined.Send, "Share on Telegram") {
                shareToPackage(context, "org.telegram.messenger", shareText)
                ReferralManager.onReferralSent("telegram", userId)
                bonus = ReferralManager.getBonusMessages()
            }
            ReferralButton(Icons.Outlined.ContentCopy, stringResource(R.string.referral_copy_link)) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AIRI referral", shareText))
                ReferralManager.onReferralSent("copy_link", userId)
                bonus = ReferralManager.getBonusMessages()
                scope.launch { snackbarHost.showSnackbar("Referral link copied") }
            }

            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it.uppercase() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.referral_code_field_label), color = AiriTheme.onSurfaceVariant) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CosmicAccent,
                    unfocusedBorderColor = AiriTheme.onSurface.copy(alpha = 0.18f),
                    focusedTextColor = AiriTheme.onSurface,
                    unfocusedTextColor = AiriTheme.onSurface
                )
            )

            TextButton(
                onClick = {
                    val redeemed = ReferralManager.redeemCode(codeInput, userId)
                    bonus = ReferralManager.getBonusMessages()
                    scope.launch { snackbarHost.showSnackbar(if (redeemed) "Welcome bonus added" else "Enter a valid AIRI code") }
                }
            ) {
                Icon(Icons.Outlined.Redeem, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.referral_redeem), color = CosmicAccent)
            }
        }
    }
}

@Composable
private fun ReferralButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AiriTheme.outline, contentColor = AiriTheme.onBackground)
    ) {
        Icon(icon, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

private fun shareToPackage(context: Context, packageName: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        setPackage(packageName)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share AIRI"))
    }
}