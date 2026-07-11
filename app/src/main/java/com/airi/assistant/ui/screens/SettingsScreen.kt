package com.airi.assistant.ui.screens

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
import androidx.compose.material.icons.filled.ArrowBack
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
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.components.PremiumBadge
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicBlack
import com.airi.assistant.ui.theme.DividerColor
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
    // Task 3: Route through AuthService instead of direct FirebaseAuth.getInstance() call.
    val authService = remember { ServiceLocator.authService }
    val email     = authService.currentUser()?.email ?: "guest"
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
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
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
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = Color(0xFF3A2800),
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
                            tint = Color(0xFFFFB340),
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                "Secure storage unavailable",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFFB340)
                            )
                            Text(
                                "Android Keystore failed to initialize. Connector tokens and API keys cannot be persisted and will be lost when the app closes. Restart the device to restore encrypted storage.",
                                fontSize = 11.sp,
                                color = Color(0xFFFFB340).copy(0.75f),
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.History,
                    iconTint = Color(0xFF7B8DFF),
                    label    = stringResource(R.string.settings_agent_tasks),
                    onClick  = { onNavigate(AiriRoute.AGENT_TASKS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Psychology,
                    iconTint = Color(0xFFB0B8CC),
                    label    = stringResource(R.string.settings_knowledge),
                    onClick  = { onNavigate(AiriRoute.MEMORY) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Settings,
                    iconTint = Color(0xFFB0B8CC),
                    label    = stringResource(R.string.settings_data_controls),
                    onClick  = { onNavigate(AiriRoute.SETTINGS_PRIVACY) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Star,
                    iconTint = Color(0xFFB0B8CC),
                    label    = stringResource(R.string.settings_skills),
                    onClick  = { onNavigate(AiriRoute.SKILL_MANAGER) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Hub,
                    iconTint = Color(0xFFB0B8CC),
                    label    = stringResource(R.string.settings_connectors),
                    onClick  = { onNavigate(AiriRoute.CONNECTORS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Extension,
                    iconTint = Color(0xFFB0B8CC),
                    label    = stringResource(R.string.integrations),
                    onClick  = { onNavigate(AiriRoute.INTEGRATIONS) }
                )
            }
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.Language,
                    iconTint = Color(0xFFB0B8CC),
                    label    = stringResource(R.string.language),
                    trailing = LanguageManager.getLanguageOption(LanguageManager.getCurrentLanguage(context)).displayName,
                    onClick  = { onNavigate(AiriRoute.SETTINGS_GENERAL) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Palette,
                    iconTint = Color(0xFFB0B8CC),
                    label    = stringResource(R.string.appearance),
                    trailing = stringResource(R.string.settings_follow_system),
                    onClick  = { onNavigate(AiriRoute.SETTINGS_CUSTOMIZATION) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.AutoAwesome,
                    iconTint = Color(0xFF7C4DFF),
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
                    iconTint = Color(0xFF00BCD4),
                    label    = "AI Execution Settings",
                    trailing = "Mode, privacy, provider",
                    onClick  = { onNavigate(AiriRoute.SETTINGS_AI_MODELS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Mic,
                    iconTint = Color(0xFF4CAF50),
                    label    = stringResource(R.string.settings_voice_wakeword),
                    onClick  = { onNavigate(AiriRoute.VOICE_SETTINGS) }
                )
            }
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.Token,
                    iconTint = Color(0xFF7C6AF7),
                    label    = stringResource(R.string.settings_credits_usage),
                    onClick  = { onNavigate(AiriRoute.CREDITS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Security,
                    iconTint = Color(0xFF22C55E),
                    label    = stringResource(R.string.settings_permissions),
                    onClick  = { onNavigate(AiriRoute.PERMISSIONS_SCREEN) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.SystemUpdate,
                    iconTint = Color(0xFF06B6D4),
                    label    = stringResource(R.string.settings_updates),
                    onClick  = { onNavigate(AiriRoute.UPDATE_SCREEN) }
                )
            }
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.AutoAwesome,
                    iconTint = Color(0xFFFF4A00),
                    label    = stringResource(R.string.settings_zapier_ifttt),
                    onClick  = { onNavigate(AiriRoute.ZAPIER_IFTTT) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.CreditCard,
                    iconTint = Color(0xFF635BFF),
                    label    = stringResource(R.string.settings_buy_credits),
                    onClick  = { onNavigate(AiriRoute.STRIPE_PAYMENT) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.ReceiptLong,
                    iconTint = Color(0xFF22C55E),
                    label    = stringResource(R.string.settings_billing_history),
                    onClick  = { onNavigate(AiriRoute.BILLING_HISTORY) }
                )
            }
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.Store,
                    iconTint = Color(0xFF7C6AF7),
                    label    = stringResource(R.string.settings_skill_marketplace),
                    onClick  = { onNavigate(AiriRoute.MARKETPLACE) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Groups,
                    iconTint = Color(0xFF06B6D4),
                    label    = stringResource(R.string.settings_community_skills),
                    onClick  = { onNavigate(AiriRoute.COMMUNITY_SKILLS) }
                )
            }
            // These tools were previously unreachable from any UI path.
            // Now exposed here so developers and power users can access
            // the terminal, workspace, sandbox, diagnostics, and agent
            // observability tools that already exist in the project.
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.Terminal,
                    iconTint = Color(0xFF80CBC4),
                    label    = stringResource(R.string.settings_terminal),
                    onClick  = { onNavigate(AiriRoute.TERMINAL) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Workspaces,
                    iconTint = Color(0xFF80CBC4),
                    label    = stringResource(R.string.settings_workspace),
                    onClick  = { onNavigate(AiriRoute.WORKSPACE) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Science,
                    iconTint = Color(0xFF80CBC4),
                    label    = stringResource(R.string.settings_sandbox),
                    onClick  = { onNavigate(AiriRoute.SANDBOX_WORKSPACE) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Analytics,
                    iconTint = Color(0xFF80CBC4),
                    label    = stringResource(R.string.settings_exec_diagnostics),
                    onClick  = { onNavigate(AiriRoute.EXEC_DIAGNOSTICS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Monitor,
                    iconTint = Color(0xFF80CBC4),
                    label    = stringResource(R.string.performance),
                    onClick  = { onNavigate(AiriRoute.PERFORMANCE) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Visibility,
                    iconTint = Color(0xFF80CBC4),
                    label    = stringResource(R.string.settings_agent_observability),
                    onClick  = { onNavigate(AiriRoute.OBSERVABILITY) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Code,
                    iconTint = Color(0xFF80CBC4),
                    label    = stringResource(R.string.settings_developer_center),
                    onClick  = { onNavigate(AiriRoute.DEVELOPER_CENTER) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.BugReport,
                    iconTint = Color(0xFF80CBC4),
                    label    = stringResource(R.string.settings_debug_panel),
                    onClick  = { onNavigate(AiriRoute.DEBUG_PANEL) }
                )
                SettingsDivider()
                // Secret Manager
                SettingsNavItem(
                    icon     = Icons.Outlined.Key,
                    iconTint = Color(0xFFFFD54F),
                    label    = "Secret Manager",
                    onClick  = { onNavigate(AiriRoute.SECRET_MANAGER) }
                )
                SettingsDivider()
                // Security Scanner
                SettingsNavItem(
                    icon     = Icons.Outlined.Security,
                    iconTint = Color(0xFF80CBC4),
                    label    = "Security Scanner",
                    onClick  = { onNavigate(AiriRoute.SECURITY_SCANNER) }
                )
            }

            // : About AIRI — was unreachable; SETTINGS_ABOUT route now has a caller.
            SettingsGroup {
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Info,
                    iconTint = Color(0xFF90CAF9),
                    label    = "About AIRI",
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
            .clip(RoundedCornerShape(16.dp))
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
            .background(DividerColor)
    )
}

@Composable
fun SettingsNavItem(
    icon: ImageVector,
    iconTint: Color = Color.White.copy(alpha = 0.65f),
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

        // Label + badge (RTL: end side)
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
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
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = AiriTheme.outline.copy(alpha = 0.28f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun SettingsToggleItem(
    icon: ImageVector,
    iconTint: Color = Color.White.copy(alpha = 0.65f),
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
            textAlign = TextAlign.End
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor   = Color.White,
                checkedTrackColor   = CosmicAccent,
                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
            )
        )
    }
}
