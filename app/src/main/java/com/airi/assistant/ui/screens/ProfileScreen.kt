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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.userProfileChangeRequest

private const val PREFS_PROFILE = "airi_profile"
private const val KEY_DISPLAY_NAME = "display_name"
private const val KEY_USERNAME = "username"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_PROFILE, Context.MODE_PRIVATE) }
    val firebaseUser = remember { FirebaseAuth.getInstance().currentUser }
    val email = firebaseUser?.email ?: ""
    val initial = email.firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var displayName by remember {
        mutableStateOf(
            prefs.getString(KEY_DISPLAY_NAME, firebaseUser?.displayName ?: "") ?: ""
        )
    }
    var username by remember {
        mutableStateOf(prefs.getString(KEY_USERNAME, "") ?: "")
    }
    var isSaving by remember { mutableStateOf(false) }

    fun saveProfile() {
        isSaving = true
        prefs.edit()
            .putString(KEY_DISPLAY_NAME, displayName.trim())
            .putString(KEY_USERNAME, username.trim())
            .apply()

        firebaseUser?.let { user ->
            val updates = userProfileChangeRequest { displayName = displayName.trim() }
            user.updateProfile(updates).addOnCompleteListener {
                isSaving = false
            }
        } ?: run { isSaving = false }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Text(
                        stringResource(R.string.edit_profile),
                        fontWeight = FontWeight.Bold,
                        color = AiriTheme.onBackground
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(CosmicAccent.copy(alpha = 0.18f))
                    .border(2.dp, CosmicAccent.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (displayName.isNotBlank()) {
                    Text(
                        displayName.first().uppercaseChar().toString(),
                        color = CosmicAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    )
                } else {
                    Icon(Icons.Outlined.Person, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(36.dp))
                }
            }

            if (email.isNotBlank()) {
                Text(email, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
            }

            Spacer(Modifier.height(4.dp))

            ProfileField(
                label = stringResource(R.string.display_name),
                value = displayName,
                onValueChange = { displayName = it },
                placeholder = "Your name"
            )

            ProfileField(
                label = stringResource(R.string.username),
                value = username,
                onValueChange = { username = it },
                placeholder = "@handle"
            )

            ProfileField(
                label = stringResource(R.string.email_read_only),
                value = email,
                onValueChange = {},
                placeholder = "",
                readOnly = true
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { saveProfile() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !isSaving,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CosmicAccent,
                    contentColor = Color.Black
                )
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text(stringResource(R.string.save), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    readOnly: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = CosmicAccent.copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            placeholder = { Text(placeholder, color = AiriTheme.outline) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CosmicAccent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                disabledBorderColor = Color.White.copy(alpha = 0.08f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                disabledTextColor = Color.White.copy(alpha = 0.45f)
            )
        )
    }
}
