package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.ui.theme.CosmicAccent
// ExecOriginBadge — Visible execution origin indicator for chat messages
//
// AIRI never hides execution origin. Every assistant response that was
// produced by an identifiable backend carries this badge so the user always
// knows whether the answer came from the local model, a cloud provider, or
// a hybrid pipeline.
//
// Usage:
//   ExecOriginBadge(origin = message.execOrigin)
//
// The badge is invisible (zero-height, zero-width) when origin == NONE,
// so it adds no whitespace to user messages or untagged responses.
private val LocalBadgeColor  = Color(0xFF43A047)  // green
private val CloudBadgeColor  = Color(0xFF29B6F6)  // light blue
private val HybridBadgeColor = Color(0xFFAB47BC)  // purple

/**
 * Small origin badge composable.
 *
 * Renders nothing when [origin] is [ExecOrigin.NONE].
 * Otherwise renders a small pill chip with an icon and a text label.
 *
 * @param origin      Execution origin to display.
 * @param modifier    Optional layout modifier.
 * @param showDot     When true, shows a coloured dot instead of the full chip.
 *                    Useful in compact list layouts.
 */
@Composable
fun ExecOriginBadge(
    origin:   ExecOrigin,
    modifier: Modifier = Modifier,
    showDot:  Boolean  = false
) {
    if (origin == ExecOrigin.NONE) return

    val spec = when (origin) {
        ExecOrigin.LOCAL  -> BadgeSpec(
            Icons.Outlined.PhoneAndroid,
            "LOCAL",
            LocalBadgeColor.copy(alpha = 0.15f),
            LocalBadgeColor
        )
        ExecOrigin.CLOUD  -> BadgeSpec(
            Icons.Outlined.Cloud,
            "CLOUD",
            CloudBadgeColor.copy(alpha = 0.15f),
            CloudBadgeColor
        )
        ExecOrigin.HYBRID -> BadgeSpec(
            Icons.Outlined.Hub,
            "HYBRID",
            HybridBadgeColor.copy(alpha = 0.15f),
            HybridBadgeColor
        )
        ExecOrigin.NONE   -> return
    }
    val icon = spec.icon
    val label = spec.label
    val bgColor = spec.bgColor
    val textColor = spec.textColor

    if (showDot) {
        Box(
            modifier = modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(textColor)
        )
        return
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 6.dp))
            .background(bgColor)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = textColor,
            modifier           = Modifier.size(9.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text       = label,
            color      = textColor,
            fontSize   = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * Inline badge variant with a larger size — suitable for placing below
 * a message bubble as a standalone attribution line.
 */
@Composable
fun ExecOriginAttributionLine(
    origin:   ExecOrigin,
    modifier: Modifier = Modifier
) {
    if (origin == ExecOrigin.NONE) return

    val (color, label) = when (origin) {
        ExecOrigin.LOCAL  -> LocalBadgeColor  to "Answered locally"
        ExecOrigin.CLOUD  -> CloudBadgeColor  to "Answered by cloud"
        ExecOrigin.HYBRID -> HybridBadgeColor to "Hybrid response"
        ExecOrigin.NONE   -> return
    }

    Text(
        text     = label,
        color    = color.copy(alpha = 0.65f),
        fontSize = 10.sp,
        modifier = modifier
    )
}
// Internal
private data class BadgeSpec(
    val icon:      androidx.compose.ui.graphics.vector.ImageVector,
    val label:     String,
    val bgColor:   Color,
    val textColor: Color
)
