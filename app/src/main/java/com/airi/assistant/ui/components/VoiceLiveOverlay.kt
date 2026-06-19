package com.airi.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.screens.VoiceSessionState
import com.airi.assistant.ui.theme.*
import kotlin.math.*
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

/**
 * VoiceLiveOverlay — cinematic full-screen voice conversation UI.
 *
 * Shown when the user enters live voice / duplex conversation mode.
 * Displays:
 *  - Animated waveform (bars react to voice state)
 *  - Breathing avatar orb (pulsing in sync with speaking state)
 *  - State label (Listening / Speaking / Processing / Interrupted)
 *  - Realtime caption display
 *  - Stop button
 *
 * Wired to [VoiceSessionState] — all animations are driven purely from state
 * enum changes. No side effects, recomposition-safe.
 */
@Composable
fun VoiceLiveOverlay(
    voiceState: VoiceSessionState,
    caption: String = "",
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = voiceState != VoiceSessionState.IDLE,
        enter   = fadeIn(tween(300)),
        exit    = fadeOut(tween(250)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0A0D1A).copy(alpha = 0.97f),
                            Color(0xFF080B14).copy(alpha = 0.99f)
                        )
                    )
                )
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // ── Breathing orb ─────────────────────────────────────────
                VoiceOrb(voiceState = voiceState)

                // ── State label ───────────────────────────────────────────
                VoiceStateLabel(voiceState = voiceState)

                // ── Waveform ──────────────────────────────────────────────
                VoiceWaveform(voiceState = voiceState, modifier = Modifier.fillMaxWidth().height(56.dp))

                // ── Caption ───────────────────────────────────────────────
                if (caption.isNotBlank()) {
                    Text(
                        text       = caption,
                        fontSize   = 15.sp,
                        color      = Color.White.copy(alpha = 0.75f),
                        fontWeight = FontWeight.Normal,
                        modifier   = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    )
                }

                // ── Stop button ───────────────────────────────────────────
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(SemanticError.copy(alpha = 0.18f))
                        .border(1.dp, SemanticError.copy(alpha = 0.35f), CircleShape)
                        .clickable(onClick = onStop)
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.cd_stop_voice),
                        tint               = SemanticError,
                        modifier           = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

// ── Breathing orb ─────────────────────────────────────────────────────────────
@Composable
private fun VoiceOrb(voiceState: VoiceSessionState) {
    val orbColor = when (voiceState) {
        VoiceSessionState.LISTENING   -> AIRIColors.voiceListening
        VoiceSessionState.SPEAKING    -> AIRIColors.voiceSpeaking
        VoiceSessionState.PROCESSING  -> AIRIColors.voiceProcessing
        VoiceSessionState.IDLE        -> AIRIColors.voiceIdle
    }

    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val scale by infiniteTransition.animateFloat(
        initialValue  = 1.0f,
        targetValue   = when (voiceState) {
            VoiceSessionState.SPEAKING   -> 1.18f
            VoiceSessionState.LISTENING  -> 1.10f
            VoiceSessionState.PROCESSING -> 1.05f
            VoiceSessionState.IDLE       -> 1.0f
        },
        animationSpec = infiniteRepeatable(
            animation  = tween(
                durationMillis = when (voiceState) {
                    VoiceSessionState.SPEAKING   -> 700
                    VoiceSessionState.LISTENING  -> 900
                    VoiceSessionState.PROCESSING -> 600
                    VoiceSessionState.IDLE       -> 2000
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbScale"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
        // Outer glow
        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            orbColor.copy(alpha = 0.25f),
                            orbColor.copy(alpha = 0.0f)
                        )
                    )
                )
        )
        // Inner orb
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(orbColor.copy(alpha = 0.55f), orbColor.copy(alpha = 0.20f))
                    )
                )
                .border(1.5.dp, orbColor.copy(alpha = 0.45f), CircleShape)
        ) {
            Icon(
                imageVector        = if (voiceState == VoiceSessionState.SPEAKING) Icons.Outlined.VolumeUp else Icons.Outlined.Mic,
                contentDescription = null,
                tint               = Color.White.copy(alpha = 0.9f),
                modifier           = Modifier.size(26.dp)
            )
        }
    }
}

// ── State label ───────────────────────────────────────────────────────────────
@Composable
private fun VoiceStateLabel(voiceState: VoiceSessionState) {
    val (label, color) = when (voiceState) {
        VoiceSessionState.LISTENING   -> "جارٍ الاستماع…" to AIRIColors.voiceListening
        VoiceSessionState.SPEAKING    -> "AIRI يتحدث" to AIRIColors.voiceSpeaking
        VoiceSessionState.PROCESSING  -> "جارٍ المعالجة…" to AIRIColors.voiceProcessing
        VoiceSessionState.IDLE        -> "" to Color.Transparent
    }
    if (label.isNotBlank()) {
        Text(
            text       = label,
            fontSize   = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color      = color
        )
    }
}

// ── Animated waveform ─────────────────────────────────────────────────────────
@Composable
private fun VoiceWaveform(voiceState: VoiceSessionState, modifier: Modifier = Modifier) {
    val barCount = 28
    val isActive = voiceState != VoiceSessionState.IDLE

    val infiniteTransition = rememberInfiniteTransition(label = "wave")

    // Create per-bar animated heights
    val barHeights = (0 until barCount).map { i ->
        val phase = (i.toFloat() / barCount) * 2 * PI
        val duration = if (isActive) (350 + i * 18) else 2000
        infiniteTransition.animateFloat(
            initialValue = if (isActive) 0.12f else 0.08f,
            targetValue  = if (isActive) {
                when (voiceState) {
                    VoiceSessionState.SPEAKING   -> 0.35f + 0.45f * abs(sin(phase).toFloat())
                    VoiceSessionState.LISTENING  -> 0.20f + 0.30f * abs(sin(phase).toFloat())
                    VoiceSessionState.PROCESSING -> 0.18f + 0.20f * abs(sin(phase).toFloat())
                    else -> 0.10f
                }
            } else 0.10f,
            animationSpec = infiniteRepeatable(
                animation  = tween(duration, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar$i"
        )
    }

    val barColor = when (voiceState) {
        VoiceSessionState.LISTENING   -> AIRIColors.voiceListening
        VoiceSessionState.SPEAKING    -> CosmicAccent
        VoiceSessionState.PROCESSING  -> SemanticWarn
        VoiceSessionState.IDLE        -> Color.White.copy(alpha = 0.3f)
    }

    Canvas(modifier = modifier) {
        val w      = size.width
        val h      = size.height
        val barW   = w / (barCount * 1.8f)
        val gap    = w / barCount

        barHeights.forEachIndexed { i, heightAnim ->
            val barH  = h * heightAnim.value
            val x     = i * gap + gap / 2f
            val top   = (h - barH) / 2f

            drawLine(
                color       = barColor.copy(alpha = 0.75f + 0.25f * heightAnim.value),
                start       = Offset(x, top),
                end         = Offset(x, top + barH),
                strokeWidth = barW,
                cap         = StrokeCap.Round
            )
        }
    }
}
