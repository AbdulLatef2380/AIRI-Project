package com.airi.assistant.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Task
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.core.runtime.SessionStatus
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow

private data class NavTab(
    val route:          String,
    val labelAr:        String,
    val selectedIcon:   ImageVector,
    val unselectedIcon: ImageVector,
    val relatedRoutes:  Set<String> = emptySet()
)

private val TABS = listOf(
    NavTab(AiriRoute.CHAT,           "دردشة",   Icons.Filled.Chat,    Icons.Outlined.Chat,    setOf(AiriRoute.CHAT, AiriRoute.HISTORY)),
    NavTab(AiriRoute.TASK_DASHBOARD, "المهام",  Icons.Filled.Task,    Icons.Outlined.Task,    setOf(AiriRoute.TASK_DASHBOARD, AiriRoute.OBSERVABILITY)),
    NavTab(AiriRoute.SKILL_MANAGER,  "مهارات",  Icons.Filled.Explore, Icons.Outlined.Explore, setOf(AiriRoute.SKILL_MANAGER, AiriRoute.INTEGRATIONS, AiriRoute.CONNECTORS, AiriRoute.TEMPLATES)),
    NavTab(AiriRoute.MODELS,         "نماذج",   Icons.Filled.Memory,  Icons.Outlined.Memory,  setOf(AiriRoute.MODELS, AiriRoute.PERFORMANCE, AiriRoute.MODEL_PERFORMANCE, AiriRoute.EXEC_DIAGNOSTICS)),
    NavTab(AiriRoute.SETTINGS,       "إعدادات", Icons.Filled.Settings, Icons.Outlined.Settings, setOf(
        AiriRoute.SETTINGS, AiriRoute.PROFILE, AiriRoute.MEMORY,
        AiriRoute.SETTINGS_GENERAL, AiriRoute.SETTINGS_AI_MODELS,
        AiriRoute.SETTINGS_CUSTOMIZATION, AiriRoute.SETTINGS_PRIVACY,
        AiriRoute.SETTINGS_ABOUT, AiriRoute.AGENT_CONTROL, AiriRoute.AGENT_LOGS,
        AiriRoute.PAYWALL, AiriRoute.REFERRALS, AiriRoute.DEBUG_PANEL,
        AiriRoute.DEBUG_SCREEN, AiriRoute.VOICE_SETTINGS
    ))
)

val BOTTOM_NAV_ROUTES: Set<String> = TABS.flatMap { it.relatedRoutes }.toSet() + TABS.map { it.route }

@Composable
fun AiriBottomNav(
    currentRoute:  String?,
    navController: NavController,
    modifier:      Modifier = Modifier
) {
    val arm = remember { runCatching { ServiceLocator.autonomousRuntimeManager }.getOrNull() }
    val armFlow = remember(arm) {
        arm?.sessions ?: MutableStateFlow(emptyList<com.airi.assistant.core.runtime.PersistentTaskSession>())
    }
    val armSessions by armFlow.collectAsState()
    val runningCount = armSessions.count { it.status == SessionStatus.RUNNING }

    Column(modifier = modifier.fillMaxWidth()) {
        // Neural gradient separator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, BorderMid, Color.Transparent)
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface1)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TABS.forEach { tab ->
                val isSelected = currentRoute != null && (
                    currentRoute == tab.route ||
                    tab.relatedRoutes.any { r -> currentRoute.startsWith(r) }
                )
                NavTabItem(
                    tab        = tab,
                    isSelected = isSelected,
                    badgeCount = if (tab.route == AiriRoute.TASK_DASHBOARD) runningCount else 0,
                    onClick    = {
                        if (!isSelected) {
                            navController.navigate(tab.route) {
                                popUpTo(AiriRoute.CHAT) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavTabItem(
    tab: NavTab,
    isSelected: Boolean,
    badgeCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconTint by animateColorAsState(
        targetValue   = if (isSelected) Color.White else TextTertiary,
        animationSpec = tween(200),
        label         = "navIconTint"
    )
    val labelColor by animateColorAsState(
        targetValue   = if (isSelected) PrimaryAccent else TextTertiary,
        animationSpec = tween(200),
        label         = "navLabelColor"
    )
    val pillWidth by animateDpAsState(
        targetValue   = if (isSelected) 52.dp else 36.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "pillWidth"
    )
    val pillHeight = 34.dp
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Glow halo
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(pillWidth + 10.dp, pillHeight + 10.dp)
                        .drawBehind {
                            drawIntoCanvas { canvas ->
                                val paint = Paint().apply {
                                    asFrameworkPaint().apply {
                                        isAntiAlias = true
                                        color = android.graphics.Color.TRANSPARENT
                                        setShadowLayer(18f, 0f, 0f, PrimaryAccent.copy(alpha = 0.50f).toArgb())
                                    }
                                }
                                canvas.drawRoundRect(
                                    0f, 0f, size.width, size.height,
                                    17.dp.toPx(), 17.dp.toPx(), paint
                                )
                            }
                        }
                )
            }
            // Pill
            Box(
                modifier = Modifier
                    .size(pillWidth, pillHeight)
                    .clip(RoundedCornerShape(17.dp))
                    .background(
                        if (isSelected)
                            Brush.horizontalGradient(listOf(PrimaryAccent, AccentDark))
                        else
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                    contentDescription = tab.labelAr,
                    tint               = iconTint,
                    modifier           = Modifier.size(20.dp)
                )
            }
            // Badge
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(SemanticError)
                        .border(1.5.dp, Surface1, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = if (badgeCount > 9) "9+" else "$badgeCount",
                        fontSize   = 7.sp,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White
                    )
                }
            }
        }
        Text(
            text       = tab.labelAr,
            color      = labelColor,
            fontSize   = 10.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines   = 1
        )
    }
}
