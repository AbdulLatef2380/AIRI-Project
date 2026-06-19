package com.airi.assistant.ui.plan

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ExecutionStage
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

@Composable
fun AgentPlanOverlay(
    modifier: Modifier = Modifier,
    planViewModel: AgentPlanViewModel = viewModel()
) {
    val isVisible  by planViewModel.isVisible.collectAsStateWithLifecycle()
    val isExpanded by planViewModel.isPanelExpanded.collectAsStateWithLifecycle()
    val steps      by planViewModel.steps.collectAsStateWithLifecycle()
    val stage      by planViewModel.currentStage.collectAsStateWithLifecycle()
    val goal       by planViewModel.goalDescription.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible  = isVisible,
        enter    = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit     = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        val listState = rememberLazyListState()
        val runningIdx = steps.indexOfLast { it.status.isActive }
        LaunchedEffect(runningIdx) { if (runningIdx >= 0) listState.animateScrollToItem(runningIdx) }

        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(shape)
                .background(Color(0xFF111827).copy(alpha = 0.97f))
                .border(0.5.dp, stageAccent(stage).copy(alpha = 0.30f), shape)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { planViewModel.toggleExpanded() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                PulseDot(color = stageAccent(stage), active = stage == ExecutionStage.EXECUTING || stage == ExecutionStage.RECOVERING)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stageLabel(stage), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = stageAccent(stage))
                    if (goal.isNotBlank()) Text(goal.take(72), fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                }
                val done = steps.count { it.status == PlanStepStatus.COMPLETED }
                if (steps.isNotEmpty()) Text("$done/${steps.size}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f), modifier = Modifier.padding(end = 10.dp))
                Text(if (isExpanded) "⌃" else "⌄", fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))
                Spacer(Modifier.width(10.dp))
                if (stage == ExecutionStage.COMPLETED || stage == ExecutionStage.FAILED || stage == ExecutionStage.IDLE)
                    Text("✕", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.clickable { planViewModel.dismissPanel() })
            }
            // Steps
            AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                if (steps.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.agent_plan_initialising), fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        items(items = steps, key = { it.id }) { step ->
                            AgentPlanCard(step = step)
                            Divider(color = Color.White.copy(alpha = 0.04f), thickness = 0.5.dp,
                                modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PulseDot(color: Color, active: Boolean) {
    val alpha by if (active) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.4f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulseAlpha")
    } else remember { mutableStateOf(if (color == SemanticSuccess) 1.0f else 0.5f) }
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

private fun stageAccent(stage: ExecutionStage) = when (stage) {
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
