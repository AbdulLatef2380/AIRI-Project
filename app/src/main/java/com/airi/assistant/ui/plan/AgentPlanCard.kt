package com.airi.assistant.ui.plan

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

@Composable
fun AgentPlanCard(step: PlanStepModel, depth: Int = 0, modifier: Modifier = Modifier) {
    var expanded by remember(step.id) { mutableStateOf(false) }
    val hasDetail = !step.detail.isNullOrBlank() || step.children.isNotEmpty()

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(start = (12 + depth * 16).dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
        ) {
            StepIndicator(status = step.status)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.label, fontSize = 13.sp,
                    fontWeight = if (step.status.isActive) FontWeight.Medium else FontWeight.Normal,
                    color = labelColor(step.status), maxLines = 2
                )
                if (!step.subLabel.isNullOrBlank())
                    Text(step.subLabel, fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f), maxLines = 1)
                if (step.retryCount > 0)
                    Text(stringResource(R.string.agent_plan_retry_count, step.retryCount), fontSize = 10.sp, color = SemanticWarn.copy(alpha = 0.8f))
            }
            step.elapsedLabel?.let { Text(it, fontSize = 10.sp, color = Color.White.copy(alpha = 0.3f), modifier = Modifier.padding(start = 8.dp)) }
        }
        AnimatedVisibility(visible = expanded && hasDetail, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(modifier = Modifier.padding(start = (28 + depth * 16).dp, end = 12.dp, bottom = 6.dp)) {
                if (!step.detail.isNullOrBlank())
                    Text(step.detail, fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f), lineHeight = 16.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.04f)).padding(8.dp))
                step.children.forEach { AgentPlanCard(step = it, depth = depth + 1) }
            }
        }
    }
}

@Composable
private fun StepIndicator(status: PlanStepStatus) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(18.dp)) {
        when (status) {
            PlanStepStatus.RUNNING, PlanStepStatus.RETRYING -> SpinnerDot(
                color = if (status == PlanStepStatus.RETRYING) SemanticWarn else CosmicAccent)
            PlanStepStatus.COMPLETED -> Box(modifier = Modifier.size(14.dp).clip(CircleShape)
                .background(SemanticSuccess.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text("✓", fontSize = 9.sp, color = SemanticSuccess) }
            PlanStepStatus.FAILED -> Box(modifier = Modifier.size(14.dp).clip(CircleShape)
                .background(SemanticError.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text("✕", fontSize = 9.sp, color = SemanticError) }
            PlanStepStatus.CANCELLED -> Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)))
            PlanStepStatus.QUEUED    -> Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
        }
    }
}

@Composable
private fun SpinnerDot(color: Color) {
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "rot")
    Box(modifier = Modifier.size(14.dp).rotate(rotation)) {
        Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)))
        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(color).align(Alignment.TopCenter))
    }
}

private fun labelColor(status: PlanStepStatus) = when (status) {
    PlanStepStatus.RUNNING   -> Color.White
    PlanStepStatus.RETRYING  -> SemanticWarn
    PlanStepStatus.COMPLETED -> Color.White.copy(alpha = 0.55f)
    PlanStepStatus.FAILED    -> SemanticError.copy(alpha = 0.9f)
    PlanStepStatus.CANCELLED -> Color.White.copy(alpha = 0.3f)
    PlanStepStatus.QUEUED    -> Color.White.copy(alpha = 0.4f)
}
