package com.airi.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.theme.CosmicAccent
import androidx.compose.material3.MaterialTheme
import com.airi.assistant.ui.theme.NavBarBackground
import com.airi.assistant.ui.theme.NavIconActive
// Fixed invalid import
import com.airi.assistant.ui.theme.SurfaceRaised

enum class AiriNavTab {
    SKILLS,
    SCHEDULE,
    SETTINGS,
    CHAT,
    NEW
}

data class AiriNavItem(
    val tab: AiriNavTab,
    val icon: ImageVector,
    val labelRes: Int
)

@Composable
fun AiriBottomNavBar(
    selectedTab: AiriNavTab,
    visible: Boolean = true,
    onTabSelected: (AiriNavTab) -> Unit
) {
    val items = listOf(
        AiriNavItem(AiriNavTab.SKILLS,   Icons.Outlined.Star,             R.string.nav_skills),
        AiriNavItem(AiriNavTab.SCHEDULE, Icons.Outlined.History,    R.string.nav_schedule),
        AiriNavItem(AiriNavTab.SETTINGS, Icons.Outlined.Settings,         R.string.nav_settings),
        AiriNavItem(AiriNavTab.CHAT,     Icons.Outlined.SmartToy,         R.string.nav_chat),
        AiriNavItem(AiriNavTab.NEW,      Icons.Outlined.Forum, R.string.nav_new),
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it },
        exit  = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NavBarBackground)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outline)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    AiriNavTabItem(
                        item       = item,
                        isSelected = item.tab == selectedTab,
                        onClick    = { onTabSelected(item.tab) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AiriNavTabItem(
    item: AiriNavItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val iconTint  = if (isSelected) NavIconActive else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val labelColor = if (isSelected) NavIconActive else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(if (isSelected) 44.dp else 36.dp)
                .clip(if (isSelected) RoundedCornerShape(14.dp) else CircleShape)
                .background(
                    if (isSelected) CosmicAccent else Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.onSurface else iconTint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text       = stringResource(item.labelRes),
            color      = labelColor,
            fontSize   = 10.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines   = 1
        )
    }
}
