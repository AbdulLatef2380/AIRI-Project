package com.airi.assistant.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn
import java.util.concurrent.TimeUnit

@Composable
fun TaskInfoTrigger(
    isWorking: Boolean,
    isTaskConversation: Boolean,
    executionId: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val events by AgentActivityBus.recentEvents.collectAsState()
    val scoped = remember(events, executionId) {
        events.filter { executionId != null && it.executionId == executionId }
    }
    val shouldShow = isTaskConversation && (isWorking || (executionId != null && scoped.isNotEmpty()))
    if (!shouldShow) return
    val latest = scoped.firstOrNull()
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .background(AiriTheme.surfaceVariant, RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.task_info_title), color = AiriTheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(latest?.message ?: stringResource(R.string.task_info_running), color = AiriTheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
        }
        Text(stringResource(R.string.task_info_open), color = CosmicAccent, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskInfoBottomSheet(
    isWorking: Boolean,
    executionId: String?,
    onDismiss: () -> Unit
) {
    val events by AgentActivityBus.recentEvents.collectAsState()
    val scoped = remember(events, executionId) { events.filter { executionId != null && it.executionId == executionId } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val started = scoped.minOfOrNull { it.timestampMs }
    val elapsed = started?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L
    val metrics = listOf(
        TaskMetric(Icons.Outlined.Terminal, stringResource(R.string.task_metric_tools), scoped.count { it.category == ActivityCategory.TOOL }.toString(), CosmicAccent),
        TaskMetric(Icons.Outlined.FolderOpen, stringResource(R.string.task_metric_files), scoped.count { it.category == ActivityCategory.SANDBOX }.toString(), SemanticSuccess),
        TaskMetric(Icons.Outlined.Cloud, stringResource(R.string.task_metric_api), scoped.count { it.category == ActivityCategory.CONNECTOR }.toString(), SemanticWarn),
        TaskMetric(Icons.Outlined.Description, stringResource(R.string.task_metric_events), scoped.size.toString(), AiriTheme.onSurfaceVariant)
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = AiriTheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(stringResource(R.string.task_info_title), color = AiriTheme.onSurface, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(if (isWorking) stringResource(R.string.task_info_status_running) else stringResource(R.string.task_info_status_finished), color = if (isWorking) CosmicAccent else SemanticSuccess, fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_close), tint = AiriTheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.task_info_what), color = AiriTheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(scoped.firstOrNull()?.message ?: stringResource(R.string.task_info_no_details), color = AiriTheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.task_info_when), color = AiriTheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.task_info_when_value, formatDuration(elapsed)), color = AiriTheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.task_info_why), color = AiriTheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.task_info_why_value), color = AiriTheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(150.dp)) {
                items(metrics) { metric -> MetricCard(metric) }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = AiriTheme.outline.copy(alpha = 0.5f))
            Text(stringResource(R.string.task_info_conditions), color = AiriTheme.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.task_info_conditions_value), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (scoped.any { it.severity == ActivitySeverity.ERROR }) {
                Text(stringResource(R.string.task_info_has_errors), color = SemanticError, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private data class TaskMetric(val icon: ImageVector, val label: String, val value: String, val color: androidx.compose.ui.graphics.Color)

@Composable
private fun MetricCard(metric: TaskMetric) {
    Row(Modifier.fillMaxWidth().background(AiriTheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(metric.icon, contentDescription = null, tint = metric.color, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(horizontal = 8.dp)) {
            Text(metric.value, color = AiriTheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(metric.label, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}
