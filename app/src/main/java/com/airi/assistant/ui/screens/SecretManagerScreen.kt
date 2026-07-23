package com.airi.assistant.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.airi.assistant.R
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.security.SecureApiKeyStore
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretManagerScreen(onBack: () -> Unit) {
    val context  = LocalContext.current
    val keyStore = remember { SecureApiKeyStore(context) }
    val scope    = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val providers = remember {
        listOf(
            CloudProvider.OPENAI,
            CloudProvider.ANTHROPIC,
            CloudProvider.GEMINI,
            CloudProvider.OPENROUTER,
            CloudProvider.KIMI,
            CloudProvider.BRAVE
        )
    }

    val keyStates = remember {
        providers.associateWith { p -> mutableStateOf(keyStore.getKey(p)) }.toMutableMap()
    }

    var editingProvider by remember { mutableStateOf<CloudProvider?>(null) }
    var editText        by remember { mutableStateOf("") }
    var showKey         by remember { mutableStateOf(false) }
    var showDeleteFor   by remember { mutableStateOf<CloudProvider?>(null) }

    // Biometric availability check — performed once
    val biometricAvailable = remember {
        val bm = BiometricManager.from(context)
        bm.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Run BiometricPrompt if hardware is available; invoke [onSuccess] immediately otherwise.
     * Errors and denials are surfaced as snackbar messages.
     */
    fun authenticateThen(onSuccess: () -> Unit) {
        if (!biometricAvailable) { onSuccess(); return }
        val activity = context as? FragmentActivity ?: run { onSuccess(); return }
        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    scope.launch { snackbar.showSnackbar(errString.toString()) }
                }
            }
            override fun onAuthenticationFailed() {
                scope.launch {
                    snackbar.showSnackbar(context.getString(R.string.secret_manager_biometric_cancel))
                }
            }
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.secret_manager_biometric_title))
            .setSubtitle(context.getString(R.string.secret_manager_biometric_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }

    // Delete confirmation dialog
    showDeleteFor?.let { provider ->
        AlertDialog(
            onDismissRequest = { showDeleteFor = null },
            containerColor   = SurfaceFloating,
            shape            = AIRIShapes.xl,
            icon = {
                Icon(Icons.Outlined.Delete, null, tint = SemanticError, modifier = Modifier.size(28.dp))
            },
            title = {
                Text(
                    stringResource(R.string.secret_manager_remove_title, provider.displayName),
                    color = AiriTheme.onBackground,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text  = {
                Text(
                    stringResource(R.string.secret_manager_remove_body, provider.displayName),
                    color = AiriTheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        keyStore.clearKey(provider)
                        keyStates[provider]?.value = null
                        showDeleteFor = null
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.secret_manager_key_deleted)) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SemanticError),
                    shape  = AIRIShapes.md
                ) { Text(stringResource(R.string.delete), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFor = null }) {
                    Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
                }
            }
        )
    }

    // Edit / set key dialog
    editingProvider?.let { provider ->
        AlertDialog(
            onDismissRequest = { editingProvider = null; editText = ""; showKey = false },
            containerColor   = SurfaceFloating,
            shape            = AIRIShapes.xl,
            icon = {
                Box(
                    modifier = Modifier.size(40.dp).clip(AIRIShapes.md).background(CosmicAccent.copy(0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Key, null, tint = CosmicAccent, modifier = Modifier.size(20.dp))
                }
            },
            title = {
                Text(
                    stringResource(R.string.secret_manager_set_key_title, provider.displayName),
                    color = AiriTheme.onBackground,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.secret_manager_enter_key_hint, provider.displayName),
                        color = AiriTheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value              = editText,
                        onValueChange      = { editText = it },
                        singleLine         = true,
                        placeholder        = { Text(stringResource(R.string.secret_manager_key_placeholder), color = AiriTheme.outline, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions    = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    stringResource(if (showKey) R.string.secret_manager_hide_key_cd else R.string.secret_manager_show_key_cd),
                                    tint = AiriTheme.onSurfaceVariant
                                )
                            }
                        },
                        label  = { Text(stringResource(R.string.secret_manager_api_key_label)) },
                        shape  = AIRIShapes.md,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = CosmicAccent,
                            unfocusedBorderColor = AiriTheme.outline,
                            focusedLabelColor    = CosmicAccent,
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
                        val p = editingProvider ?: return@Button
                        if (editText.isNotBlank()) {
                            keyStore.saveKey(p, editText.trim())
                            keyStates[p]?.value = editText.trim()
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.secret_manager_key_saved)) }
                        }
                        editingProvider = null; editText = ""; showKey = false
                    },
                    enabled = editText.isNotBlank(),
                    colors  = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                    shape   = AIRIShapes.md
                ) { Text(stringResource(R.string.secret_manager_save), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { editingProvider = null; editText = ""; showKey = false }) {
                    Text(stringResource(R.string.secret_manager_cancel), color = AiriTheme.onSurfaceVariant)
                }
            }
        )
    }

    Scaffold(
        containerColor = AiriTheme.background,
        snackbarHost   = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Security, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.secret_manager_title), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    shape = AIRIShapes.md,
                    color = CosmicAccent.copy(0.08f),
                    border = BorderStroke(0.5.dp, CosmicAccent.copy(0.20f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Outlined.Lock, null, tint = CosmicAccent, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                        Text(
                            stringResource(R.string.secret_manager_description),
                            fontSize = 12.sp,
                            color    = AiriTheme.onSurfaceVariant,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            items(providers) { provider ->
                val currentKey = keyStates[provider]?.value
                val isSet      = !currentKey.isNullOrBlank()
                val preview    = if (isSet) "••••••••${currentKey!!.takeLast(4)}" else null

                SecretKeyCard(
                    provider  = provider,
                    isSet     = isSet,
                    preview   = preview,
                    onEdit    = {
                        editText        = ""
                        showKey         = false
                        editingProvider = provider
                    },
                    onCopy    = {
                        if (!currentKey.isNullOrBlank()) {
                            authenticateThen {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("", currentKey).also { cd ->
                                    // Android 13+: mark clip as sensitive so the system
                                    // does not show a clipboard access toast with the key value
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        cd.description.extras = android.os.PersistableBundle().also {
                                            it.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                        }
                                    }
                                }
                                cm.setPrimaryClip(clip)
                                scope.launch { snackbar.showSnackbar(context.getString(R.string.secret_manager_copied)) }
                            }
                        }
                    },
                    onDelete  = { showDeleteFor = provider }
                )
            }
        }
    }
}

