package com.airi.assistant.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.airi.assistant.auth.identity.BiometricGatekeeper
import com.airi.assistant.ui.theme.*
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

data class PermissionInfo(
    val permission:   String,
    val label:        String,
    val icon:         ImageVector,
    val iconTint:     Color,
    val rationale:    String,
    val whyNeeded:    String,
    val group:        String,
    val isDangerous:  Boolean = true,
    val isSpecial:    Boolean = false   // e.g. Accessibility, Notification Policy
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val allPermissions = remember {
        listOf(
            PermissionInfo(
                permission  = Manifest.permission.RECORD_AUDIO,
                label       = "Microphone",
                icon        = Icons.Outlined.Mic,
                iconTint    = Color(0xFF4CAF50),
                rationale   = "Required for voice commands, speech-to-text (Vosk), and wake-word detection (\"Hey AIRI\").",
                whyNeeded   = "Without this, AIRI cannot hear you. Voice input, live conversation, and hotword detection all depend on it.",
                group       = "Voice",
                isDangerous = true
            ),
            PermissionInfo(
                permission  = Manifest.permission.POST_NOTIFICATIONS,
                label       = "Notifications",
                icon        = Icons.Outlined.Notifications,
                iconTint    = Color(0xFF7B8DFF),
                rationale   = "Send alerts when scheduled tasks complete, agents finish work, or usage limits are approaching.",
                whyNeeded   = "AIRI runs tasks in the background. Without this you won't know when they finish.",
                group       = "Notifications",
                isDangerous = true
            ),
            PermissionInfo(
                permission  = Manifest.permission.CAMERA,
                label       = "Camera",
                icon        = Icons.Outlined.CameraAlt,
                iconTint    = Color(0xFFFF9800),
                rationale   = "Take photos to send to AIRI for visual analysis and vision-based queries.",
                whyNeeded   = "Only used when you tap the camera button in chat. AIRI never accesses your camera automatically.",
                group       = "Camera",
                isDangerous = true
            ),
            PermissionInfo(
                permission  = Manifest.permission.READ_CALENDAR,
                label       = "Read Calendar",
                icon        = Icons.Outlined.CalendarMonth,
                iconTint    = Color(0xFF00BCD4),
                rationale   = "Allow AIRI to read your schedule to help plan tasks and answer \"what's on my calendar today?\"",
                whyNeeded   = "Used by the CalendarTool and ProductivityAgent. No calendar data is sent to the cloud.",
                group       = "Calendar",
                isDangerous = true
            ),
            PermissionInfo(
                permission  = Manifest.permission.WRITE_CALENDAR,
                label       = "Write Calendar",
                icon        = Icons.Outlined.EditCalendar,
                iconTint    = Color(0xFF00BCD4),
                rationale   = "Allow AIRI to create calendar events when you ask it to schedule something.",
                whyNeeded   = "Only triggered when you explicitly ask AIRI to add an event. No silent writes.",
                group       = "Calendar",
                isDangerous = true
            ),
            PermissionInfo(
                permission  = Manifest.permission.READ_CONTACTS,
                label       = "Contacts",
                icon        = Icons.Outlined.Contacts,
                iconTint    = Color(0xFF9C27B0),
                rationale   = "Look up contact names and details when you ask AIRI to message or call someone.",
                whyNeeded   = "The ContactsConnector uses this. Contact data stays on-device and is never synced.",
                group       = "Contacts",
                isDangerous = true
            ),
            PermissionInfo(
                permission  = Manifest.permission.READ_EXTERNAL_STORAGE,
                label       = "Read Storage",
                icon        = Icons.Outlined.FolderOpen,
                iconTint    = Color(0xFFFF5722),
                rationale   = "Read local AI model files and user documents you share with AIRI.",
                whyNeeded   = "Required on Android 12 and below for loading GGUF model files from external storage.",
                group       = "Storage",
                isDangerous = true
            ),
            PermissionInfo(
                permission  = Manifest.permission.SCHEDULE_EXACT_ALARM,
                label       = "Exact Alarms",
                icon        = Icons.Outlined.Alarm,
                iconTint    = Color(0xFFFFD600),
                rationale   = "Schedule precise reminders and tasks at exact times you specify.",
                whyNeeded   = "Without this, reminders fire approximately — not at the exact time requested.",
                group       = "Alarms",
                isDangerous = false
            )
        )
    }

    // Special permissions (non-standard — checked differently)
    val specialPermissions = remember {
        listOf(
            PermissionInfo(
                permission  = "android.permission.BIND_ACCESSIBILITY_SERVICE",
                label       = "Accessibility Service",
                icon        = Icons.Outlined.Accessibility,
                iconTint    = Color(0xFF00E5FF),
                rationale   = "Allows AIRI to read the UI tree and perform actions (tap, swipe, type) on your behalf.",
                whyNeeded   = "The core automation layer. Without it AIRI cannot control apps. Enabled in Accessibility Settings.",
                group       = "Automation",
                isSpecial   = true
            ),
            PermissionInfo(
                permission  = "android.permission.FOREGROUND_SERVICE_MICROPHONE",
                label       = "Foreground Mic Service",
                icon        = Icons.Outlined.Mic,
                iconTint    = Color(0xFF4CAF50),
                rationale   = "Keeps the voice session alive when AIRI is in the background.",
                whyNeeded   = "LiveVoiceService requires this to maintain audio focus during barge-in mode.",
                group       = "Voice",
                isSpecial   = true
            )
        )
    }

    // Check grant status for each regular permission
    fun isGranted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    val groups = (allPermissions + specialPermissions).groupBy { it.group }

    var expandedGroups by remember { mutableStateOf(groups.keys.toSet()) }

    // Summary counts
    val grantedCount  = allPermissions.count { isGranted(it.permission) }
    val totalCount    = allPermissions.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.permissions_title), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = AiriTheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                Surface(
                    shape = AIRIShapes.md,
                    color = if (grantedCount == totalCount) SemanticSuccess.copy(0.10f)
                            else CosmicAccent.copy(0.10f),
                    modifier = Modifier.fillMaxWidth().border(
                        1.dp,
                        if (grantedCount == totalCount) SemanticSuccess.copy(0.30f)
                        else CosmicAccent.copy(0.30f),
                        AIRIShapes.md
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape)
                                .background(
                                    if (grantedCount == totalCount) SemanticSuccess.copy(0.20f)
                                    else CosmicAccent.copy(0.20f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "$grantedCount/$totalCount",
                                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = if (grantedCount == totalCount) SemanticSuccess else CosmicAccent
                            )
                        }
                        Column {
                            Text(
                                if (grantedCount == totalCount) "All permissions granted"
                                else "$grantedCount of $totalCount permissions granted",
                                fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                color = AiriTheme.onBackground
                            )
                            Text(
                                "AIRI requests only what it needs. No permission is used silently.",
                                fontSize = 12.sp, color = AiriTheme.onSurfaceVariant, lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = AIRIShapes.md,
                    border   = androidx.compose.foundation.BorderStroke(
                        1.dp, androidx.compose.ui.graphics.SolidColor(CosmicAccent.copy(0.5f))
                    )
                ) {
                    Icon(Icons.Filled.OpenInNew, null, tint = CosmicAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.open_app_settings), color = CosmicAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            groups.forEach { (groupName, perms) ->
                item(key = "group_$groupName") {
                    Surface(
                        shape    = AIRIShapes.md,
                        color    = AiriTheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            // Group header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedGroups = if (groupName in expandedGroups)
                                            expandedGroups - groupName
                                        else expandedGroups + groupName
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    groupName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = AiriTheme.onBackground, modifier = Modifier.weight(1f)
                                )
                                val groupGranted = perms.count { p ->
                                    if (p.isSpecial) false else isGranted(p.permission)
                                }
                                Text(
                                    "$groupGranted/${perms.filter { !it.isSpecial }.size}",
                                    fontSize = 12.sp, color = AiriTheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Icon(
                                    if (groupName in expandedGroups) Icons.Filled.ExpandLess
                                    else Icons.Filled.ExpandMore,
                                    null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(18.dp)
                                )
                            }

                            // Permission rows
                            AnimatedVisibility(
                                visible = groupName in expandedGroups,
                                enter   = expandVertically(),
                                exit    = shrinkVertically()
                            ) {
                                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                    perms.forEachIndexed { idx, perm ->
                                        if (idx > 0 || true) {
                                            Divider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                color = AiriTheme.outline
                                            )
                                        }
                                        PermissionRow(
                                            perm      = perm,
                                            isGranted = if (perm.isSpecial) false else isGranted(perm.permission)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun PermissionRow(perm: PermissionInfo, isGranted: Boolean) {
    var showRationale by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showRationale = !showRationale }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(AIRIShapes.sm)
                    .background(perm.iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(perm.icon, null, tint = perm.iconTint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(perm.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AiriTheme.onBackground)
                Text(perm.rationale, fontSize = 11.sp, color = AiriTheme.onSurfaceVariant,
                    lineHeight = 15.sp, maxLines = if (showRationale) Int.MAX_VALUE else 2)
            }
            Spacer(Modifier.width(8.dp))
            if (perm.isSpecial) {
                // : For accessibility (full device control), show a biometric-gated
                // "Enable" button instead of a static badge. Non-accessibility special
                // permissions retain the old badge.
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                if (perm.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE") {
                    Surface(
                        shape = AIRIShapes.xs,
                        color = CosmicAccent.copy(0.12f),
                        modifier = Modifier
                            .border(0.5.dp, CosmicAccent.copy(0.35f), AIRIShapes.xs)
                            .clickable {
                                scope.launch {
                                    val activity = ctx as? FragmentActivity
                                    if (activity != null) {
                                        val avail = BiometricGatekeeper.checkAvailability(activity)
                                        if (avail == BiometricGatekeeper.Availability.NOT_ENROLLED) {
                                            // Cannot gate — proceed anyway (no enrolled biometric)
                                            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                            return@launch
                                        }
                                        val passed = BiometricGatekeeper.authenticate(
                                            activity = activity,
                                            title    = "Enable Accessibility Service",
                                            subtitle = "This grants AIRI control over your device UI."
                                        )
                                        if (passed) ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    } else {
                                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    }
                                }
                            }
                    ) {
                        Text("Enable", fontSize = 10.sp, color = CosmicAccent,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                    }
                } else {
                    Surface(
                        shape = AIRIShapes.xs,
                        color = SemanticWarn.copy(0.12f),
                        modifier = Modifier.border(0.5.dp, SemanticWarn.copy(0.3f), AIRIShapes.xs)
                    ) {
                        Text(stringResource(R.string.permissions_special_badge), fontSize = 10.sp, color = SemanticWarn,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                    }
                }
            } else {
                Icon(
                    if (isGranted) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = if (isGranted) "Granted" else "Not granted",
                    tint = if (isGranted) SemanticSuccess else SemanticError.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        AnimatedVisibility(visible = showRationale) {
            Surface(
                shape    = AIRIShapes.sm,
                color    = CosmicAccent.copy(0.06f),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).border(
                    0.5.dp, CosmicAccent.copy(0.2f), AIRIShapes.sm
                )
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.permissions_why_needed), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CosmicAccent)
                    Text(perm.whyNeeded, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant, lineHeight = 17.sp)
                }
            }
        }
    }
}
