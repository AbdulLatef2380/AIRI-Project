package com.airi.assistant.ui.screens

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.ui.theme.*
import com.google.firebase.auth.userProfileChangeRequest
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

private const val PREFS_PROFILE   = "airi_profile"
private const val KEY_DISPLAY_NAME = "display_name"
private const val KEY_USERNAME     = "username"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack:   () -> Unit,
    onSignOut: (() -> Unit)? = null
) {
    val context     = LocalContext.current
    val prefs       = remember { context.getSharedPreferences(PREFS_PROFILE, Context.MODE_PRIVATE) }
    val authService = remember { ServiceLocator.authService }
    val fbUser      = authService.currentUser()
    val scope       = rememberCoroutineScope()
    val snackbar    = remember { SnackbarHostState() }

    val email    = fbUser?.email.orEmpty()
    val provider = fbUser?.providerData?.firstOrNull()?.providerId ?: "email"
    val memberSince = fbUser?.metadata?.creationTimestamp?.let {
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(it))
    } ?: "—"

    var displayName by remember {
        mutableStateOf(prefs.getString(KEY_DISPLAY_NAME, fbUser?.displayName.orEmpty()).orEmpty())
    }
    var username by remember {
        mutableStateOf(prefs.getString(KEY_USERNAME, "").orEmpty())
    }
    var isSaving          by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting        by remember { mutableStateOf(false) }

    // Delete account confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor   = SurfaceFloating,
            shape            = AIRIShapes.xl,
            icon = {
                Icon(Icons.Outlined.DeleteForever, null, tint = SemanticError, modifier = Modifier.size(32.dp))
            },
            title = {
                Text(stringResource(R.string.profile_delete_confirm_title), color = AiriTheme.onBackground, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(stringResource(R.string.profile_delete_confirm_body), color = AiriTheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeleting = true
                        showDeleteConfirm = false
                        fbUser?.delete()?.addOnCompleteListener { task ->
                            isDeleting = false
                            if (task.isSuccessful) {
                                onSignOut?.invoke()
                            } else {
                                scope.launch {
                                    snackbar.showSnackbar(task.exception?.message ?: "Delete failed")
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SemanticError),
                    shape  = AIRIShapes.md,
                    enabled = !isDeleting
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.profile_delete_confirm_action), fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
                }
            }
        )
    }

    fun saveProfile() {
        isSaving = true
        prefs.edit()
            .putString(KEY_DISPLAY_NAME, displayName.trim())
            .putString(KEY_USERNAME,     username.trim())
            .apply()
        fbUser?.updateProfile(userProfileChangeRequest { displayName = displayName.trim() })
            ?.addOnCompleteListener { isSaving = false
                scope.launch { snackbar.showSnackbar(context.getString(R.string.profile_saved_ok)) }
            } ?: run { isSaving = false }
    }

    Scaffold(
        containerColor = AiriTheme.background,
        snackbarHost   = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Text(stringResource(R.string.profile_title), fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // Avatar
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(CosmicAccent.copy(0.30f), SurfaceRaised)
                            )
                        )
                        .border(1.5.dp, CosmicAccent.copy(0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val initials = when {
                        displayName.isNotBlank() -> displayName.take(2).uppercase()
                        email.isNotBlank()       -> email.first().uppercaseChar().toString()
                        else                     -> "A"
                    }
                    Text(initials, color = CosmicAccent, fontWeight = FontWeight.Bold, fontSize = if (initials.length > 1) 26.sp else 32.sp)
                }
                // Edit photo indicator
                Box(
                    modifier = Modifier.size(26.dp).clip(CircleShape).background(CosmicAccent).border(2.dp, AiriTheme.background, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.profile_photo_cd), tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            if (email.isNotBlank()) {
                Text(email, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))

            // ── Editable fields ──
            SectionLabel(stringResource(R.string.personalization))

            ProfileTextField(
                label       = stringResource(R.string.profile_display_name_label),
                value       = displayName,
                onChange    = { displayName = it },
                placeholder = "Your name",
                icon        = Icons.Outlined.Person
            )
            Spacer(Modifier.height(10.dp))
            ProfileTextField(
                label       = stringResource(R.string.username),
                value       = username,
                onChange    = { username = it },
                placeholder = "@handle",
                icon        = Icons.Outlined.AlternateEmail
            )
            Spacer(Modifier.height(10.dp))
            ProfileTextField(
                label       = stringResource(R.string.profile_email_label),
                value       = email,
                onChange    = {},
                placeholder = "",
                icon        = Icons.Outlined.Email,
                readOnly    = true
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick  = ::saveProfile,
                enabled  = !isSaving,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape    = AIRIShapes.md,
                colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.White)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.profile_saving))
                } else {
                    Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Account information ──
            SectionLabel(stringResource(R.string.profile_account_section))

            InfoRow(
                label = stringResource(R.string.profile_login_provider),
                value = when {
                    provider.contains("google", ignoreCase = true) -> "Google"
                    provider.contains("password")                  -> "Email / Password"
                    else                                           -> provider.replaceFirstChar { it.uppercase() }
                },
                icon  = Icons.Outlined.AccountCircle
            )
            Divider(modifier = Modifier.padding(vertical = 1.dp), color = DividerColor)
            InfoRow(
                label = stringResource(R.string.profile_member_since),
                value = memberSince,
                icon  = Icons.Outlined.CalendarToday
            )

            Spacer(Modifier.height(28.dp))

            // ── Session actions ──
            SectionLabel(stringResource(R.string.profile_session_section))

            // Sign out
            Surface(
                onClick  = { onSignOut?.invoke() },
                shape    = AIRIShapes.md,
                color    = SurfaceRaised,
                border   = BorderStroke(0.5.dp, DividerColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.Logout, null, tint = AiriTheme.onBackground, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.profile_sign_out), color = AiriTheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Delete account (destructive — visually separated and subdued)
            Surface(
                onClick  = { showDeleteConfirm = true },
                shape    = AIRIShapes.md,
                color    = SemanticError.copy(0.06f),
                border   = BorderStroke(0.5.dp, SemanticError.copy(0.18f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.DeleteForever, null, tint = SemanticError.copy(0.75f), modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.profile_delete_account), color = SemanticError.copy(0.85f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, null, tint = SemanticError.copy(0.40f), modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text     = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color    = AiriTheme.onSurfaceVariant.copy(0.55f),
        letterSpacing = 0.8f.sp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    )
}

@Composable
private fun ProfileTextField(
    label:       String,
    value:       String,
    onChange:    (String) -> Unit,
    placeholder: String,
    icon:        ImageVector,
    readOnly:    Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = CosmicAccent.copy(0.75f), fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value         = value,
            onValueChange = onChange,
            readOnly      = readOnly,
            placeholder   = { Text(placeholder, color = AiriTheme.outline) },
            leadingIcon   = { Icon(icon, null, tint = if (readOnly) AiriTheme.outline else CosmicAccent.copy(0.60f), modifier = Modifier.size(18.dp)) },
            singleLine    = true,
            shape         = AIRIShapes.md,
            modifier      = Modifier.fillMaxWidth(),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = CosmicAccent,
                unfocusedBorderColor = DividerColor,
                disabledBorderColor  = DividerColor,
                focusedTextColor     = AiriTheme.onBackground,
                unfocusedTextColor   = AiriTheme.onBackground,
                disabledTextColor    = AiriTheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 13.sp, color = AiriTheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = AiriTheme.onBackground, fontWeight = FontWeight.Medium)
    }
}
