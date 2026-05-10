package com.airi.assistant.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
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
// NeuralBottomSheet — glass dark sheet with animated handle
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
                .background(Color.Black.copy(alpha = 0.68f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Surface2)
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(listOf(BorderMid, Color.Transparent)),
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    )
                    .pointerInput(Unit) { detectTapGestures { /* consume */ } }
            ) {
                // Drag handle
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(BorderMid)
                    )
                }
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                    NeuralDivider()
                }
                content()
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralSearchBar — glass search field
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    // Alias for compatibility
    query: String = value,
    onQueryChange: (String) -> Unit = onValueChange
) {
    BasicTextField(
        value = if (value.isEmpty()) query else value,
        onValueChange = { 
            onValueChange(it)
            onQueryChange(it)
        },
        textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
        cursorBrush = SolidColor(AccentBlue),
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface3)
            .border(1.dp, BorderLow, RoundedCornerShape(12.dp)),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty() && query.isEmpty()) {
                        Text(text = placeholder, color = TextTertiary, fontSize = 15.sp)
                    }
                    innerTextField()
                }
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralSectionCard — grouped items container
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface2)
            .border(1.dp, BorderLow, RoundedCornerShape(16.dp)),
        content = content
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralRowItem — single row with optional icon and arrow
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralRowItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = AccentBlue,
    showArrow: Boolean = true,
    onClick: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null,
    // Compatibility aliases
    iconTint: Color = iconColor,
    iconBgColor: Color? = null,
    showChevron: Boolean = showArrow
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            val finalIconColor = iconTint
            val finalBgColor = iconBgColor ?: finalIconColor.copy(alpha = 0.12f)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(finalBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = finalIconColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(text = subtitle, color = TextTertiary, fontSize = 13.sp)
            }
        }
        if (trailingContent != null) {
            trailingContent()
        } else if (showArrow && showChevron) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = BorderMid,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralToggle — custom animated switch
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) AccentBlue else BorderLow,
        animationSpec = tween(250),
        label = "toggle_track"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 18.dp else 2.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "toggle_thumb"
    )

    Box(
        modifier = modifier
            .width(42.dp)
            .height(24.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, Color.Black.copy(alpha = 0.05f), CircleShape)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralDivider — thin line
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralDivider(modifier: Modifier = Modifier) {
    Divider(
        modifier = modifier.fillMaxWidth(),
        thickness = 0.5.dp,
        color = BorderLow
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralBadge — small status chip
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralBadge(
    text: String,
    color: Color = AccentBlue,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralAccentButton — primary action button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralAccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentBlue,
            contentColor = Color.White,
            disabledContainerColor = Surface3,
            disabledContentColor = TextTertiary
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralHeader — screen top bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AiriScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary
            )
        }
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (trailingContent != null) {
            trailingContent()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralStatusDot / NeuralGlowDot — indicator for active state
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    animate: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dot_pulse")
    val scale by if (animate) infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dot_scale"
    ) else remember { mutableStateOf(1f) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .then(
                if (animate) Modifier.drawBehind {
                    drawIntoCanvas { canvas ->
                        val paint = Paint().apply {
                            asFrameworkPaint().setShadowLayer(6f, 0f, 0f, color.copy(alpha = 0.6f).toArgb())
                        }
                        canvas.drawCircle(
                            androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
                            size.minDimension / 2f * scale, paint
                        )
                    }
                } else Modifier
            )
    )
}

@Composable
fun NeuralGlowDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    animate: Boolean = true
) {
    NeuralStatusDot(color = color, modifier = modifier, size = size, animate = animate)
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralSectionLabel — section header text
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = TextTertiary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
