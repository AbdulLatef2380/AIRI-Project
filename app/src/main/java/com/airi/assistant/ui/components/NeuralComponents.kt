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
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralSectionCard — surface-1 rounded card with border glow
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
            .background(Surface1)
            .border(1.dp, BorderLight, RoundedCornerShape(16.dp))
    ) { content() }
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
            .background(BorderDark)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralToggle — animated rounded switch
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) PrimaryAccent else Surface3,
        animationSpec = tween(200),
        label = "toggle_track"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "toggle_thumb"
    )
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(trackColor)
            .border(1.dp, if (checked) PrimaryAccent.copy(alpha = 0.4f) else BorderLight, CircleShape)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset, top = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
                .then(
                    if (checked) Modifier.drawBehind {
                        drawIntoCanvas { canvas ->
                            val paint = Paint().apply {
                                asFrameworkPaint().setShadowLayer(6f, 0f, 0f, PrimaryAccent.copy(alpha = 0.55f).toArgb())
                            }
                            canvas.drawCircle(
                                androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
                                size.minDimension / 2f,
                                paint
                            )
                        }
                    } else Modifier
                )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralScreenHeader — top bar with back arrow + optional action
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
    subtitle: String? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Surface1, Surface0)
                )
            )
            .border(
                width = 0.5.dp,
                brush = Brush.horizontalGradient(listOf(Color.Transparent, BorderLight, Color.Transparent)),
                shape = RoundedCornerShape(0.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = PrimaryAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (actionIcon != null && onAction != null) {
                IconButton(onClick = onAction) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                        tint = PrimaryAccent
                    )
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralSearchBar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "بحث...",
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
        cursorBrush = SolidColor(PrimaryAccent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .border(1.dp, BorderLight, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(placeholder, color = TextTertiary, fontSize = 14.sp)
                    }
                    inner()
                }
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralAccentButton — primary CTA button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralAccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PrimaryAccent,
            contentColor = Color.White,
            disabledContainerColor = PrimaryAccent.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.5f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .then(
                if (enabled) Modifier.drawBehind {
                    drawIntoCanvas { canvas ->
                        val paint = Paint().apply {
                            asFrameworkPaint().setShadowLayer(16f, 0f, 4f, PrimaryAccent.copy(alpha = 0.45f).toArgb())
                        }
                        canvas.drawRoundRect(
                            0f, 0f, size.width, size.height,
                            14.dp.toPx(), 14.dp.toPx(), paint
                        )
                    }
                } else Modifier
            )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralBadge — pill chip for tags/status
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralBadge(
    text: String,
    color: Color = PrimaryAccent,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.15f))
            .border(0.5.dp, color.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// StreamingCursor — blinking ▍ for streaming responses
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun StreamingCursor(modifier: Modifier = Modifier) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500L)
            on = !on
        }
    }
    androidx.compose.animation.AnimatedContent(
        targetState = on,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(tween(80)) togetherWith
                    androidx.compose.animation.fadeOut(tween(80)))
        },
        label = "cursor_blink",
        modifier = modifier
    ) { visible ->
        Text(
            text = if (visible) "▍" else " ",
            color = PrimaryAccent.copy(alpha = 0.85f),
            fontSize = 15.sp,
            lineHeight = 23.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VioletWaveform — animated audio waveform bars
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun VioletWaveform(
    active: Boolean,
    color: Color = PrimaryAccent,
    barCount: Int = 5,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "waveform")
    Row(
        modifier = modifier.height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (i in 0 until barCount) {
            val maxH = when (i % 3) { 0 -> 14f; 1 -> 20f; else -> 10f }
            val barH by infinite.animateFloat(
                initialValue = 3f,
                targetValue  = if (active) maxH else 4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(280 + i * 70, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 80)
                ),
                label = "bar_$i"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barH.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = if (active) 0.9f else 0.4f))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralRowItem — settings/list row with icon, title, subtitle, chevron
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = PrimaryAccent,
    iconBgColor: Color = PrimaryAccent.copy(alpha = 0.14f),
    trailingContent: @Composable (() -> Unit)? = null,
    showChevron: Boolean = true,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBgColor)
                .border(0.5.dp, iconTint.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        if (trailingContent != null) {
            trailingContent()

// ─────────────────────────────────────────────────────────────────────────────
// AiriScreenHeader — reusable top bar used across all screens
// Exported from this package so screens can import from either package.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AiriScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Surface1, Surface0)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                modifier = Modifier.weight(1f)
            )
            actions()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, BorderLight, Color.Transparent)))
                .align(Alignment.BottomCenter)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NeuralGlowDot — pulsing status dot
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NeuralGlowDot(
    color: Color = SemanticSuccess,
    size: Dp = 8.dp,
    animate: Boolean = true
) {
    val infinite = rememberInfiniteTransition(label = "dot_pulse")
    val scale by if (animate) infinite.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dot_scale"
    ) else remember { mutableStateOf(1f) }

    Box(
        modifier = Modifier
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
