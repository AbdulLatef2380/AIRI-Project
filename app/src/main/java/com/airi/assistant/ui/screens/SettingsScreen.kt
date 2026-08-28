package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.airi.assistant.voice.VoskModelManager
import com.airi.assistant.system.DefaultAssistantManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ChevronRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.system.LanguageManager
import com.airi.assistant.system.LanguageOption
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.domain.monetization.PaywallTriggerEngine
import com.airi.assistant.domain.release.ReleaseScopePolicy
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.components.PremiumBadge
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicBlack
import androidx.compose.material3.MaterialTheme
import com.airi.assistant.ui.theme.SurfaceCard
import com.airi.assistant.ui.theme.SurfaceRaised
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.util.ChatExporter
import com.airi.assistant.util.ChatImporter
import com.airi.assistant.core.ServiceLocator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel:  ChatViewModel,
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit,
    onLogout:   () -> Unit
) {
    // Route through AuthService instead of direct FirebaseAuth.getInstance() call.
    val authService = remember { ServiceLocator.authService }
    val email     = authService.currentUser()?.email ?: stringResource(R.string.settings_guest)
    val isPremium = remember { viewModel.isPremium() }
    val scope     = rememberCoroutineScope()
    val snackbar  = remember { SnackbarHostState() }
    val context   = LocalContext.current

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background.copy(alpha = 0.92f)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        color = AiriTheme.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val isStorageEncrypted = remember {
                runCatching { ServiceLocator.secureStorage.isEncrypted }.getOrDefault(true)
            }
            if (!isStorageEncrypted) {
                Surface(
                    shape = AIRIShapes.md,
                    color = SemanticWarnContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = SemanticWarn,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                stringResource(R.string.settings_storage_unavailable_title),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SemanticWarn
                            )
                            Text(
                                stringResource(R.string.settings_storage_unavailable_body),
                                fontSize = 11.sp,
                                color = SemanticWarn.copy(alpha = 0.80f),
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.History,
                    iconTint = CosmicAccent,
                    label    = stringResource(R.string.settings_agent_tasks),
                    onClick  = { onNavigate(AiriRoute.AGENT_TASKS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Psychology,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_knowledge),
                    onClick  = { onNavigate(AiriRoute.MEMORY) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Settings,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_data_controls),
                    onClick  = { onNavigate(AiriRoute.SETTINGS_PRIVACY) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Star,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_skills),
                    onClick  = { onNavigate(AiriRoute.SKILL_MANAGER) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Hub,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_connectors),
                    onClick  = { onNavigate(AiriRoute.CONNECTORS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Extension,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.integrations),
                    onClick  = { onNavigate(AiriRoute.INTEGRATIONS) }
                )
            }
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.Language,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.language),
                    trailing = LanguageManager.getLanguageOption(LanguageManager.getCurrentLanguage(context)).displayName,
                    onClick  = { onNavigate(AiriRoute.SETTINGS_GENERAL) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Palette,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.appearance),
                    onClick  = { onNavigate(AiriRoute.SETTINGS_CUSTOMIZATION) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.AutoAwesome,
                    iconTint = CosmicAccent,
                    label    = stringResource(R.string.settings_ai_library),
                    trailing = stringResource(R.string.settings_smart_routing),
                    onClick  = { onNavigate(AiriRoute.MODEL_LIBRARY) }
                )
                SettingsDivider()
                // : AI Execution Settings — was unreachable; now connected to registered route.
                // Exposes ExecutionMode (LOCAL/CLOUD/HYBRID), PrivacyLevel, InternetPermission,
                // OfflineFallback, and PreferredProvider to the user.
                SettingsNavItem(
                    icon     = Icons.Outlined.Psychology,
                    iconTint = CosmicAccent,
                    label    = stringResource(R.string.settings_ai_execution),
                    trailing = stringResource(R.string.settings_ai_execution_summary),
                    onClick  = { onNavigate(AiriRoute.SETTINGS_AI_MODELS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Mic,
                    iconTint = SemanticSuccess,
                    label    = stringResource(R.string.settings_voice_wakeword),
                    onClick  = { onNavigate(AiriRoute.VOICE_SETTINGS) }
                )
            }
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.Token,
                    iconTint = CosmicAccent,
                    label    = stringResource(R.string.settings_credits_usage),
                    onClick  = { onNavigate(AiriRoute.CREDITS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Security,
                    iconTint = SemanticSuccess,
                    label    = stringResource(R.string.settings_permissions),
                    onClick  = { onNavigate(AiriRoute.PERMISSIONS_SCREEN) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.SystemUpdate,
                    iconTint = CosmicAccent,
                    label    = stringResource(R.string.settings_updates),
                    onClick  = { onNavigate(AiriRoute.UPDATE_SCREEN) }
                )
            }
            if (ReleaseScopePolicy.externalAutomationIntegrationsEnabled) {
                SettingsGroup {
                    SettingsNavItem(
                        icon = Icons.Outlined.AutoAwesome,
                        iconTint = SemanticWarn,
                        label = stringResource(R.string.settings_zapier_ifttt),
                        onClick = { onNavigate(AiriRoute.ZAPIER_IFTTT) }
                    )
                }
            }
            // Internal tooling is visible only in development builds. Release
            // routing independently fails closed for direct deep links.
            if (ReleaseScopePolicy.internalSurfacesEnabled) {
                SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.Terminal,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_terminal),
                    onClick  = { onNavigate(AiriRoute.TERMINAL) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Workspaces,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_workspace),
                    onClick  = { onNavigate(AiriRoute.WORKSPACE) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Science,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_sandbox),
                    onClick  = { onNavigate(AiriRoute.SANDBOX_WORKSPACE) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Analytics,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_exec_diagnostics),
                    onClick  = { onNavigate(AiriRoute.EXEC_DIAGNOSTICS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Monitor,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.performance),
                    onClick  = { onNavigate(AiriRoute.PERFORMANCE) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Visibility,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_agent_observability),
                    onClick  = { onNavigate(AiriRoute.OBSERVABILITY) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Code,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_developer_center),
                    onClick  = { onNavigate(AiriRoute.DEVELOPER_CENTER) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.BugReport,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_debug_panel),
                    onClick  = { onNavigate(AiriRoute.DEBUG_PANEL) }
                )
                SettingsDivider()
                // Secret Manager
                SettingsNavItem(
                    icon     = Icons.Outlined.Key,
                    iconTint = SemanticWarn,
                    label    = stringResource(R.string.settings_secret_manager),
                    onClick  = { onNavigate(AiriRoute.SECRET_MANAGER) }
                )
                SettingsDivider()
                // Security Scanner
                SettingsNavItem(
                    icon     = Icons.Outlined.Security,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_security_scanner),
                    onClick  = { onNavigate(AiriRoute.SECURITY_SCANNER) }
                )
                }
            }

            // : About AIRI — was unreachable; SETTINGS_ABOUT route now has a caller.
            SettingsGroup {
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Info,
                    iconTint = AiriTheme.onSurfaceVariant,
                    label    = stringResource(R.string.settings_about_airi),
                    onClick  = { onNavigate(AiriRoute.SETTINGS_ABOUT) }
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
// Reusable settings components
@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AIRIShapes.md)
            .background(AiriTheme.surface),
        content = content
    )
}

