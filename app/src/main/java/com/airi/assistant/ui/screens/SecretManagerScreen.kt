package com.airi.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.security.SecureApiKeyStore
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent

/**
 * Task 9.1 – Secret Manager Screen.
 * Lists all CloudProvider API keys with set/clear controls.
 * Uses SecureApiKeyStore — does NOT duplicate storage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretManagerScreen(onBack: () -> Unit) {
    val context  = LocalContext.current
    val keyStore = remember { SecureApiKeyStore(context) }

    // Providers that have user-facing API keys (exclude internal-only providers)
    val providers = remember {
        listOf(
            CloudProvider.OPENAI,
            CloudProvider.GEMINI,
            CloudProvider.ANTHROPIC,
            CloudProvider.OPENROUTER,
            CloudProvider.KIMI,
            CloudProvider.BRAVE
        )
    }

    // Track key state per provider (null = not set)
    val keyStates = remember {
        providers.associateWith { p ->
            mutableStateOf(keyStore.getKey(p))
        }.toMutableMap()
    }

    var editingProvider by remember { mutableStateOf<CloudProvider?>(null) }
    var editText        by remember { mutableStateOf("") }
    var showKey         by remember { mutableStateOf(false) }

    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (editingProvider != null) {
        AlertDialog(
            onDismissRequest = { editingProvider = null; editText = "" },
            title = { Text("Set API Key — ${editingProvider?.displayName.orEmpty()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter your ${editingProvider?.displayName.orEmpty()} API key:", fontSize = 13.sp)
                    OutlinedTextField(
                        value          = editText,
                        onValueChange  = { editText = it },
                        singleLine     = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = editingProvider ?: return@TextButton
                    if (editText.isNotBlank()) {
                        keyStore.saveKey(p, editText.trim())
                        keyStates[p]?.value = editText.trim()
                    }
                    editingProvider = null
                    editText = ""
                    showKey = false
                }) { Text("Save", color = CosmicAccent) }
            },
            dismissButton = {
                TextButton(onClick = { editingProvider = null; editText = "" }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secret Manager", color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = AiriTheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = AiriTheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "API keys are stored in Android EncryptedSharedPreferences and never transmitted except to their respective API.",
                    fontSize = 12.sp,
                    color = AiriTheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(providers) { provider ->
                val currentKey = keyStates[provider]?.value
                val isSet      = !currentKey.isNullOrBlank()
                val preview    = if (isSet) "•••••••${currentKey!!.takeLast(4)}" else "Not set"

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AiriTheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(provider.displayName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AiriTheme.onBackground)
                            Text(
                                preview,
                                fontSize = 12.sp,
                                color = if (isSet) CosmicAccent else AiriTheme.onSurfaceVariant
                            )
                        }
                        // Edit button
                        IconButton(onClick = {
                            editText = ""
                            showKey  = false
                            editingProvider = provider
                        }) {
                            Icon(Icons.Outlined.Edit, "Set key", tint = CosmicAccent, modifier = Modifier.size(20.dp))
                        }
                        // Clear button
                        if (isSet) {
                            IconButton(onClick = {
                                keyStore.clearKey(provider)
                                keyStates[provider]?.value = null
                            }) {
                                Icon(Icons.Outlined.Delete, "Clear key",
                                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
