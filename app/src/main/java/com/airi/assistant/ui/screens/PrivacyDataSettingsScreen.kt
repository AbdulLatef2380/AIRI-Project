package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.AiriTheme
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airi.assistant.R
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.util.ChatExporter
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.auth.identity.BiometricGatekeeper
import com.airi.assistant.domain.auth.DataDeletionCoordinator
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDataSettingsScreen(
    viewModel:  ChatViewModel,
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit,
    onLogout:   () -> Unit
) {
    val context      = LocalContext.current
    val messages     by viewModel.messages.collectAsState()
    val scope        = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    // Route deletion through DataDeletionCoordinator — never call AuthService
    // directly from UI. The coordinator orchestrates all 8 deletion steps
    // (WorkManager, Firebase, Room, disk, credentials, preferences, cache,
    // sign-out) and surfaces structured results so the UI can respond correctly
    // to each outcome without embedding any deletion business logic here.
    val coordinator = remember { ServiceLocator.dataDeletionCoordinator }

    var showDeleteDialog by remember { mutableStateOf(false) }

    val exportChatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        scope.launch {
            val success = uri != null && ChatExporter.exportToUri(context, uri, messages)
            snackbarHost.showSnackbar(
                if (success) context.getString(R.string.export_success)
                else         context.getString(R.string.export_failed)
            )
        }
    }
    val importChatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importChatJson(uri) { count ->
            scope.launch {
                snackbarHost.showSnackbar(
                    if (count > 0) context.getString(R.string.import_success, count)
                    else           context.getString(R.string.import_failed)
                )
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost   = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.65f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Text(stringResource(R.string.privacy_data_title), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsSurface {
                SettingsCategoryHeader(
                    icon  = Icons.Outlined.Security,
                    title = stringResource(R.string.data_controls)
                )
                Spacer(Modifier.height(8.dp))
                SettingsActionRow(
                    label    = stringResource(R.string.export_chats),
                    sublabel = stringResource(R.string.download_chat_history)
                ) { exportChatLauncher.launch(ChatExporter.buildFileName()) }
                Divider(
                    color    = AiriTheme.onBackground.copy(alpha = 0.06f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                SettingsActionRow(
                    label    = stringResource(R.string.import_chats),
                    sublabel = stringResource(R.string.import_chat_history)
                ) { importChatLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                Divider(
                    color    = AiriTheme.onBackground.copy(alpha = 0.06f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                SettingsActionRow(
                    label    = stringResource(R.string.clear_chat_history),
                    sublabel = stringResource(R.string.remove_from_display)
                ) { viewModel.clearMessages() }
                Divider(
                    color    = AiriTheme.onBackground.copy(alpha = 0.06f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                SettingsActionRow(
                    label       = stringResource(R.string.delete_account),
                    sublabel    = stringResource(R.string.delete_account_sublabel),
                    destructive = true
                ) { showDeleteDialog = true }
            }

            ObservabilitySection(onNavigate = onNavigate)

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest  = { showDeleteDialog = false },
            containerColor    = Color(0xFF12162E),
            titleContentColor = Color.White,
            textContentColor  = Color.White.copy(alpha = 0.7f),
            shape             = RoundedCornerShape(20.dp),
            title = {
                Text(stringResource(R.string.delete_account), fontWeight = FontWeight.Bold)
            },
            text = { Text(stringResource(R.string.delete_account_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            // ── AP-05: Biometric gate ──────────────────────────────
                            // Account deletion is irreversible (8-step wipe). Require
                            // biometric confirmation before proceeding.
                            val activity = context as? FragmentActivity
                            if (activity != null) {
                                val availability = BiometricGatekeeper.checkAvailability(activity)
                                if (availability == BiometricGatekeeper.Availability.NOT_ENROLLED) {
                                    snackbarHost.showSnackbar("Add a screen lock or fingerprint in device Settings to confirm account deletion.")
                                    return@launch
                                }
                                val confirmed = BiometricGatekeeper.authenticate(
                                    activity = activity,
                                    title    = "Confirm Account Deletion",
                                    subtitle = "This action is irreversible and cannot be undone."
                                )
                                if (!confirmed) return@launch
                            }
                            // ── Delegate to DataDeletionCoordinator ─────────────
                            // Orchestrates all 8 steps (WorkManager stop, Firebase
                            // deletion, Room wipe, filesystem wipe, credential wipe,
                            // preference reset, cache wipe, local sign-out).
                            when (val result = coordinator.deleteAccount()) {
                                is DataDeletionCoordinator.DeletionResult.Success -> {
                                    onLogout()
                                }
                                is DataDeletionCoordinator.DeletionResult.PartialSuccess -> {
                                    // Account is deleted server-side — navigate away.
                                    // Partial local failure is non-blocking for the user.
                                    onLogout()
                                }
                                is DataDeletionCoordinator.DeletionResult.FirebaseAuthFailed -> {
                                    val msg = if (result.requiresReauth)
                                        context.getString(R.string.delete_account_reauth_required)
                                    else
                                        result.message.ifBlank {
                                            context.getString(R.string.delete_account_error_generic)
                                        }
                                    snackbarHost.showSnackbar(
                                        message     = msg,
                                        actionLabel = context.getString(R.string.ok),
                                        duration    = SnackbarDuration.Long
                                    )
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC2222))
                ) { Text(stringResource(R.string.delete_account)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
                }
            }
        )
    }
}
