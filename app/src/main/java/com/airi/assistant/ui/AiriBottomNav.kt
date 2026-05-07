package com.airi.assistant.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.core.runtime.SessionStatus
import com.airi.assistant.ui.theme.BorderLight
import com.airi.assistant.ui.theme.PrimaryAccent
import com.airi.assistant.ui.theme.Surface1
import com.airi.assistant.ui.theme.TextTertiary
import kotlinx.coroutines.flow.MutableStateFlow

private data class NavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val relatedRoutes: Set<String> = emptySet()
)

private val TABS = listOf(
    NavTab(
        route          = AiriRoute.CHAT,
        label          = "Chat",
        selectedIcon   = Icons.Filled.Chat,
        unselectedIcon = Icons.Outlined.Chat,
        relatedRoutes  = setOf(AiriRoute.CHAT, AiriRoute.HISTORY)
    ),
    NavTab(
        route          = AiriRoute.TASK_DASHBOARD,
        label          = "Tasks",
        selectedIcon   = Icons.Filled.Task,
        unselectedIcon = Icons.Outlined.Task,
        relatedRoutes  = setOf(AiriRoute.TASK_DASHBOARD, AiriRoute.OBSERVABILITY)
    ),
    NavTab(
        route          = AiriRoute.SKILL_MANAGER,
        label          = "Explore",
        selectedIcon   = Icons.Filled.Explore,
        unselectedIcon = Icons.Outlined.Explore,
        relatedRoutes  = setOf(
            AiriRoute.SKILL_MANAGER,
            AiriRoute.INTEGRATIONS,
            AiriRoute.CONNECTORS,
            AiriRoute.TEMPLATES
        )
    ),
    NavTab(
        route          = AiriRoute.MODELS,
        label          = "Models",
        selectedIcon   = Icons.Filled.Memory,
        unselectedIcon = Icons.Outlined.Memory,
        relatedRoutes  = setOf(
            AiriRoute.MODELS,
            AiriRoute.PERFORMANCE,
            AiriRoute.MODEL_PERFORMANCE,
            AiriRoute.EXEC_DIAGNOSTICS
        )
    ),
    NavTab(
        route          = AiriRoute.SETTINGS,
        label          = "Profile",
        selectedIcon   = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        relatedRoutes  = setOf(
            AiriRoute.SETTINGS,
            AiriRoute.PROFILE,
            AiriRoute.MEMORY,
            AiriRoute.SETTINGS_GENERAL,
            AiriRoute.SETTINGS_AI_MODELS,
            AiriRoute.SETTINGS_CUSTOMIZATION,
            AiriRoute.SETTINGS_PRIVACY,
            AiriRoute.SETTINGS_ABOUT,
            AiriRoute.AGENT_CONTROL,
            AiriRoute.AGENT_LOGS,
            AiriRoute.PAYWALL,
            AiriRoute.REFERRALS,
            AiriRoute.DEBUG_PANEL,
            AiriRoute.DEBUG_SCREEN,
            AiriRoute.VOICE_SETTINGS
        )
    )
)

val BOTTOM_NAV_ROUTES: Set<String> = TABS.flatMap { it.relatedRoutes }.toSet() +
    TABS.map { it.route }

@Composable
fun AiriBottomNav(
    currentRoute: String?,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val arm = remember { runCatching { ServiceLocator.autonomousRuntimeManager }.getOrNull() }
    val armFlow = remember(arm) {
        arm?.sessions ?: MutableStateFlow(emptyList<com.airi.assistant.core.runtime.PersistentTaskSession>())
    }
    val armSessions by armFlow.collectAsState()
    val runningCount = armSessions.count { it.status == SessionStatus.RUNNING }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface1)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BorderLight)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                BottomNavItem(
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
                    modifier   = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    tab: NavTab,
    isSelected: Boolean,
    badgeCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) PrimaryAccent else TextTertiary,
        animationSpec = tween(durationMillis = 200),
        label = "navIconTint"
    )
    val labelColor by animateColorAsState(
        targetValue = if (isSelected) PrimaryAccent else TextTertiary,
        animationSpec = tween(durationMillis = 200),
        label = "navLabelColor"
    )
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication        = null,
                onClick           = onClick
            )
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(PrimaryAccent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = tab.selectedIcon,
                        contentDescription = tab.label,
                        tint               = iconTint,
                        modifier           = Modifier.size(20.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = tab.unselectedIcon,
                        contentDescription = tab.label,
                        tint               = iconTint,
                        modifier           = Modifier.size(20.dp)
                    )
                }
            }
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(PrimaryAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text       = if (badgeCount > 9) "9+" else "$badgeCount",
                        fontSize   = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White
                    )
                }
            }
        }
        Text(
            text       = tab.label,
            color      = labelColor,
            fontSize   = 10.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines   = 1
        )
    }
}
