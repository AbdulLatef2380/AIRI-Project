package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.AiriTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.PrivacyLevel
import com.airi.assistant.execution.prefs.ExecModePreferences
import com.airi.assistant.ui.theme.CosmicAccent
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

// ExecutionModePanel — User control layer for the Hybrid Execution system
private val CloudColor  = Color(0xFF29B6F6)  // light blue
private val LocalColor  = Color(0xFF66BB6A)  // green
private val HybridColor = Color(0xFFAB47BC)  // purple
private val WarnAmber   = Color(0xFFFFB74D)

@Composable
private fun dimWhite() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
@Composable
private fun subtleWhite() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

/**
 * Full execution mode control panel.
 */
@Composable
fun ExecutionModePanel(
    currentMode:            ExecutionMode,
    currentPrivacy:         PrivacyLevel,
    internetGranted:        Boolean,
    offlineFallback:        Boolean,
    preferredProvider:      CloudProvider,
    cloudTokensUsed:        Int,
    cloudTokensCap:         Int,
    onModeChange:           (ExecutionMode) -> Unit,
    onPrivacyChange:        (PrivacyLevel)  -> Unit,
    onInternetPermChange:   (Boolean)       -> Unit,
    onOfflineFallbackChange:(Boolean)       -> Unit,
    onProviderChange:       (CloudProvider) -> Unit
) {
    SettingsSurface {
        SettingsCategoryHeader(
            icon  = Icons.Outlined.Hub,
            title = "Execution Mode"
        )
        Spacer(Modifier.height(12.dp))
        ExecutionMode.values().forEach { mode ->
            ExecModeOption(
                mode       = mode,
                selected   = mode == currentMode,
                onSelected = { onModeChange(mode) }
            )
            if (mode != ExecutionMode.values().last()) Spacer(Modifier.height(6.dp))
        }
        AnimatedVisibility(
            visible = currentMode != ExecutionMode.LOCAL_ONLY,
            enter   = expandVertically(),
            exit    = shrinkVertically()
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AiriTheme.onBackground.copy(alpha = 0.05f))
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Internet Permission",
                            color = AiriTheme.onBackground,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Allow AIRI to reach cloud providers",
                            color = dimWhite(),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked         = internetGranted,
                        onCheckedChange = onInternetPermChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor  = CosmicAccent,
                            checkedTrackColor  = CosmicAccent.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = internetGranted && currentMode != ExecutionMode.LOCAL_ONLY,
            enter   = expandVertically(),
            exit    = shrinkVertically()
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AiriTheme.onBackground.copy(alpha = 0.05f))
                Spacer(Modifier.height(12.dp))

                // Provider preference
                SettingsCategoryHeader(icon = Icons.Outlined.Cloud, title = "Preferred Provider")
                Spacer(Modifier.height(8.dp))
                CloudProviderSelector(
                    current    = preferredProvider,
                    onSelected = onProviderChange
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AiriTheme.onBackground.copy(alpha = 0.05f))
                Spacer(Modifier.height(12.dp))

                // Offline fallback toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Offline Fallback",
                            color = AiriTheme.onBackground,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Use local model when cloud is unavailable",
                            color = dimWhite(),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked         = offlineFallback,
                        onCheckedChange = onOfflineFallbackChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CosmicAccent,
                            checkedTrackColor = CosmicAccent.copy(alpha = 0.3f)
                        )
                    )
                }

                // Cloud token usage
                if (cloudTokensCap > 0) {
                    Spacer(Modifier.height(12.dp))
                    CloudTokenUsageBar(used = cloudTokensUsed, cap = cloudTokensCap)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = AiriTheme.onBackground.copy(alpha = 0.05f))
        Spacer(Modifier.height(12.dp))
        SettingsCategoryHeader(icon = Icons.Outlined.Shield, title = "Privacy Level")
        Spacer(Modifier.height(8.dp))
        PrivacyLevel.values().forEach { level ->
            PrivacyLevelOption(
                level      = level,
                selected   = level == currentPrivacy,
                onSelected = { onPrivacyChange(level) }
            )
            if (level != PrivacyLevel.values().last()) Spacer(Modifier.height(4.dp))
        }

        // Warning when privacy overrides mode
        if (currentPrivacy == PrivacyLevel.MAXIMUM && currentMode != ExecutionMode.LOCAL_ONLY) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(WarnAmber.copy(alpha = 0.1f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Info, null,
                    tint = WarnAmber, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Maximum Privacy overrides ${currentMode.displayName} — all requests stay local",
                    color = WarnAmber,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun ExecModeOption(
    mode:       ExecutionMode,
    selected:   Boolean,
    onSelected: () -> Unit
) {
    val (icon, accentColor) = when (mode) {
        ExecutionMode.LOCAL_ONLY -> Icons.Outlined.PhoneAndroid to LocalColor
        ExecutionMode.CLOUD_ONLY -> Icons.Outlined.Cloud        to CloudColor
        ExecutionMode.HYBRID     -> Icons.Outlined.Hub          to HybridColor
    }

    Surface(
        onClick  = onSelected,
        shape    = RoundedCornerShape(12.dp),
        color    = if (selected) accentColor.copy(alpha = 0.10f)
                   else MaterialTheme.colorScheme.surfaceVariant,
        border   = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) accentColor.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = if (selected) accentColor else dimWhite(),
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    mode.displayName,
                    color      = if (selected) MaterialTheme.colorScheme.onSurface else dimWhite(),
                    fontSize   = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    mode.description,
                    color    = if (selected) dimWhite() else subtleWhite(),
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
        }
    }
}

@Composable
private fun PrivacyLevelOption(
    level:      PrivacyLevel,
    selected:   Boolean,
    onSelected: () -> Unit
) {
    val accentColor = when (level) {
        PrivacyLevel.MAXIMUM     -> LocalColor
        PrivacyLevel.BALANCED    -> CosmicAccent
        PrivacyLevel.PERFORMANCE -> CloudColor
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accentColor.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onSelected)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick  = onSelected,
            colors   = RadioButtonDefaults.colors(selectedColor = accentColor)
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                level.displayName,
                color      = if (selected) MaterialTheme.colorScheme.onSurface else dimWhite(),
                fontSize   = 12.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
            Text(
                level.description,
                color    = subtleWhite(),
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
private fun CloudProviderSelector(
    current:    CloudProvider,
    onSelected: (CloudProvider) -> Unit
) {
    val providers = CloudProvider.values()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        providers.forEach { provider ->
            val sel = provider == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (sel) CosmicAccent.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    )
                    .border(
                        1.dp,
                        if (sel) CosmicAccent.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelected(provider) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    provider.displayName.split(" ").first(),
                    color    = if (sel) CosmicAccent else subtleWhite(),
                    fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun CloudTokenUsageBar(used: Int, cap: Int) {
    val pct = (used.toFloat() / cap.toFloat()).coerceIn(0f, 1f)
    val color = when {
        pct > 0.9f -> Color(0xFFEF5350)
        pct > 0.7f -> WarnAmber
        else       -> CosmicAccent
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Cloud Usage", color = dimWhite(), fontSize = 11.sp)
            Text("$used / $cap tokens", color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct)
                    .fillMaxHeight()
                    .background(color)
            )
        }
    }
}

@Composable
private fun SettingsSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        shape    = RoundedCornerShape(16.dp),
        border   = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content  = { Column(modifier = Modifier.padding(14.dp), content = content) }
    )
}
