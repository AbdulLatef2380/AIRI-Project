import com.airi.assistant.ui.components.AiriScreenHeader
package com.airi.assistant.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.auth.FirebaseAuth

private const val PREFS_PROFILE   = "airi_profile"
private const val KEY_DISPLAY_NAME = "display_name"
private const val KEY_USERNAME     = "username"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val context  = LocalContext.current
    val prefs    = remember { context.getSharedPreferences(PREFS_PROFILE, Context.MODE_PRIVATE) }
    val user     = remember { FirebaseAuth.getInstance().currentUser }
    val email    = user?.email ?: "guest@airi.ai"
    val initial  = email.firstOrNull()?.uppercaseChar()?.toString() ?: "A"

    var displayName by remember { mutableStateOf(prefs.getString(KEY_DISPLAY_NAME, user?.displayName ?: "") ?: "") }
    var username    by remember { mutableStateOf(prefs.getString(KEY_USERNAME, "") ?: "") }
    var saved       by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Surface0,
        topBar = { AiriScreenHeader(title = "الملف الشخصي", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Avatar card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Surface2, Surface1)))
                    .border(1.dp, BorderLight, RoundedCornerShape(20.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(PrimaryAccent.copy(0.3f), PrimaryAccent.copy(0.08f))))
                            .border(2.dp, PrimaryAccent.copy(0.55f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initial, color = PrimaryAccent, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                    }
                    Text(displayName.ifBlank { email }, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(email, color = TextSecondary, fontSize = 13.sp)
                }
            }

            NeuralSectionLabel("المعلومات الشخصية")

            // Fields
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface1)
                    .border(1.dp, BorderLight, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; saved = false },
                    label = { Text("الاسم المعروض", color = TextTertiary, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = neuralTextFieldColors(),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; saved = false },
                    label = { Text("اسم المستخدم", color = TextTertiary, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.AlternateEmail, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = neuralTextFieldColors(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            NeuralAccentButton(
                text = if (saved) "✓ تم الحفظ" else "حفظ التغييرات",
                onClick = {
                    prefs.edit()
                        .putString(KEY_DISPLAY_NAME, displayName)
                        .putString(KEY_USERNAME, username)
                        .apply()
                    saved = true
                },
                enabled = !saved,
                icon = Icons.Outlined.Save
            )
        }
    }
}

@Composable
private fun neuralTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor    = PrimaryAccent,
    unfocusedBorderColor  = BorderLight,
    focusedTextColor      = TextPrimary,
    unfocusedTextColor    = TextPrimary,
    cursorColor           = PrimaryAccent,
    focusedContainerColor    = Surface2,
    unfocusedContainerColor  = Surface2,
    focusedLabelColor     = PrimaryAccent
)
