package com.airi.assistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.BorderLight
import com.airi.assistant.ui.theme.PrimaryAccent
import com.airi.assistant.ui.theme.Surface1
import com.airi.assistant.ui.theme.Surface2
import com.airi.assistant.ui.theme.TextPrimary
import com.airi.assistant.ui.theme.TextSecondary
import com.airi.assistant.ui.theme.TextTertiary

// ─────────────────────────────────────────────────────────────────────────────
// AiriComponents — production-grade reusable UI atoms for AIRI
//
// All components consume design tokens from Color.kt. No hardcoded hex values.
// ─────────────────────────────────────────────────────────────────────────────

// ── Section label ─────────────────────────────────────────────────────────────

/**
 * Uppercase section divider label used throughout settings and list screens.
 * Mirrors iOS-style section headers with a subtle left-accent bar.
 */
@Composable
fun AiriSectionLabel(
    text:     String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier          = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(CircleShape)
                .background(PrimaryAccent.copy(alpha = 0.55f))
        )
        Text(
            text          = text.uppercase(),
            fontSize      = 11.sp,
            fontWeight    = FontWeight.Bold,
            color         = TextTertiary,
            letterSpacing = 0.9.sp
        )
    }
}

// ── Standard card ─────────────────────────────────────────────────────────────

/**
 * Standard Surface1 card with rounded corners and a subtle border.
 * Use for any grouped content that needs visual separation from the page.
 */
@Composable
fun AiriCard(
    modifier:     Modifier = Modifier,
    cornerRadius: Dp       = 14.dp,
    content:      @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(cornerRadius),
        color    = Surface1,
        border   = BorderStroke(1.dp, BorderLight)
    ) {
        Column(content = content)
    }
}

// ── Status badge ──────────────────────────────────────────────────────────────

/**
 * Small coloured pill that communicates a discrete state.
 * Renders a filled dot indicator or an icon-labeled chip.
 *
 * @param label     Text shown in the badge.
 * @param color     Accent colour for background tint, border, and text.
 * @param showDot   When true, renders a bare 6dp coloured dot instead.
 */
@Composable
fun AiriStatusBadge(
    label:    String,
    color:    Color,
    modifier: Modifier = Modifier,
    showDot:  Boolean  = false
) {
    if (showDot) {
        Box(
            modifier = modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        return
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.11f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text          = label,
            fontSize      = 10.sp,
            fontWeight    = FontWeight.Bold,
            color         = color,
            letterSpacing = 0.6.sp
        )
    }
}

// ── Stat card ─────────────────────────────────────────────────────────────────

/**
 * Compact metric display: large number on top, label below.
 * Used in dashboard summary rows and overview headers.
 */
@Composable
fun AiriStatCard(
    value:    String,
    label:    String,
    color:    Color    = PrimaryAccent,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.09f))
            .border(1.dp, color.copy(alpha = 0.20f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text       = value,
            fontSize   = 22.sp,
            fontWeight = FontWeight.Bold,
            color      = color
        )
        Text(
            text       = label,
            fontSize   = 10.sp,
            color      = TextTertiary,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp
        )
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

/**
 * Full-area empty state with an icon, heading, and body text.
 * Optionally renders a CTA button via [actionLabel] + [onAction].
 */
@Composable
fun AiriEmptyState(
    icon:        ImageVector,
    heading:     String,
    body:        String,
    modifier:    Modifier = Modifier,
    actionLabel: String?  = null,
    onAction:    (() -> Unit)? = null
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier            = Modifier.padding(horizontal = 36.dp)
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = TextTertiary.copy(alpha = 0.35f),
                modifier           = Modifier.size(60.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text       = heading,
                color      = TextSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 17.sp,
                textAlign  = TextAlign.Center
            )
            Text(
                text       = body,
                color      = TextTertiary,
                fontSize   = 13.sp,
                textAlign  = TextAlign.Center,
                lineHeight = 19.sp
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onAction,
                    colors  = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                    shape   = RoundedCornerShape(12.dp)
                ) {
                    Text(actionLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ── List row ──────────────────────────────────────────────────────────────────

/**
 * Standard settings / list row with a leading icon box, title, optional
 * subtitle, and a trailing chevron. Surfaces the click ripple via [onClick].
 *
 * @param icon       Leading icon displayed in a tinted rounded box.
 * @param iconTint   Icon tint colour (defaults to PrimaryAccent).
 * @param title      Primary label.
 * @param subtitle   Optional secondary label shown below title.
 * @param trailing   Optional trailing composable that replaces the chevron.
 * @param onClick    Called on row tap.
 */
@Composable
fun AiriListRow(
    icon:      ImageVector,
    title:     String,
    modifier:  Modifier  = Modifier,
    iconTint:  Color     = PrimaryAccent,
    subtitle:  String?   = null,
    trailing:  @Composable (() -> Unit)? = null,
    onClick:   () -> Unit
) {
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        // Leading icon box
        Box(
            modifier         = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = iconTint,
                modifier           = Modifier.size(19.dp)
            )
        }

        // Labels
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = title,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Medium,
                color      = TextPrimary
            )
            if (subtitle != null) {
                Text(
                    text      = subtitle,
                    fontSize  = 12.sp,
                    color     = TextTertiary,
                    maxLines  = 1,
                    lineHeight = 16.sp
                )
            }
        }

        // Trailing
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector        = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint               = TextTertiary.copy(alpha = 0.50f),
                modifier           = Modifier.size(18.dp)
            )
        }
    }
}

// ── Divider ───────────────────────────────────────────────────────────────────

/**
 * Lightweight inset divider used between [AiriListRow] items within a card.
 * Left-inset of 65dp aligns the line with the label text, not the icon box.
 */
@Composable
fun AiriRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier  = modifier.padding(start = 65.dp),
        thickness = 0.5.dp,
        color     = BorderLight
    )
}

// ── Primary button ────────────────────────────────────────────────────────────

/**
 * Standard full-width primary CTA button with PrimaryAccent fill.
 */
@Composable
fun AiriPrimaryButton(
    label:    String,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier,
    enabled:  Boolean  = true
) {
    Button(
        onClick  = onClick,
        enabled  = enabled,
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor         = PrimaryAccent,
            disabledContainerColor = PrimaryAccent.copy(alpha = 0.35f)
        )
    ) {
        Text(
            text       = label,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 15.sp
        )
    }
}

// ── Chip row ──────────────────────────────────────────────────────────────────

/**
 * Compact horizontal category chip for filter strips.
 * Selected chip uses PrimaryAccent tint; idle chip uses Surface2.
 */
@Composable
fun AiriFilterChip(
    label:      String,
    isSelected: Boolean,
    onClick:    () -> Unit,
    modifier:   Modifier = Modifier
) {
    Surface(
        onClick      = onClick,
        modifier     = modifier.height(32.dp),
        shape        = CircleShape,
        color        = if (isSelected) PrimaryAccent.copy(alpha = 0.15f) else Surface2,
        contentColor = if (isSelected) PrimaryAccent else TextSecondary,
        border       = BorderStroke(
            1.dp,
            if (isSelected) PrimaryAccent.copy(alpha = 0.45f) else BorderLight
        )
    ) {
        Box(
            modifier         = Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = label,
                fontSize   = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}
