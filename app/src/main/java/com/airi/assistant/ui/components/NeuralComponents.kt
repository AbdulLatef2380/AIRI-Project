package com.airi.assistant.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.airi.assistant.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// NeuralBottomSheet
// Slides up from the bottom. Tapping the scrim or the handle dismisses it.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NeuralBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.60f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(Surface2)
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(listOf(BorderMid, Color.Transparent)),
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                    )
                    .pointerInput(Unit) { detectTapGestures { /* consume — don't dismiss */ } }
            ) {
                // Drag handle
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(BorderMid)
                    )
                }

                if (title != null) {
                    Text(
                        text  = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                    Box(
                        Modifier.fillMaxWidth().height(1.dp).background(BorderLight)
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                }

                content()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralSectionCard
// Dark surface card matching the React prototype's section container.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NeuralSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .border(1.dp, BorderLight, RoundedCornerShape(14.dp))
    ) {
        content()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralDivider
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NeuralDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(start = 16.dp)
            .background(BorderLight)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralToggle
// Custom Switch with violet accent matching the React prototype design.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NeuralToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val trackColor by animateColorAsState(
        targetValue  = if (checked) PrimaryAccent else Surface3,
        animationSpec = tween(180),
        label        = "trackColor"
    )
    val thumbX by animateDpAsState(
        targetValue  = if (checked) 20.dp else 2.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label        = "thumbX"
    )

    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(trackColor)
            .clickable(
                enabled             = enabled,
                interactionSource   = remember { MutableInteractionSource() },
                indication          = null,
                onClick             = { onCheckedChange(!checked) }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbX)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralScreenHeader
// Top bar with Neural Violet styling, back button, title, optional trailing action.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NeuralScreenHeader(
    title:            String,
    onBack:           (() -> Unit)? = null,
    trailingIcon:     ImageVector?  = null,
    trailingAction:   (() -> Unit)? = null,
    trailingContent:  (@Composable () -> Unit)? = null,
    modifier:         Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }

        Text(
            text       = title,
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color      = TextPrimary,
            modifier   = Modifier.weight(1f)
        )

        if (trailingContent != null) {
            trailingContent()
        } else if (trailingIcon != null && trailingAction != null) {
            IconButton(onClick = trailingAction) {
                Icon(trailingIcon, contentDescription = null, tint = PrimaryAccent)
            }
        }
    }

    Box(Modifier.fillMaxWidth().height(1.dp).background(BorderLight))
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralSearchBar
// Search input matching the React prototype style.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NeuralSearchBar(
    query:         String,
    onQueryChange: (String) -> Unit,
    placeholder:   String    = "بحث...",
    modifier:      Modifier  = Modifier,
    onSearch:      (() -> Unit)? = null
) {
    BasicTextField(
        value          = query,
        onValueChange  = onQueryChange,
        modifier       = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .border(1.dp, if (query.isNotEmpty()) PrimaryAccent.copy(alpha = 0.6f) else BorderLight, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        textStyle      = TextStyle(color = TextPrimary, fontSize = 15.sp),
        cursorBrush    = SolidColor(PrimaryAccent),
        singleLine     = true,
        keyboardOptions   = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions   = KeyboardActions(onSearch = { onSearch?.invoke() }),
        decorationBox  = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(placeholder, color = TextTertiary, fontSize = 15.sp)
                    }
                    inner()
                }
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralAccentButton
// Primary violet CTA button matching the React prototype.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NeuralAccentButton(
    text:     String,
    onClick:  () -> Unit,
    modifier: Modifier  = Modifier,
    enabled:  Boolean   = true,
    icon:     ImageVector? = null
) {
    val alpha = if (enabled) 1f else 0.5f
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(PrimaryAccent.copy(alpha = alpha), AccentDark.copy(alpha = alpha))
                )
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text       = text,
            color      = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize   = 14.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralBadge
// Small label pill used for skill types, status, etc.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NeuralBadge(
    text:      String,
    color:     Color    = PrimaryAccent,
    modifier:  Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text      = text,
            color     = color,
            fontSize  = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// StreamingCursor
// Pulsing violet dot shown at the end of a streaming AI response.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StreamingCursor(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "cursor")
    val alpha by inf.animateFloat(
        initialValue = 1f,
        targetValue  = 0.15f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(PrimaryAccent.copy(alpha = alpha))
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// VioletWaveform
// Animated vertical bars for the voice listening state.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun VioletWaveform(
    modifier:  Modifier = Modifier,
    barCount:  Int      = 5,
    color:     Color    = PrimaryAccent,
    isActive:  Boolean  = true
) {
    val inf = rememberInfiniteTransition(label = "waveform")

    val heights = (0 until barCount).map { i ->
        inf.animateFloat(
            initialValue  = if (isActive) 0.25f else 0.25f,
            targetValue   = if (isActive) 1.00f else 0.25f,
            animationSpec = if (isActive) infiniteRepeatable(
                animation  = tween(
                    durationMillis = 400 + i * 80,
                    easing         = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(i * 60)
            ) else snap(),
            label = "bar$i"
        )
    }

    Row(
        modifier            = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment   = Alignment.CenterVertically
    ) {
        heights.forEach { heightFraction ->
            val h by heightFraction
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((4 + h * 20).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.6f + h * 0.4f))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralRowItem
// Reusable settings / list row with icon, title, subtitle, and trailing slot.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NeuralRowItem(
    icon:      ImageVector,
    title:     String,
    subtitle:  String?           = null,
    iconTint:  Color             = PrimaryAccent,
    trailing:  (@Composable () -> Unit)? = null,
    onClick:   (() -> Unit)?     = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null)
                    Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                else
                    Modifier
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            if (subtitle != null) {
                Text(subtitle, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }

        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                imageVector        = Icons.Default.ChevronRight,
                contentDescription = null,
                tint               = TextTertiary,
                modifier           = Modifier.size(18.dp)
            )
        }
    }
}
