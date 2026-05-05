package com.airi.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ai.ContextPressureManager
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticWarn

/**
 * ContextPressureBar — live context window saturation indicator.
 *
 * Sits above the [ChatInputBar] and [AgentExecutionPanel] in the bottom bar
 * of [ChatScreen]. Invisible at NOMINAL pressure; slides in at WARNING (≥70%)
 * and becomes progressively more urgent at CRITICAL (≥90%) and OVERFLOW (≥100%).
 *
 * ── VISUAL DESIGN ─────────────────────────────────────────────────────────
 *
 *   ┌──────────────────────────────────────────────────────┐
 *   │ ⚠  Context 74% full                    [Summarize]  │  ← WARNING (amber)
 *   │ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▒▒▒▒▒▒▒░░░░░░░░░░░░░░░░░░░░  │
 *   └──────────────────────────────────────────────────────┘
 *
 *   ┌──────────────────────────────────────────────────────┐
 *   │ ⚠  Context 93% full — summarize now    [New Chat]   │  ← CRITICAL (red)
 *   │ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▒▒▒▒░░░░░░░  │
 *   └──────────────────────────────────────────────────────┘
 *
 *   ┌──────────────────────────────────────────────────────┐
 *   │ ● Context full — responses may be cut off [New Chat] │  ← OVERFLOW (pulsing red)
 *   │ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  │
 *   └──────────────────────────────────────────────────────┘
 *
 * ── CALLBACKS ─────────────────────────────────────────────────────────────
 *
 *   [onSummarize]  — WARNING level: send a summarization prompt so the user
 *                    gets a text snapshot of the conversation. Does NOT reset
 *                    the context window on its own.
 *   [onNewChat]    — CRITICAL/OVERFLOW: start a fresh session, resetting the
 *                    context pressure counter to zero.
 *
 * ── ANIMATIONS ────────────────────────────────────────────────────────────
 *
 *   - Bar slides in with expandVertically + fadeIn (300ms)
 *   - Progress animates with spring/tween (600ms, FastOutSlowIn)
 *   - OVERFLOW triggers a 1.2s alpha pulse on the label dot
 *
 * @param report     Current [ContextPressureManager.PressureReport] from the ViewModel.
 * @param onSummarize Called when user taps "Summarize" at WARNING level.
 * @param onNewChat   Called when user taps "New Chat" at CRITICAL/OVERFLOW level.
 */
@Composable
fun ContextPressureBar(
    report: ContextPressureManager.PressureReport,
    onSummarize: () -> Unit,
    onNewChat:   () -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = report.level != ContextPressureManager.PressureLevel.NOMINAL

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            expandFrom     = Alignment.Bottom
        ) + fadeIn(animationSpec = tween(250)),
        exit  = shrinkVertically(
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Bottom
        ) + fadeOut(animationSpec = tween(200))
    ) {
        ContextPressureBarContent(
            report      = report,
            onSummarize = onSummarize,
            onNewChat   = onNewChat
        )
    }
}

// ── Internal implementation ────────────────────────────────────────────────

@Composable
private fun ContextPressureBarContent(
    report:      ContextPressureManager.PressureReport,
    onSummarize: () -> Unit,
    onNewChat:   () -> Unit,
) {
    // Resolve level-sensitive colors
    val barColor: Color = when (report.level) {
        ContextPressureManager.PressureLevel.NOMINAL  -> CosmicAccent
        ContextPressureManager.PressureLevel.WARNING  -> SemanticWarn
        ContextPressureManager.PressureLevel.CRITICAL -> SemanticError
        ContextPressureManager.PressureLevel.OVERFLOW -> SemanticError
    }

    val trackColor: Color = barColor.copy(alpha = 0.18f)
    val surfaceColor: Color = when (report.level) {
        ContextPressureManager.PressureLevel.WARNING  -> SemanticWarn.copy(alpha = 0.08f)
        ContextPressureManager.PressureLevel.CRITICAL,
        ContextPressureManager.PressureLevel.OVERFLOW -> SemanticError.copy(alpha = 0.10f)
        else                                          -> Color.Transparent
    }

    // Animated progress fraction — smooth easing so every token update is fluid
    val progressFraction by animateFloatAsState(
        targetValue    = (report.usedPercent / 100f).coerceIn(0f, 1f),
        animationSpec  = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label          = "contextPressureProgress"
    )

    // Overflow pulse: alpha oscillates 0.5 → 1.0 → 0.5 on a 1.2s cycle
    val infiniteTransition = rememberInfiniteTransition(label = "overflowPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue   = 1f,
        targetValue    = 0.45f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val labelAlpha = if (report.level == ContextPressureManager.PressureLevel.OVERFLOW) pulseAlpha else 1f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // ── Label row ─────────────────────────────────────────────────────
        Row(
            modifier            = Modifier.fillMaxWidth(),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: icon + status label
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (report.level) {
                    ContextPressureManager.PressureLevel.OVERFLOW -> {
                        // Pulsing dot for overflow
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(barColor.copy(alpha = labelAlpha))
                        )
                    }
                    else -> {
                        Icon(
                            imageVector        = Icons.Filled.Warning,
                            contentDescription = null,
                            modifier           = Modifier.size(13.dp),
                            tint               = barColor
                        )
                    }
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text       = contextLabel(report),
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color      = barColor,
                    maxLines   = 1
                )
            }

            // Right: action button
            when (report.level) {
                ContextPressureManager.PressureLevel.WARNING -> {
                    TextButton(
                        onClick      = onSummarize,
                        modifier     = Modifier.height(26.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp
                        )
                    ) {
                        Icon(
                            imageVector        = Icons.Outlined.Article,
                            contentDescription = null,
                            modifier           = Modifier.size(12.dp),
                            tint               = barColor
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text       = "Summarize",
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = barColor
                        )
                    }
                }
                ContextPressureManager.PressureLevel.CRITICAL,
                ContextPressureManager.PressureLevel.OVERFLOW -> {
                    TextButton(
                        onClick      = onNewChat,
                        modifier     = Modifier.height(26.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp
                        )
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.Compress,
                            contentDescription = null,
                            modifier           = Modifier.size(12.dp),
                            tint               = barColor
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text       = "New Chat",
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = barColor
                        )
                    }
                }
                else -> Unit
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── Progress bar track ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressFraction)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

private fun contextLabel(report: ContextPressureManager.PressureReport): String =
    when (report.level) {
        ContextPressureManager.PressureLevel.WARNING  ->
            "Context ${report.usedPercent}% full · ${report.remaining} tokens remaining"
        ContextPressureManager.PressureLevel.CRITICAL ->
            "Context ${report.usedPercent}% full — summarize before next reply"
        ContextPressureManager.PressureLevel.OVERFLOW ->
            "Context full — responses may be truncated"
        ContextPressureManager.PressureLevel.NOMINAL  -> ""
    }
