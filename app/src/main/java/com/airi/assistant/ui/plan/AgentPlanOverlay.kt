package com.airi.assistant.ui.plan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn
import com.airi.assistant.ui.viewmodel.ExecutionStage
import androidx.compose.ui.res.stringResource

/** AIRI's compact execution center: a projection only, never a second runtime owner. */
@Composable
fun AgentPlanOverlay(
    modifier: Modifier = Modifier,
    planViewModel: AgentPlanViewModel = viewModel()
) {
    val isVisible by planViewModel.isVisible.collectAsStateWithLifecycle()
    val isExpanded by planViewModel.isPanelExpanded.collectAsStateWithLifecycle()
    val steps by planViewModel.steps.collectAsStateWithLifecycle()
    val stage by planViewModel.currentStage.collectAsStateWithLifecycle()
    val goal by planViewModel.goalDescription.collectAsStateWithLifecycle()
    val traceEntries by planViewModel.traceEntries.collectAsStateWithLifecycle()
    val panelTitle = stringResource(R.string.execution_steps_label)
    val stageText = stageLabel(stage)
    val completed = steps.count { it.status == PlanStepStatus.COMPLETED }
    val terminal = steps.count { it.status.isTerminal }
    val progress = if (steps.isEmpty()) 0f else (completed.toFloat() / steps.size).coerceIn(0f, 1f)
    val activeStep = steps.lastOrNull { it.status.isActive }
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val accent = stageAccent(stage)

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        val listState = rememberLazyListState()
        LaunchedEffect(activeStep?.id) {
            val index = steps.indexOfFirst { it.id == activeStep?.id }
            if (index >= 0) listState.animateScrollToItem(index)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AiriTheme.surface.copy(alpha = 0.985f))
                .border(1.dp, accent.copy(alpha = 0.28f), shape)
                .semantics {
                    paneTitle = panelTitle
                    contentDescription = "$panelTitle · $stageText · $completed/${steps.size}"
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { planViewModel.toggleExpanded() }
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SignalMark(accent, stage == ExecutionStage.EXECUTING || stage == ExecutionStage.RECOVERING)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("AIRI  ·  $panelTitle", color = AiriTheme.onSurface.copy(alpha = 0.55f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(stageText, color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("$completed/${steps.size}", color = AiriTheme.onSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = AiriTheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.size(28.dp),
                )
                if (stage.isTerminal()) {
                    IconButton(onClick = planViewModel::dismissPanel, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.agent_plan_dismiss_cd), tint = AiriTheme.onSurface.copy(alpha = 0.62f))
                    }
                }
            }
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .padding(horizontal = 16.dp)
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) },
                color = accent,
                trackColor = AiriTheme.outline.copy(alpha = 0.35f),
            )
            if (goal.isNotBlank()) {
                Text(goal.take(120), color = AiriTheme.onSurface.copy(alpha = 0.72f), fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            ExecutionMetrics(completed, terminal, steps.size, accent)
            if (traceEntries.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(AiriTheme.outline.copy(alpha = 0.22f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .semantics { contentDescription = stringResource(R.string.agent_plan_latest_signals) },
                ) {
                    Text(
                        stringResource(R.string.agent_plan_latest_signals),
                        color = accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                    traceEntries.takeLast(3).forEach { entry ->
                        Text(
                            text = "#${entry.sequence}  ${entry.summary}",
                            color = AiriTheme.onSurface.copy(alpha = 0.72f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            AnimatedVisibility(visible = isExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                if (steps.isEmpty()) {
                    Text(stringResource(R.string.agent_plan_initialising), color = AiriTheme.onSurface.copy(alpha = 0.45f), fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(steps, key = { it.id }) { step -> AirisStepRow(step, accent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionMetrics(completed: Int, terminal: Int, total: Int, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricPill(stringResource(R.string.agent_plan_metric_done), completed.toString(), SemanticSuccess)
        MetricPill(stringResource(R.string.agent_plan_metric_closed), terminal.toString(), accent)
        MetricPill(stringResource(R.string.agent_plan_metric_total), total.toString(), AiriTheme.onSurface.copy(alpha = 0.55f))
    }
}

@Composable
private fun MetricPill(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        Text(value, color = AiriTheme.onSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AirisStepRow(step: PlanStepModel, accent: Color) {
    val status = planStepStatusLabel(step.status)
    val color = stepColor(step.status, accent)
    Row(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$status: ${step.label}" }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(stepIcon(step.status), null, tint = color, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            Text(step.label, color = if (step.status.isActive) AiriTheme.onSurface else color.copy(alpha = 0.82f), fontSize = 12.sp, fontWeight = if (step.status.isActive) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2)
            val detail = when {
                step.status == PlanStepStatus.RETRYING -> stringResource(R.string.agent_plan_retry_count, step.retryCount)
                !step.detail.isNullOrBlank() -> step.detail.take(100)
                else -> status
            }
            Text(detail, color = color.copy(alpha = 0.72f), fontSize = 10.sp, maxLines = 2)
        }
        step.elapsedLabel?.let { Text(it, color = AiriTheme.onSurface.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun SignalMark(accent: Color, active: Boolean) = Box(Modifier.size(10.dp).clip(CircleShape).background(accent.copy(alpha = if (active) 1f else 0.72f)))

private fun ExecutionStage.isTerminal() = this == ExecutionStage.COMPLETED || this == ExecutionStage.FAILED || this == ExecutionStage.CANCELLED

@Composable
private fun stageAccent(stage: ExecutionStage): Color = when (stage) {
    ExecutionStage.PLANNING, ExecutionStage.EXECUTING -> CosmicAccent
    ExecutionStage.RECOVERING -> SemanticWarn
    ExecutionStage.REFLECTING -> CosmicAccent.copy(alpha = 0.85f)
    ExecutionStage.COMPLETED -> SemanticSuccess
    ExecutionStage.FAILED -> SemanticError
    ExecutionStage.CANCELLED, ExecutionStage.IDLE -> AiriTheme.onSurface.copy(alpha = 0.55f)
}

@Composable
private fun stageLabel(stage: ExecutionStage): String = stringResource(when (stage) {
    ExecutionStage.PLANNING -> R.string.agent_plan_stage_planning
    ExecutionStage.EXECUTING -> R.string.agent_plan_stage_executing
    ExecutionStage.RECOVERING -> R.string.agent_plan_stage_recovering
    ExecutionStage.REFLECTING -> R.string.agent_plan_stage_reflecting
    ExecutionStage.COMPLETED -> R.string.agent_plan_stage_completed
    ExecutionStage.FAILED -> R.string.agent_plan_stage_failed
    ExecutionStage.CANCELLED -> R.string.agent_plan_stage_cancelled
    ExecutionStage.IDLE -> R.string.agent_plan_stage_idle
})

@Composable
private fun planStepStatusLabel(status: PlanStepStatus): String = stringResource(when (status) {
    PlanStepStatus.QUEUED -> R.string.agent_plan_step_queued
    PlanStepStatus.RUNNING -> R.string.agent_plan_step_running
    PlanStepStatus.COMPLETED -> R.string.agent_plan_step_completed
    PlanStepStatus.FAILED -> R.string.agent_plan_step_failed
    PlanStepStatus.RETRYING -> R.string.agent_plan_step_retrying
    PlanStepStatus.CANCELLED -> R.string.agent_plan_step_cancelled
})

private fun stepIcon(status: PlanStepStatus) = when (status) {
    PlanStepStatus.QUEUED -> Icons.Outlined.HourglassEmpty
    PlanStepStatus.RUNNING -> Icons.Outlined.PlayArrow
    PlanStepStatus.COMPLETED -> Icons.Outlined.CheckCircle
    PlanStepStatus.FAILED -> Icons.Outlined.ErrorOutline
    PlanStepStatus.RETRYING -> Icons.Outlined.PauseCircleOutline
    PlanStepStatus.CANCELLED -> Icons.Outlined.Close
}

private fun stepColor(status: PlanStepStatus, accent: Color): Color = when (status) {
    PlanStepStatus.QUEUED -> AiriTheme.onSurface.copy(alpha = 0.38f)
    PlanStepStatus.RUNNING -> accent
    PlanStepStatus.RETRYING -> SemanticWarn
    PlanStepStatus.COMPLETED -> SemanticSuccess
    PlanStepStatus.FAILED -> SemanticError
    PlanStepStatus.CANCELLED -> AiriTheme.onSurface.copy(alpha = 0.42f)
}
