package com.airi.assistant.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import com.airi.assistant.domain.permission.AccessibilityServiceState
import com.airi.assistant.domain.permission.PermissionDisplayPolicy
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
    val requiredOnDevice: Boolean = true,
    val includedInSummary: Boolean = true,
    val isSpecial:    Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val accessibilityRationale = stringResource(R.string.permissions_accessibility_rationale)
    val accessibilityWhyNeeded = stringResource(R.string.permissions_accessibility_why_needed)

    val allPermissions = listOf(
            PermissionInfo(
                permission  = Manifest.permission.RECORD_AUDIO,
                label       = stringResource(R.string.permissions_microphone_label),
                icon        = Icons.Outlined.Mic,
                iconTint    = Color(0xFF4CAF50),
                rationale   = stringResource(R.string.permissions_microphone_rationale),
                whyNeeded   = stringResource(R.string.permissions_microphone_why_needed),
                group       = stringResource(R.string.permissions_group_voice)
            ),
            PermissionInfo(
                permission  = Manifest.permission.POST_NOTIFICATIONS,
                label       = stringResource(R.string.permissions_notifications_label),
                icon        = Icons.Outlined.Notifications,
                iconTint    = Color(0xFF7B8DFF),
                rationale   = stringResource(R.string.permissions_notifications_rationale),
                whyNeeded   = stringResource(R.string.permissions_notifications_why_needed),
                group       = stringResource(R.string.permissions_group_notifications),
                requiredOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ),
            PermissionInfo(
                permission  = Manifest.permission.CAMERA,
                label       = stringResource(R.string.permissions_camera_label),
                icon        = Icons.Outlined.CameraAlt,
                iconTint    = Color(0xFFFF9800),
                rationale   = stringResource(R.string.permissions_camera_rationale),
                whyNeeded   = stringResource(R.string.permissions_camera_why_needed),
                group       = stringResource(R.string.permissions_group_camera),
            ),
            PermissionInfo(
                permission  = Manifest.permission.READ_CALENDAR,
                label       = stringResource(R.string.permissions_read_calendar_label),
                icon        = Icons.Outlined.CalendarMonth,
                iconTint    = Color(0xFF00BCD4),
                rationale   = stringResource(R.string.permissions_read_calendar_rationale),
                whyNeeded   = stringResource(R.string.permissions_read_calendar_why_needed),
                group       = stringResource(R.string.permissions_group_calendar),
            ),
            PermissionInfo(
                permission  = Manifest.permission.WRITE_CALENDAR,
                label       = stringResource(R.string.permissions_write_calendar_label),
                icon        = Icons.Outlined.EditCalendar,
                iconTint    = Color(0xFF00BCD4),
                rationale   = stringResource(R.string.permissions_write_calendar_rationale),
                whyNeeded   = stringResource(R.string.permissions_write_calendar_why_needed),
                group       = stringResource(R.string.permissions_group_calendar),
            ),
            PermissionInfo(
                permission  = Manifest.permission.READ_CONTACTS,
                label       = stringResource(R.string.permissions_contacts_label),
                icon        = Icons.Outlined.Contacts,
                iconTint    = Color(0xFF9C27B0),
                rationale   = stringResource(R.string.permissions_contacts_rationale),
                whyNeeded   = stringResource(R.string.permissions_contacts_why_needed),
                group       = stringResource(R.string.permissions_group_contacts),
            ),
            PermissionInfo(
                permission  = Manifest.permission.READ_EXTERNAL_STORAGE,
                label       = stringResource(R.string.permissions_storage_label),
                icon        = Icons.Outlined.FolderOpen,
                iconTint    = Color(0xFFFF5722),
                rationale   = stringResource(R.string.permissions_storage_rationale),
                whyNeeded   = stringResource(R.string.permissions_storage_why_needed),
                group       = stringResource(R.string.permissions_group_storage),
                requiredOnDevice = Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
            ),
        )

    // Service state is not a runtime permission; Android tracks it separately.
    val specialPermissions = listOf(
            PermissionInfo(
                permission  = "android.permission.BIND_ACCESSIBILITY_SERVICE",
                label       = stringResource(R.string.permissions_accessibility_label),
                icon        = Icons.Outlined.Accessibility,
                iconTint    = Color(0xFF00E5FF),
                rationale   = accessibilityRationale,
                whyNeeded   = accessibilityWhyNeeded,
                group       = stringResource(R.string.permissions_group_accessibility),
                includedInSummary = false,
                isSpecial   = true
            )
        )

    // Check grant status for each regular permission
    fun isGranted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    fun statusFor(permission: PermissionInfo): PermissionDisplayPolicy.Status =
        if (permission.isSpecial) {
            PermissionDisplayPolicy.status(
                requiredOnDevice = true,
                granted = AccessibilityServiceState.isEnabled(context)
            )
        } else {
            PermissionDisplayPolicy.status(
                requiredOnDevice = permission.requiredOnDevice,
                granted = isGranted(permission.permission)
            )
        }

    val allPermissionItems = allPermissions + specialPermissions
    val groups = allPermissionItems.groupBy { it.group }

    var expandedGroups by remember { mutableStateOf(groups.keys.toSet()) }

    val summaryStatuses = allPermissions.map(::statusFor)
    val grantedCount = PermissionDisplayPolicy.grantedRequiredCount(summaryStatuses)
    val totalCount = PermissionDisplayPolicy.requiredCount(summaryStatuses)

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
                                stringResource(R.string.permissions_summary_count, grantedCount, totalCount),
                                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = if (grantedCount == totalCount) SemanticSuccess else CosmicAccent
                            )
                        }
                        Column {
                            Text(
                                stringResource(
                                    if (grantedCount == totalCount) R.string.permissions_summary_all_enabled
                                    else R.string.permissions_summary_some_enabled,
                                    grantedCount,
                                    totalCount
                                ),
                                fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                color = AiriTheme.onBackground
                            )
                            Text(
                                stringResource(R.string.permissions_summary_description),
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
                                val groupStatuses = perms
                                    .filter { it.includedInSummary }
                                    .map(::statusFor)
                                val groupGranted = PermissionDisplayPolicy.grantedRequiredCount(groupStatuses)
                                val groupTotal = PermissionDisplayPolicy.requiredCount(groupStatuses)
                                if (groupStatuses.isNotEmpty()) {
                                    Text(
                                        stringResource(R.string.permissions_summary_count, groupGranted, groupTotal),
                                        fontSize = 12.sp, color = AiriTheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
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
                                            perm = perm,
                                            status = statusFor(perm)
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
private fun PermissionRow(perm: PermissionInfo, status: PermissionDisplayPolicy.Status) {
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
                // Accessibility opens Android Settings only after a visible user action.
                // It is not a blanket authorization for AgentLoop device actions.
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                if (perm.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" && status != PermissionDisplayPolicy.Status.GRANTED) {
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
                                            title    = ctx.getString(R.string.permissions_accessibility_enable_title),
                                            subtitle = ctx.getString(R.string.permissions_accessibility_enable_body)
                                        )
                                        if (passed) ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    } else {
                                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    }
                                }
                            }
                    ) {
                        Text(stringResource(R.string.permissions_enable_label), fontSize = 10.sp, color = CosmicAccent,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                    }
                } else {
                    Surface(
                        shape = AIRIShapes.xs,
                        color = SemanticSuccess.copy(0.12f),
                        modifier = Modifier.border(0.5.dp, SemanticSuccess.copy(0.3f), AIRIShapes.xs)
                    ) {
                        Text(stringResource(R.string.permissions_status_enabled), fontSize = 10.sp, color = SemanticSuccess,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                    }
                }
            } else {
                Icon(
                    imageVector = when (status) {
                        PermissionDisplayPolicy.Status.GRANTED -> Icons.Filled.CheckCircle
                        PermissionDisplayPolicy.Status.NOT_GRANTED -> Icons.Filled.Error
                        PermissionDisplayPolicy.Status.NOT_REQUIRED -> Icons.Outlined.Info
                    },
                    contentDescription = stringResource(
                        if (status == PermissionDisplayPolicy.Status.GRANTED) R.string.permissions_status_granted
                        else if (status == PermissionDisplayPolicy.Status.NOT_REQUIRED) R.string.permissions_status_not_required
                        else R.string.permissions_status_not_granted
                    ),
                    tint = when (status) {
                        PermissionDisplayPolicy.Status.GRANTED -> SemanticSuccess
                        PermissionDisplayPolicy.Status.NOT_GRANTED -> SemanticError.copy(alpha = 0.7f)
                        PermissionDisplayPolicy.Status.NOT_REQUIRED -> AiriTheme.onSurfaceVariant
                    },
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
