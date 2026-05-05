package com.airi.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.ExecutionStage

/**
 * AgentExecutionPanel — live execution status overlay shown above the chat input bar.
 *
 * Displays structured real-time information from [AgentState] while the
 * autonomous agent is executing a DAG task graph. Disappears when idle.
 *
 * Visual regions:
 *  1. Stage badge (icon + PLANNING / EXECUTING / RECOVERING / REFLECTING)
 *  2. Active goal description (scrolling single line)
 *  3. Node progress bar (nodesCompleted / nodesTotal)
 *  4. Recovery indicator (shown only when executionStage == RECOVERING)
 *
 * The entire panel uses AnimatedVisibility with fade + expand so it
 * appears and disappears smoothly without shifting the message list.
 *
 * Colours follow the material theme so it adapts to dark/light mode.
 * The pulsing dot on the stage badge is implemented via InfiniteTransition
 * — it stops pulsing when execution is idle (no wasted animations).
 */
@Composable
fun AgentExecutionPanel(
    agentState: AgentState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = agentState.isWorking,
        enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
        exit    = fadeOut(tween(150)) + shrinkVertically(tween(200)),
        modifier = modifier,
    ) {
        Surface(
            modifier      = Modifier.fillMaxWidth(),
            color         = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
            tonalElevation = 2.dp,
            shape         = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StageRow(agentState)
                if (agentState.activeGoalDescription.isNotBlank()) {
                    GoalLine(agentState.activeGoalDescription)
                } else if (agentState.currentAction.isNotBlank()) {
                    GoalLine(agentState.currentAction)
                }
                if (agentState.nodesTotal > 0) {
                    NodeProgressBar(agentState.nodesCompleted, agentState.nodesTotal)
                }
                if (agentState.executionStage == ExecutionStage.RECOVERING) {
                    RecoveryIndicator(agentState.recoveryReason, agentState.retryCount)
                }
            }
        }
    }
}

@Composable
private fun StageRow(state: AgentState) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.75f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot_pulse",
    )

    val (icon, label, dotColor) = stageVisuals(state.executionStage)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint    = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp),
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp,
        )
        if (state.activeNodeId.isNotBlank()) {
            Text(
                text  = "· ${state.activeNodeId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun GoalLine(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.bodySmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NodeProgressBar(completed: Int, total: Int) {
    val progress by animateFloatAsState(
        targetValue = if (total > 0) completed.toFloat() / total else 0f,
        animationSpec = tween(400),
        label = "node_progress",
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LinearProgressIndicator(
            progress    = progress,
            modifier    = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
            strokeCap   = StrokeCap.Round,
            trackColor  = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            text  = "$completed / $total nodes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun RecoveryIndicator(reason: String, retryCount: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = null,
            tint     = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text  = "Retry #$retryCount${if (reason.isNotBlank()) " — $reason" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private data class StageVisuals(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val dotColor: Color,
)

@Composable
private fun stageVisuals(stage: ExecutionStage): StageVisuals {
    val primary   = MaterialTheme.colorScheme.primary
    val tertiary  = MaterialTheme.colorScheme.tertiary
    val error     = MaterialTheme.colorScheme.error
    val secondary = MaterialTheme.colorScheme.secondary
    return when (stage) {
        ExecutionStage.PLANNING    -> StageVisuals(Icons.Filled.Psychology,   "PLANNING",    tertiary)
        ExecutionStage.EXECUTING   -> StageVisuals(Icons.Filled.AutoFixHigh,  "EXECUTING",   primary)
        ExecutionStage.RECOVERING  -> StageVisuals(Icons.Filled.Refresh,      "RECOVERING",  error)
        ExecutionStage.REFLECTING  -> StageVisuals(Icons.Filled.Timeline,     "REFLECTING",  secondary)
        ExecutionStage.COMPLETED   -> StageVisuals(Icons.Filled.AutoFixHigh,  "COMPLETED",   primary)
        ExecutionStage.FAILED      -> StageVisuals(Icons.Filled.Refresh,      "FAILED",      error)
        ExecutionStage.IDLE        -> StageVisuals(Icons.Filled.AutoFixHigh,  "WORKING",     primary)
    }
}
