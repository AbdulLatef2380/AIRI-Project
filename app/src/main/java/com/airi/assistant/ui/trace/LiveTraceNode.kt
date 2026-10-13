package com.airi.assistant.ui.trace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.agent.trace.*
import com.airi.assistant.ui.theme.*

@Composable
fun LiveTraceNode(nodeState: TraceNodeState, modifier: Modifier = Modifier) {
    var expanded by remember(nodeState.id) { mutableStateOf(false) }
    val hasDetails = nodeState.step is TraceStep.WebSearch || nodeState.step is TraceStep.Planning || nodeState.step is TraceStep.StepError
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .then(if (hasDetails) Modifier.clickable { expanded = !expanded } else Modifier)
            .animateContentSize(),
        color = AiriTheme.surfaceVariant.copy(alpha = .62f),
        shape = AIRIShapes.sm,
        border = androidx.compose.foundation.BorderStroke(.5.dp, AiriTheme.outline.copy(alpha = .45f))
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateIndicator(nodeState.executionState)
                Spacer(Modifier.width(7.dp))
                ToolBadgeIcon(nodeState.step, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(traceNodeLabel(nodeState.step, nodeState.executionState), color = AiriTheme.onSurface.copy(alpha = .9f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (hasDetails) Text(if (expanded) "▲" else "▾", color = AiriTheme.onSurfaceVariant, fontSize = 10.sp)
            }
            AnimatedVisibility(expanded && hasDetails, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                TraceNodeDetail(nodeState.step)
            }
        }
    }
}

@Composable
private fun StateIndicator(state: NodeExecutionState) {
    when (state) {
        NodeExecutionState.RUNNING -> CircularProgressIndicator(Modifier.size(12.dp), color = CosmicAccent, strokeWidth = 1.5.dp)
        NodeExecutionState.DONE -> Text("✓", color = SemanticSuccess, fontSize = 12.sp)
        NodeExecutionState.FAILED -> Text("✗", color = SemanticError, fontSize = 12.sp)
    }
}

@Composable
fun TraceNodeDetail(step: TraceStep) {
    Column(Modifier.padding(start = 22.dp, top = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        when (step) {
            is TraceStep.WebSearch -> step.urls.take(12).forEach { url -> SelectionContainer { Text(url, fontSize = 11.sp, color = CosmicAccentAlt, fontFamily = FontFamily.Monospace) } }
            is TraceStep.Planning -> step.tasks.forEachIndexed { index, task -> Text("${if (task.state == TaskState.DONE) "✓" else "○"} ${index + 1}. ${task.label}", color = AiriTheme.onSurface.copy(alpha = if (task.state == TaskState.PENDING) .5f else .9f), fontSize = 11.sp) }
            is TraceStep.StepError -> Text(step.errorMessage.take(240), color = SemanticError, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            else -> Unit
        }
    }
}
