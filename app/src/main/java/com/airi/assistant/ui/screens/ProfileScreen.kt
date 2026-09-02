package com.airi.assistant.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.airi.assistant.R
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.ui.theme.*
import com.google.firebase.auth.userProfileChangeRequest
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.launch

/**
 * ProfileScreen — Account/Profile screen.
 *
 * ── FEATURES ──────────────────────────────────────────────────────────────
 *   • Profile photo: tappable → PickVisualMedia → cached locally per-account
 *   • Display name:  tappable row → inline edit dialog → saved to Auth + repo
 *   • Email:         read-only, sourced from Firebase Auth (never stale copy)
 *   • User ID:       read-only, Firebase UID (never token/secret)
 *   • Sign out:      calls canonical sign-out via [onSignOut]
 *   • Delete account: guarded by explicit confirmation → DataDeletionCoordinator
 *
 * ── ACCOUNT OWNERSHIP ─────────────────────────────────────────────────────
 *   Photo is stored at files/profile/{uid}/avatar.jpg — UID-scoped so
 *   different accounts never share a file path.
 *   State is observed from [UserProfileRepository] which is reset on sign-out
 *   (see AiriApp.kt canonical sign-out lambda).
 *
 * ── SECURITY ──────────────────────────────────────────────────────────────
 *   The Firebase UID is displayed — NOT an ID token or access token.
 *   No tokens or secrets appear in UI or Snackbar messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack:    () -> Unit,
    onSignOut: (() -> Unit)? = null
) {
    val context           = LocalContext.current
    val authService       = remember { ServiceLocator.authService }
    val profileRepository = remember { ServiceLocator.userProfileRepository }
    val profile           by profileRepository.profile.collectAsState()
    val fbUser            = authService.currentUser()
    val scope             = rememberCoroutineScope()
    val snackbar          = remember { SnackbarHostState() }

    // ── Derived account data ─────────────────────────────────────────────────
    // Email and UID come only from Firebase Auth — never a stale local copy.
    val email  = fbUser?.email.orEmpty()
    val uid    = fbUser?.uid.orEmpty()

    // Display name: prefers local repo (user may have set a rich display name
    // not yet synced to Auth), falls back to Firebase Auth displayName.
    val resolvedDisplayName = profile.displayName
        .ifBlank { fbUser?.displayName.orEmpty() }

    // Local photo path is profile-owned and UID-scoped (files/profile/{uid}/avatar.jpg).
    var localPhotoPath by remember(profile.localPhotoPath) {
        mutableStateOf(profile.localPhotoPath.ifBlank { null })
    }

    // ── UI state ─────────────────────────────────────────────────────────────
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeleting        by remember { mutableStateOf(false) }
    var isLoggingOut      by remember { mutableStateOf(false) }
    var showEditName      by remember { mutableStateOf(false) }
    var isSavingName      by remember { mutableStateOf(false) }
    var editNameValue     by remember { mutableStateOf("") }
    var editNameError     by remember { mutableStateOf<String?>(null) }

    // ── Photo picker ─────────────────────────────────────────────────────────
    // PickVisualMedia is the modern media picker (API 33+) with graceful
    // fallback on older devices via the Jetpack Activity Result library.
    val photoPickerCd = stringResource(R.string.profile_photo_cd)
    val pickPhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = cachePhotoForAccount(context, uri, uid)
            if (path != null) {
                localPhotoPath = path
                profileRepository.update { copy(localPhotoPath = path) }
                snackbar.showSnackbar(context.getString(R.string.profile_photo_saved))
            } else {
                snackbar.showSnackbar(context.getString(R.string.profile_save_failed))
            }
        }
    }

    // ── Name edit dialog ──────────────────────────────────────────────────────
    if (showEditName) {
        AlertDialog(
            onDismissRequest = { showEditName = false },
            containerColor   = SurfaceFloating,
            shape            = AIRIShapes.xl,
            title = {
                Text(
                    stringResource(R.string.profile_name_edit_title),
                    color      = AiriTheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value         = editNameValue,
                        onValueChange = {
                            editNameValue = it
                            editNameError = null
                        },
                        singleLine    = true,
                        isError       = editNameError != null,
                        supportingText = editNameError?.let { err ->
                            { Text(err, color = SemanticError, fontSize = 12.sp) }
                        },
                        shape         = AIRIShapes.md,
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = CosmicAccent,
                            unfocusedBorderColor = DividerColor,
                            focusedTextColor     = AiriTheme.onBackground,
                            unfocusedTextColor   = AiriTheme.onBackground
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val displayName = editNameValue
                        val username = profile.username
                        val trimmed = displayName.trim()
                        when {
                            trimmed.isBlank() -> editNameError =
                                context.getString(R.string.profile_name_empty_error)
                            trimmed.length > 60 -> editNameError =
                                context.getString(R.string.profile_name_too_long)
                            else -> {
                                isSavingName = true
                                profileRepository.update {
                                    copy(displayName = displayName.trim(), username = username.trim())
                                }
                                fbUser?.updateProfile(
                                    userProfileChangeRequest { this.displayName = trimmed }
                                )?.addOnCompleteListener { task ->
                                    isSavingName = false
                                    showEditName = false
                                    scope.launch {
                                        snackbar.showSnackbar(
                                            if (task.isSuccessful)
                                                context.getString(R.string.profile_saved_ok)
                                            else
                                                context.getString(R.string.profile_save_failed)
                                        )
                                    }
                                } ?: run {
                                    isSavingName = false
                                    showEditName = false
                                    scope.launch {
                                        snackbar.showSnackbar(context.getString(R.string.profile_saved))
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isSavingName,
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = CosmicAccent,
                        contentColor   = Color.White
                    ),
                    shape = AIRIShapes.md
                ) {
                    if (isSavingName) {
                        CircularProgressIndicator(
                            color       = Color.White,
                            modifier    = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.save), fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditName = false }) {
                    Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
                }
            }
        )
    }

    // ── Delete account confirmation dialog ────────────────────────────────────
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteConfirm = false },
            containerColor   = SurfaceFloating,
            shape            = AIRIShapes.xl,
            icon = {
                Icon(
                    Icons.Outlined.DeleteForever,
                    contentDescription = null,
                    tint     = SemanticError,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    stringResource(R.string.profile_delete_confirm_title),
                    color      = AiriTheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    stringResource(R.string.profile_delete_confirm_body),
                    color      = AiriTheme.onSurfaceVariant,
                    fontSize   = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeleting = true
                        showDeleteConfirm = false
                        scope.launch {
                            when (val result =
                                ServiceLocator.dataDeletionCoordinator.deleteAccount()) {
                                is com.airi.assistant.domain.auth.DataDeletionCoordinator.DeletionResult.Success,
                                is com.airi.assistant.domain.auth.DataDeletionCoordinator.DeletionResult.PartialSuccess -> {
                                    isDeleting = false
                                    onSignOut?.invoke()
                                }
                                is com.airi.assistant.domain.auth.DataDeletionCoordinator.DeletionResult.RemoteDataDeletionUnavailable -> {
                                    isDeleting = false
                                    snackbar.showSnackbar(result.message)
                                }
                                is com.airi.assistant.domain.auth.DataDeletionCoordinator.DeletionResult.RemoteDataDeletionFailed -> {
                                    isDeleting = false
                                    snackbar.showSnackbar(result.message)
                                }
                                is com.airi.assistant.domain.auth.DataDeletionCoordinator.DeletionResult.FirebaseAuthFailed -> {
                                    isDeleting = false
                                    snackbar.showSnackbar(
                                        if (result.requiresReauth)
                                            context.getString(R.string.delete_account_reauth_required)
                                        else
                                            context.getString(R.string.delete_account_error_generic)
                                    )
                                }
                            }
                        }
                    },
                    enabled = !isDeleting,
                    colors  = ButtonDefaults.buttonColors(containerColor = SemanticError),
                    shape   = AIRIShapes.md
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            color       = Color.White,
                            modifier    = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            stringResource(R.string.profile_delete_confirm_action),
                            fontWeight = FontWeight.Bold
                        )
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

    // ── Main scaffold ─────────────────────────────────────────────────────────
    Scaffold(
        containerColor = AiriTheme.background,
        snackbarHost   = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AiriTheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = AiriTheme.onBackground
                        )
                    }
                },
                title = {
                    Text(
                        stringResource(R.string.account_screen_title),
                        fontWeight = FontWeight.SemiBold,
                        color      = AiriTheme.onBackground
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
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // ── Avatar ───────────────────────────────────────────────────────
            val photoDesc = stringResource(R.string.profile_photo_cd)
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(CosmicAccent.copy(0.30f), SurfaceRaised)
                        )
                    )
                    .border(1.5.dp, CosmicAccent.copy(0.45f), CircleShape)
                    .clickable {
                        pickPhoto.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                    .semantics { contentDescription = photoDesc },
                contentAlignment = Alignment.Center
            ) {
                if (localPhotoPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(File(localPhotoPath!!))
                            .crossfade(true)
                            .build(),
                        contentDescription = stringResource(R.string.profile_photo_cd),
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                } else {
                    val initials = when {
                        resolvedDisplayName.isNotBlank() ->
                            resolvedDisplayName.take(2).uppercase()
                        email.isNotBlank() ->
                            email.first().uppercaseChar().toString()
                        else -> "A"
                    }
                    Text(
                        text       = initials,
                        color      = CosmicAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize   = if (initials.length > 1) 28.sp else 34.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text     = stringResource(R.string.profile_tap_change_photo),
                color    = AiriTheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            Spacer(Modifier.height(24.dp))

            // ── Account info card ─────────────────────────────────────────────
            Surface(
                shape  = AIRIShapes.lg,
                color  = SurfaceRaised,
                border = BorderStroke(0.5.dp, DividerColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // Name row — tappable → opens edit dialog
                    val nameDisplayed = resolvedDisplayName.ifBlank {
                        stringResource(R.string.profile_photo_placeholder)
                    }
                    AccountInfoRow(
                        label     = stringResource(R.string.display_name),
                        value     = nameDisplayed,
                        isEditable = true,
                        onClick   = {
                            editNameValue = resolvedDisplayName
                            editNameError = null
                            showEditName  = true
                        }
                    )

                    HorizontalDivider(
                        color     = DividerColor,
                        thickness = 0.5.dp,
                        modifier  = Modifier.padding(horizontal = 16.dp)
                    )

                    // Email row — read-only, from Firebase Auth only
                    AccountInfoRow(
                        label     = stringResource(R.string.profile_email_label),
                        value     = email.ifBlank { "—" },
                        isEditable = false,
                        maxLines  = 1
                    )

                    HorizontalDivider(
                        color     = DividerColor,
                        thickness = 0.5.dp,
                        modifier  = Modifier.padding(horizontal = 16.dp)
                    )

                    // User ID — read-only Firebase UID (NOT a token/secret)
                    AccountInfoRow(
                        label     = stringResource(R.string.profile_user_id_label),
                        value     = uid.ifBlank { "—" },
                        isEditable = false,
                        maxLines  = 1
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Sign Out ──────────────────────────────────────────────────────
            Surface(
                onClick  = {
                    if (!isLoggingOut) {
                        isLoggingOut = true
                        onSignOut?.invoke()
                    }
                },
                shape    = AIRIShapes.lg,
                color    = SurfaceRaised,
                border   = BorderStroke(0.5.dp, DividerColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp, horizontal = 16.dp),
                    contentAlignment  = Alignment.CenterEnd
                ) {
                    if (isLoggingOut) {
                        CircularProgressIndicator(
                            color       = AiriTheme.onBackground,
                            modifier    = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text       = stringResource(R.string.profile_sign_out),
                            color      = AiriTheme.onBackground,
                            fontWeight = FontWeight.Medium,
                            fontSize   = 15.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Delete Account ────────────────────────────────────────────────
            Surface(
                onClick  = { showDeleteConfirm = true },
                shape    = AIRIShapes.lg,
                color    = SemanticError.copy(0.06f),
                border   = BorderStroke(0.5.dp, SemanticError.copy(0.18f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text       = stringResource(R.string.profile_delete_account),
                        color      = SemanticError,
                        fontWeight = FontWeight.Medium,
                        fontSize   = 15.sp
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

// ── Account info row ───────────────────────────────────────────────────────────

@Composable
private fun AccountInfoRow(
    label:      String,
    value:      String,
    isEditable: Boolean,
    maxLines:   Int = Int.MAX_VALUE,
    onClick:    (() -> Unit)? = null
) {
    val modifier = if (isEditable && onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier              = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Label on the end (RTL: right side in Arabic, left in English)
        Text(
            text       = label,
            color      = AiriTheme.onSurfaceVariant,
            fontSize   = 13.sp,
            fontWeight = FontWeight.Normal,
            modifier   = Modifier.padding(start = 4.dp)
        )

        Spacer(Modifier.width(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text      = value,
                color     = AiriTheme.onBackground,
                fontSize  = 13.sp,
                maxLines  = maxLines,
                overflow  = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
            if (isEditable) {
                Icon(
                    imageVector         = Icons.Outlined.ChevronLeft,
                    contentDescription  = null,
                    tint                = AiriTheme.onSurfaceVariant.copy(0.45f),
                    modifier            = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ── Photo caching ──────────────────────────────────────────────────────────────

/**
 * Copies a user-selected image into private, UID-scoped persistent storage.
 *
 * Path: files/profile/{uid}/avatar.jpg
 *
 * UID-scoping prevents one account's photo from being rendered or overwritten
 * when a different account signs in on the same device.
 *
 * Returns null on:
 *   - non-image MIME type
 *   - file exceeding [MAX_PROFILE_PHOTO_BYTES]
 *   - any I/O error (read, write, or atomic rename failure)
 *   - blank uid (in which case the photo would not be account-scoped)
 */
internal fun cachePhotoForAccount(context: Context, uri: Uri, uid: String): String? {
    if (uid.isBlank()) return null
    val mimeType = context.contentResolver.getType(uri)
    if (mimeType?.startsWith("image/") != true) return null

    val directory   = File(context.filesDir, "profile/$uid").also { it.mkdirs() }
    val destination = File(directory, "avatar.jpg")
    val temporary   = File(directory, "avatar.tmp")

    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temporary).use { output ->
                val buffer     = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    totalBytes += read
                    if (totalBytes > MAX_PROFILE_PHOTO_BYTES) {
                        throw IllegalArgumentException("Profile image exceeds size limit")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: return null
        if (destination.exists()) destination.delete()
        if (!temporary.renameTo(destination)) return null
        destination.absolutePath
    } catch (_: Exception) {
        temporary.delete()
        null
    }
}

private const val MAX_PROFILE_PHOTO_BYTES = 5L * 1024L * 1024L
