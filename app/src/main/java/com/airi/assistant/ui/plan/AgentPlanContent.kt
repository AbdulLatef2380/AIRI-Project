package com.airi.assistant.ui.plan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn
import com.airi.assistant.ui.viewmodel.ExecutionStage

/**
 * AP-C03/AP-C06: Agent Plan content for ModalBottomSheet.
 *
 * Extracted from [AgentPlanOverlay] and extended with:
 *   - Live step-status icons (PENDING / RUNNING / DONE / FAILED)
 *   - Elapsed timing display for RUNNING steps (AP-C06)
 *   - Active tool call sub-items per step (AP-C06)
 *   - Dismiss handle for ModalBottomSheet
 *
 * [AgentPlanOverlay] is preserved for backward compat on inline/tablet layout paths.
 */
@Composable
fun AgentPlanContent(
    viewModel: AgentPlanViewModel,
    modifier: Modifier = Modifier
) {
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val stage by viewModel.currentStage.collectAsStateWithLifecycle()
    val goal  by viewModel.goalDescription.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val runningIdx = steps.indexOfLast { it.status.isActive }
    LaunchedEffect(runningIdx) { if (runningIdx >= 0) listState.animateScrollToItem(runningIdx) }

    Column(modifier = modifier.padding(bottom = 16.dp)) {

        // ── Header ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ContentPulseDot(color = stageColor(stage), active = stage == ExecutionStage.EXECUTING)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stageLabel(stage),
                    fontSize    = 12.sp,
                    fontWeight  = FontWeight.SemiBold,
                    color       = stageColor(stage)
                )
                if (goal.isNotBlank()) {
                    Text(
                        goal.take(80),
                        fontSize = 12.sp,
                        color    = Color.White.copy(alpha = 0.65f),
                        maxLines = 2
                    )
                }
            }
            val done = steps.count { it.status == PlanStepStatus.COMPLETED }
            if (steps.isNotEmpty()) {
                Text(
                    "$done / ${steps.size}",
                    fontSize = 11.sp,
                    color    = Color.White.copy(alpha = 0.45f)
                )
            }
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Dismiss",
                tint     = Color.White.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(18.dp)
                    .clickable { viewModel.collapse() }
            )
        }

        Divider(color = Color.White.copy(alpha = 0.07f))

        // ── Steps ────────────────────────────────────────────────────────────
        if (steps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Initialising…", fontSize = 12.sp, color = Color.White.copy(alpha = 0.35f))
            }
        } else {
            LazyColumn(
                state   = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                items(steps, key = { it.id }) { step ->
                    PlanStepRow(step = step)
                    Divider(
                        color     = Color.White.copy(alpha = 0.04f),
                        thickness = 0.5.dp,
                        modifier  = Modifier.padding(horizontal = 14.dp)
                    )
                }
            }
        }
    }
}

// ── Step row with live timing (AP-C06) ────────────────────────────────────────

@Composable
private fun PlanStepRow(step: PlanStepModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Status icon
        Box(modifier = Modifier.padding(top = 2.dp)) {
            when (step.status) {
                PlanStepStatus.QUEUED   -> Icon(Icons.Outlined.HourglassEmpty, null,
                    tint = Color.White.copy(0.3f), modifier = Modifier.size(14.dp))
                PlanStepStatus.RUNNING   -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp), color = CosmicAccent, strokeWidth = 1.5.dp)
                PlanStepStatus.COMPLETED -> Icon(Icons.Outlined.Check, null,
                    tint = SemanticSuccess, modifier = Modifier.size(14.dp))
                PlanStepStatus.FAILED    -> Icon(Icons.Outlined.ErrorOutline, null,
                    tint = SemanticError, modifier = Modifier.size(14.dp))
                PlanStepStatus.CANCELLED   -> Icon(Icons.Outlined.PlayArrow, null,
                    tint = Color.White.copy(0.25f), modifier = Modifier.size(14.dp))
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    step.label,
                    fontSize   = 12.sp,
                    fontWeight = if (step.status == PlanStepStatus.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
                    color      = when (step.status) {
                        PlanStepStatus.RUNNING   -> Color.White
                        PlanStepStatus.COMPLETED -> Color.White.copy(0.55f)
                        PlanStepStatus.FAILED    -> SemanticError.copy(0.85f)
                        else                     -> Color.White.copy(0.4f)
                    },
                    modifier = Modifier.weight(1f)
                )

                // AP-C06: Elapsed time for RUNNING step
                if (step.status == PlanStepStatus.RUNNING && step.startedAtMs != null) {
                    val elapsed = (System.currentTimeMillis() - step.startedAtMs) / 1000L
                    Text(
                        "${elapsed}s",
                        fontSize = 10.sp,
                        color    = CosmicAccent.copy(0.55f),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // AP-C06: Active tool call sub-item
            if (step.status == PlanStepStatus.RUNNING && !step.detail.isNullOrBlank()) {
                Text(
                    "→ ${step.detail}",
                    fontSize = 10.sp,
                    color    = CosmicAccent.copy(0.6f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Error detail
            if (step.status == PlanStepStatus.FAILED && !step.subLabel.isNullOrBlank()) {
                Text(
                    step.subLabel,
                    fontSize = 10.sp,
                    color    = SemanticError.copy(0.65f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ContentPulseDot(color: Color, active: Boolean) {
    val alpha by if (active) {
        rememberInfiniteTransition(label = "cpulse").animateFloat(
            initialValue  = 0.4f,
            targetValue   = 1.0f,
            animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label         = "cpulseAlpha"
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.7f) }
    }
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

private fun stageColor(stage: ExecutionStage) = when (stage) {
    ExecutionStage.PLANNING   -> CosmicAccent
    ExecutionStage.EXECUTING  -> CosmicAccent
    ExecutionStage.RECOVERING -> SemanticWarn
    ExecutionStage.REFLECTING -> Color(0xFFB57BFF)
    ExecutionStage.COMPLETED  -> SemanticSuccess
    ExecutionStage.FAILED     -> SemanticError
    ExecutionStage.IDLE       -> Color.White.copy(alpha = 0.4f)
}

private fun stageLabel(stage: ExecutionStage) = when (stage) {
    ExecutionStage.PLANNING   -> "Planning"
    ExecutionStage.EXECUTING  -> "Executing"
    ExecutionStage.RECOVERING -> "Recovering"
    ExecutionStage.REFLECTING -> "Reflecting"
    ExecutionStage.COMPLETED  -> "Completed"
    ExecutionStage.FAILED     -> "Failed"
    ExecutionStage.IDLE       -> "Idle"
}
