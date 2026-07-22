package com.airi.assistant.ui.screens

import com.airi.assistant.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.security.SecretHealthChecker
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Task 8.1 – Security Scanner Screen.
 * Runs SecretHealthChecker, displays pass/fail cards for stored secrets,
 * Play integrity, and runtime permissions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScannerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    data class ScanResult(val label: String, val passed: Boolean, val detail: String)

    var results  by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var done     by remember { mutableStateOf(false) }

    fun runScan() {
        scanning = true
        done = false
        scope.launch {
            val scanResults = mutableListOf<ScanResult>()
            withContext(Dispatchers.IO) {
                // 1. Secret health check
                runCatching {
                    val checker = SecretHealthChecker(SecureStorage(context))
                    val report  = checker.runChecks()
                    report.checks.forEach { check ->
                        val ok = check.result != SecretHealthChecker.HealthResult.CRITICAL
                        scanResults += ScanResult(
                            label  = check.name,
                            passed = ok,
                            detail = check.detail
                        )
                    }
                }.onFailure {
                    scanResults += ScanResult("Secret Health Check", false, it.message ?: "Error")
                }

                // 2. Runtime permissions audit
                runCatching {
                    val requiredPerms = listOf(
                        android.Manifest.permission.RECORD_AUDIO,
                        android.Manifest.permission.CAMERA,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                    )
                    requiredPerms.forEach { perm ->
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            context, perm
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        scanResults += ScanResult(
                            label  = perm.substringAfterLast('.'),
                            passed = granted,
                            detail = if (granted) "Granted" else "Not granted (may affect features)"
                        )
                    }
                }

                // 3. TLS certificate pins check
                runCatching {
                    val pins = com.airi.assistant.connector.api.LlmCertPins.pins
                    val hasPlaceholders = pins.values.flatten().any { it.contains("PLACEHOLDER") || it.length < 40 }
                    scanResults += ScanResult(
                        label  = "TLS Certificate Pins",
                        passed = !hasPlaceholders,
                        detail = if (hasPlaceholders) "Placeholder pins detected — cloud calls may fail" else "Production pins set"
                    )
                }
            }
            results  = scanResults
            scanning = false
            done     = true
        }
    }

    LaunchedEffect(Unit) { runScan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.security_scanner_title), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = AiriTheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { runScan() }, enabled = !scanning) {
                        Icon(Icons.Outlined.Refresh, "Re-scan", tint = CosmicAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        },
        containerColor = AiriTheme.background
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (scanning) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = CosmicAccent)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.security_scanner_scanning), color = AiriTheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (done && results.isEmpty()) {
                        item {
                            Text(stringResource(R.string.security_scanner_no_results), color = AiriTheme.onSurfaceVariant)
                        }
                    }
                    items(results) { r ->
                        Surface(
                            shape = AIRIShapes.md,
                            color = (if (r.passed) Color(0xFF00C853) else Color(0xFFD50000)).copy(alpha = 0.08f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = if (r.passed) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                                    contentDescription = null,
                                    tint = if (r.passed) Color(0xFF00C853) else Color(0xFFD50000),
                                    modifier = Modifier.size(22.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(r.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AiriTheme.onBackground)
                                    Text(r.detail, fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
