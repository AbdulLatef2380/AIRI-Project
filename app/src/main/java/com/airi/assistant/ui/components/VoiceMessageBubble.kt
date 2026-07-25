package com.airi.assistant.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import com.airi.assistant.ui.theme.AIRIAnimations
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * VoiceMessageBubble — displays a voice recording in the chat with playback controls.
 *
 * Shows a play/pause button, a progress bar, duration text, and an optional waveform
 * visualization. Works for both user-sent and assistant-returned voice messages.
 */
@Composable
fun VoiceMessageBubble(
    durationMs: Long,
    isPlaying: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    isUser: Boolean = true
) {
    val surfaceColor = if (isUser) CosmicAccent.copy(0.15f) else AiriTheme.surface.copy(0.9f)
    val borderColor = if (isUser) CosmicAccent.copy(0.3f) else AiriTheme.outline.copy(0.5f)
    val iconTint = if (isUser) CosmicAccent else AiriTheme.onSurface

    val durationSec = durationMs / 1000
    val currentSec = (progress * durationSec).toInt()
    val currentMin = currentSec / 60
    val currentSecRemainder = currentSec % 60
    val totalMin = durationSec / 60
    val totalSecRemainder = durationSec % 60

    Row(
        modifier = modifier
            .clip(AIRIShapes.md)
            .background(surfaceColor)
            .border(0.5.dp, borderColor, AIRIShapes.md)
            .clickable(onClick = onPlayPause)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Play/Pause button
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isUser) CosmicAccent else CosmicAccent.copy(0.2f))
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center
        ) {
            val playIcon: ImageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
            Icon(
                imageVector = playIcon,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = if (isUser) Color.White else CosmicAccent,
                modifier = Modifier.size(20.dp)
            )
        }

        // Progress bar
        Column(modifier = Modifier.weight(1f)) {
            // Waveform placeholder (static bars)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val barCount = 24
                for (i in 0 until barCount) {
                    val isActive = (i.toFloat() / barCount) <= progress
                    val height = (4f + (Math.sin(i * 0.5) * 0.5f + 0.5f) * 14f).dp
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(height)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (isActive) iconTint else iconTint.copy(0.2f))
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Time display
            Text(
                text = "${currentMin}:${String.format("%02d", currentSecRemainder)} / ${totalMin}:${String.format("%02d", totalSecRemainder)}",
                fontSize = 10.sp,
                color = AiriTheme.onSurface.copy(0.6f),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * VoiceRecordingIndicator — shows a live recording indicator in the input bar.
 * Displays a pulsing red dot and elapsed time.
 */
@Composable
fun VoiceRecordingIndicator(
    elapsedMs: Long,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val elapsedSec = (elapsedMs / 1000).toInt()
    val minutes = elapsedSec / 60
    val seconds = elapsedSec % 60

    val pulseAlpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            pulseAlpha.animateTo(0.3f, animationSpec = tween(AIRIAnimations.SLOW))
            pulseAlpha.animateTo(1f, animationSpec = tween(AIRIAnimations.SLOW))
        }
    }

    Row(
        modifier = modifier
            .clip(AIRIShapes.md)
            .background(Color(0xFFFF4444).copy(0.12f))
            .border(0.5.dp, Color(0xFFFF4444).copy(0.3f), AIRIShapes.md)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF4444).copy(pulseAlpha.value))
        )
        Text(
            text = "Recording... ${minutes}:${String.format("%02d", seconds)}",
            fontSize = 12.sp,
            color = Color(0xFFFF4444),
            fontWeight = FontWeight.Medium
        )
    }
}