@Composable
fun ColumnScope.SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .padding(start = 52.dp)
            .background(AiriTheme.outline)
    )
}

@Composable
fun SettingsNavItem(
    icon: ImageVector,
    iconTint: Color = AiriTheme.onSurface.copy(alpha = 0.65f),
    label: String,
    badge: String? = null,
    trailing: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Leading icon
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }

        // The start edge follows the active layout direction, including RTL.
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .clip(AIRIShapes.xs)
                        .background(CosmicAccent)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(badge, color = AiriTheme.onBackground, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
            }
            if (trailing != null) {
                Text(trailing, color = AiriTheme.onSurfaceVariant.copy(alpha = 0.40f), fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, color = AiriTheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Normal)
        }

        // Trailing arrow
        Icon(
            Icons.AutoMirrored.Outlined.ChevronRight,
            contentDescription = null,
            tint = AiriTheme.outline.copy(alpha = 0.28f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun SettingsToggleItem(
    icon: ImageVector,
    iconTint: Color = AiriTheme.onSurface.copy(alpha = 0.65f),
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }

        Text(
            text = label,
            color = AiriTheme.onBackground,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            textAlign = TextAlign.Start
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor   = AiriTheme.onSurface,
                checkedTrackColor   = CosmicAccent,
                uncheckedThumbColor = AiriTheme.onSurface.copy(alpha = 0.6f),
                uncheckedTrackColor = AiriTheme.onSurface.copy(alpha = 0.15f)
            )
        )
    }
}
