package com.airi.assistant.ui.screens

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
import com.airi.assistant.ui.theme.Surface0
import com.airi.assistant.ui.theme.Surface1
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferralScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val userId = remember { FirebaseAuth.getInstance().currentUser?.uid }
    var bonus by remember { mutableStateOf(ReferralManager.getBonusMessages()) }
    var codeInput by remember { mutableStateOf("") }
    val code = remember(userId) { ReferralManager.getOrCreateCode(userId) }
    val shareText = remember(userId, code) { ReferralManager.createShareText(userId) }

    Scaffold(
        containerColor = Surface0,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface1),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = { Text("Invite friends", color = Color.White, fontWeight = FontWeight.Bold) }
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
            Text("Grow with AIRI", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Share AIRI with friends. You get +20 bonus messages for inviting, and new users get +20 welcome messages when they join with your code.",
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Your referral code", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                Text(code, color = CosmicAccent, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
                Text("Bonus messages available: $bonus", color = Color.White.copy(alpha = 0.66f), fontSize = 13.sp)
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
            ReferralButton(Icons.Outlined.ContentCopy, "Copy link") {
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
                label = { Text("Have a referral code?", color = Color.White.copy(alpha = 0.55f)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CosmicAccent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
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
                Text("Redeem welcome bonus", color = CosmicAccent)
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
        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f), contentColor = Color.White)
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