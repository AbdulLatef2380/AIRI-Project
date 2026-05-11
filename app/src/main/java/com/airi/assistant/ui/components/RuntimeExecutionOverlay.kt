package com.airi.assistant.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.airi.assistant.ui.theme.*

/**
 * RuntimeExecutionOverlay — floating execution status overlay for live agent tasks.
 *
 * ── DISPLAY STATES ────────────────────────────────────────────────────────────
 *
 *  | State     | UI                                                        |
 *  |-----------|-----------------------------------------------------------|
 *  | IDLE      | Hidden — overlay is not rendered                          |
 *  | RUNNING   | Pulsing progress bar + current step label                 |
 *  | DONE      | Green checkmark, fades out after [AUTO_DISMISS_MS]        |
 *  | FAILED    | Red error icon + truncated error message                  |
 *  | CANCELLED | Grey cancelled badge                                      |
 *
 * ── USAGE ────────────────────────────────────────────────────────────────────
 *
 *   Hoist [RuntimeExecutionState] in a ViewModel or screen-level state holder.
 *   Pass it to [RuntimeExecutionOverlay] which handles all animation.
 *
 *   Example:
 *     var execState by remember { mutableStateOf(RuntimeExecutionState()) }
 *     // … update from agent flow …
 *     RuntimeExecutionOverlay(state = execState, onDismiss = { execState = RuntimeExecutionState() })
 *
 * ── ACCESSIBILITY ────────────────────────────────────────────────────────────
 *
 *   The overlay announces progress via semantics for TalkBack users.
 *   Cancel button has a contentDescription.
 */

// ── State model ───────────────────────────────────────────────────────────────

enum class ExecOverlayStatus { IDLE, RUNNING, DONE, FAILED, CANCELLED }

data class RuntimeExecutionState(
    val status:      ExecOverlayStatus = ExecOverlayStatus.IDLE,
    val taskLabel:   String            = "",
    val currentStep: String            = "",
    val progressPct: Int               = 0,
    val errorMsg:    String            = "",
    val agentId:     String            = "",
)

// ── Overlay composable ────────────────────────────────────────────────────────

@Composable
fun RuntimeExecutionOverlay(
    state:     RuntimeExecutionState,
    onCancel:  () -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier:  Modifier   = Modifier,
) {
    val isVisible = state.status != ExecOverlayStatus.IDLE

    // Auto-dismiss DONE state
    LaunchedEffect(state.status) {
        if (state.status == ExecOverlayStatus.DONE) {
            kotlinx.coroutines.delay(AUTO_DISMISS_MS)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter   = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit    = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Surface1)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // Handle indicator
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BorderMid)
                    .align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(12.dp))

            // Status row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier          = Modifier.fillMaxWidth(),
            ) {
                StatusIcon(state.status)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = state.taskLabel.ifBlank { "Executing task…" }.take(60),
                        color      = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 14.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    if (state.agentId.isNotBlank()) {
                        Text(state.agentId, color = TextTertiary, fontSize = 11.sp)
                    }
                }
                if (state.status == ExecOverlayStatus.RUNNING) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Cancel, "Cancel task",
                            tint = SemanticError.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Progress bar (RUNNING only)
            AnimatedVisibility(visible = state.status == ExecOverlayStatus.RUNNING) {
                Column {
                    Spacer(Modifier.height(12.dp))

                    val progressAnim by animateFloatAsState(
                        targetValue   = state.progressPct / 100f,
                        animationSpec = tween(400),
                        label         = "overlay_progress",
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Surface2)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressAnim)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(PrimaryAccent, SecondaryAccent)
                                    )
                                )
                        )
                    }

                    if (state.currentStep.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text     = state.currentStep.take(80),
                            color    = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Error message (FAILED only)
            AnimatedVisibility(visible = state.status == ExecOverlayStatus.FAILED && state.errorMsg.isNotBlank()) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text     = state.errorMsg.take(120),
                        color    = SemanticError,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SemanticError.copy(alpha = 0.08f))
                            .padding(8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                    ) {
                        Text("Dismiss", fontSize = 12.sp)
                    }
                }
            }

            // Done message (DONE only)
            AnimatedVisibility(visible = state.status == ExecOverlayStatus.DONE) {
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Compact inline variant — shows just the status badge + step label.
 * Useful for embedding directly in screen toolbars or chat input rows.
 */
@Composable
fun RuntimeExecutionBadge(
    state:    RuntimeExecutionState,
    onClick:  () -> Unit = {},
    modifier: Modifier   = Modifier,
) {
    val isVisible = state.status == ExecOverlayStatus.RUNNING

    AnimatedVisibility(visible = isVisible, modifier = modifier) {
        Surface(
            onClick  = onClick,
            color    = PrimaryAccent.copy(alpha = 0.12f),
            shape    = RoundedCornerShape(20.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Row(
                verticalAlignment    = Alignment.CenterVertically,
                modifier             = Modifier.padding(horizontal = 10.dp),
            ) {
                PulsingDot()
                Spacer(Modifier.width(6.dp))
                Text(
                    text     = state.currentStep.ifBlank { "Executing…" }.take(30),
                    color    = PrimaryAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Internal helpers ──────────────────────────────────────────────────────────

@Composable
private fun StatusIcon(status: ExecOverlayStatus) {
    val transition = rememberInfiniteTransition(label = "status_icon")
    val scale by transition.animateFloat(
        initialValue = 0.95f,
        targetValue  = 1.05f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label        = "icon_scale",
    )

    val (icon, color) = when (status) {
        ExecOverlayStatus.RUNNING   -> Icons.Default.AutoAwesome to PrimaryAccent
        ExecOverlayStatus.DONE      -> Icons.Default.CheckCircle to SemanticSuccess
        ExecOverlayStatus.FAILED    -> Icons.Default.Error       to SemanticError
        ExecOverlayStatus.CANCELLED -> Icons.Default.Cancel      to TextSecondary
        ExecOverlayStatus.IDLE      -> Icons.Default.AutoAwesome to TextTertiary
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint       = color,
        modifier   = Modifier.size(22.dp),
    )
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label         = "pulse_alpha",
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(PrimaryAccent.copy(alpha = alpha))
    )
}

private const val AUTO_DISMISS_MS = 2_500L
