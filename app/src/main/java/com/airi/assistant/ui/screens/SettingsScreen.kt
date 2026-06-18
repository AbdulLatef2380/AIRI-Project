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
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel:  ChatViewModel,
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit,
    onLogout:   () -> Unit
) {
    val user      = remember { FirebaseAuth.getInstance().currentUser }
    val email     = user?.email ?: "guest"
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Text(
                        text = "الإعدادات",
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
            // ── Group 1: Core features ─────────────────────────────────────
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.History,
                    iconTint = Color(0xFF7B8DFF),
                    label    = "المهام المجدولة",
                    onClick  = { onNavigate(AiriRoute.AGENT_TASKS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Psychology,
                    iconTint = Color(0xFFB0B8CC),
                    label    = "معرفة",
                    onClick  = { onNavigate(AiriRoute.MEMORY) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Settings,
                    iconTint = Color(0xFFB0B8CC),
                    label    = "ضوابط البيانات",
                    onClick  = { onNavigate(AiriRoute.SETTINGS_PRIVACY) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Star,
                    iconTint = Color(0xFFB0B8CC),
                    label    = "المهارات",
                    onClick  = { onNavigate(AiriRoute.SKILL_MANAGER) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Hub,
                    iconTint = Color(0xFFB0B8CC),
                    label    = "موصلات",
                    onClick  = { onNavigate(AiriRoute.CONNECTORS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Extension,
                    iconTint = Color(0xFFB0B8CC),
                    label    = "التكاملات",
                    onClick  = { onNavigate(AiriRoute.INTEGRATIONS) }
                )
            }

            // ── Group 2: Appearance & API ─────────────────────────────────
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.Language,
                    iconTint = Color(0xFFB0B8CC),
                    label    = "اللغة",
                    trailing = "العربية",
                    onClick  = { onNavigate(AiriRoute.SETTINGS_GENERAL) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Palette,
                    iconTint = Color(0xFFB0B8CC),
                    label    = "المظهر",
                    trailing = "اتباع النظام",
                    onClick  = { onNavigate(AiriRoute.SETTINGS_CUSTOMIZATION) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.AutoAwesome,
                    iconTint = Color(0xFF7C4DFF),
                    label    = "AI Library",
                    trailing = "Smart Routing",
                    onClick  = { onNavigate(AiriRoute.MODEL_LIBRARY) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Mic,
                    iconTint = Color(0xFF4CAF50),
                    label    = "الصوت وكلمة التنبيه",
                    onClick  = { onNavigate(AiriRoute.VOICE_SETTINGS) }
                )
            }

            // ── Group 3: Account & System ─────────────────────────────────
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.Token,
                    iconTint = Color(0xFF7C6AF7),
                    label    = "Credits & Usage",
                    onClick  = { onNavigate(AiriRoute.CREDITS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Security,
                    iconTint = Color(0xFF22C55E),
                    label    = "Permissions",
                    onClick  = { onNavigate(AiriRoute.PERMISSIONS_SCREEN) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.SystemUpdate,
                    iconTint = Color(0xFF06B6D4),
                    label    = "Updates",
                    onClick  = { onNavigate(AiriRoute.UPDATE_SCREEN) }
                )
            }

            // ── Group: Automation & Payments ──────────────────────────────
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.AutoAwesome,
                    iconTint = Color(0xFFFF4A00),
                    label    = "Zapier & IFTTT",
                    onClick  = { onNavigate(AiriRoute.ZAPIER_IFTTT) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.CreditCard,
                    iconTint = Color(0xFF635BFF),
                    label    = "Buy Credits / Premium",
                    onClick  = { onNavigate(AiriRoute.STRIPE_PAYMENT) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.ReceiptLong,
                    iconTint = Color(0xFF22C55E),
                    label    = "Billing History",
                    onClick  = { onNavigate(AiriRoute.BILLING_HISTORY) }
                )
            }

            // ── Group: Skills Ecosystem ───────────────────────────────────
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.Store,
                    iconTint = Color(0xFF7C6AF7),
                    label    = "Skill Marketplace",
                    onClick  = { onNavigate(AiriRoute.MARKETPLACE) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Groups,
                    iconTint = Color(0xFF06B6D4),
                    label    = "Community Skills",
                    onClick  = { onNavigate(AiriRoute.COMMUNITY_SKILLS) }
                )
            }

            // ── Group 3: Developer & Runtime Tools ────────────────────────
            // These tools were previously unreachable from any UI path.
            // Now exposed here so developers and power users can access
            // the terminal, workspace, sandbox, diagnostics, and agent
            // observability tools that already exist in the project.
            SettingsGroup {
                SettingsNavItem(
                    icon     = Icons.Outlined.Terminal,
                    iconTint = Color(0xFF80CBC4),
                    label    = "الطرفية",
                    onClick  = { onNavigate(AiriRoute.TERMINAL) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Workspaces,
                    iconTint = Color(0xFF80CBC4),
                    label    = "مساحة العمل",
                    onClick  = { onNavigate(AiriRoute.WORKSPACE) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Science,
                    iconTint = Color(0xFF80CBC4),
                    label    = "Sandbox",
                    onClick  = { onNavigate(AiriRoute.SANDBOX_WORKSPACE) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Analytics,
                    iconTint = Color(0xFF80CBC4),
                    label    = "تشخيصات التنفيذ",
                    onClick  = { onNavigate(AiriRoute.EXEC_DIAGNOSTICS) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Monitor,
                    iconTint = Color(0xFF80CBC4),
                    label    = "الأداء",
                    onClick  = { onNavigate(AiriRoute.PERFORMANCE) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Visibility,
                    iconTint = Color(0xFF80CBC4),
                    label    = "مراقبة الوكيل",
                    onClick  = { onNavigate(AiriRoute.OBSERVABILITY) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.Code,
                    iconTint = Color(0xFF80CBC4),
                    label    = "مركز المطور",
                    onClick  = { onNavigate(AiriRoute.DEVELOPER_CENTER) }
                )
                SettingsDivider()
                SettingsNavItem(
                    icon     = Icons.Outlined.BugReport,
                    iconTint = Color(0xFF80CBC4),
                    label    = "لوحة التصحيح",
                    onClick  = { onNavigate(AiriRoute.DEBUG_PANEL) }
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable settings components
// ─────────────────────────────────────────────────────────────────────────────

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