@Composable
private fun SecretKeyCard(
    provider: CloudProvider,
    isSet:    Boolean,
    preview:  String?,
    onEdit:   () -> Unit,
    onCopy:   () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape    = AIRIShapes.md,
        color    = SurfaceRaised,
        border   = BorderStroke(0.5.dp, if (isSet) CosmicAccent.copy(0.18f) else AiriTheme.outline.copy(0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(38.dp).clip(AIRIShapes.sm)
                    .background(if (isSet) CosmicAccent.copy(0.14f) else AiriTheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Key,
                    null,
                    tint = if (isSet) CosmicAccent else AiriTheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(provider.displayName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AiriTheme.onBackground)
                Spacer(Modifier.height(2.dp))
                if (isSet && preview != null) {
                    Text(
                        preview,
                        fontSize   = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color      = CosmicAccent.copy(0.80f)
                    )
                } else {
                    Surface(shape = AIRIShapes.xs, color = AiriTheme.outline.copy(0.12f)) {
                        Text(
                            stringResource(R.string.secret_manager_not_set),
                            fontSize = 11.sp,
                            color    = AiriTheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (isSet) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.ContentCopy, stringResource(R.string.secret_manager_copy_cd), tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.secret_manager_edit_cd), tint = CosmicAccent, modifier = Modifier.size(18.dp))
                }
                if (isSet) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.secret_manager_delete_cd), tint = SemanticError.copy(0.7f), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
