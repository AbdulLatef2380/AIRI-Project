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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn
import java.util.concurrent.TimeUnit

/** Four observable execution signals used by the task-information UX. */
data class TaskActivityMetrics(
    val pagesViewed: Int = 0,
    val commandsExecuted: Int = 0,
    val apiCalls: Int = 0,
    val filesCreated: Int = 0,
    val scopedEvents: List<ActivityEvent> = emptyList(),
) {
    val satisfiedConditions: Int
        get() = listOf(pagesViewed, commandsExecuted, apiCalls, filesCreated).count { it > 0 }

    /** The panel is deliberately quiet until two different kinds of work occurred. */
    val isEligible: Boolean
        get() = satisfiedConditions >= 2
}

/**
 * Projects the shared activity bus into user-facing task signals.
 * Events without an owning execution are excluded, preventing old sessions or
 * background connector health notices from unlocking the current task panel.
 */
fun taskActivityMetrics(
    events: List<ActivityEvent>,
    executionId: String?,
): TaskActivityMetrics {
    val owner = executionId?.takeIf { it.isNotBlank() } ?: return TaskActivityMetrics()
    val scoped = events.filter { it.executionId == owner }
    fun normalizedMessages(predicate: (String) -> Boolean): Int = scoped
        .asSequence()
        .filter { predicate(it.message.lowercase()) }
        .map { it.message.trim().lowercase() }
        .distinct()
        .count()

    val pages = scoped.count { it.machineTag == "page_viewed" }.coerceAtLeast(
        normalizedMessages { message ->
            message.contains("search") || message.contains("fetch") ||
                message.contains("page") || message.contains("browser") ||
                message.contains("navigate") || message.contains("url") || message.contains("صفحة")
        }
    )
    val commands = scoped
        .filter { it.machineTag == "command_executed" || it.category == ActivityCategory.SANDBOX }
        .filterNot { it.message.contains("session created", ignoreCase = true) || it.message.contains("session closed", ignoreCase = true) }
        .map { it.message.trim().lowercase() }
        .distinct()
        .count()
    val api = scoped
        .filter { it.category == ActivityCategory.CONNECTOR }
        .filterNot { it.message.contains("unavailable", ignoreCase = true) }
        .map { it.message.trim().lowercase() }
        .distinct()
        .count()
    val files = scoped.count { it.machineTag == "file_created" }.coerceAtLeast(
        normalizedMessages { message ->
            message.contains("artifact") || message.contains("file") ||
                message.contains("saved") || message.contains("write") || message.contains("ملف")
        }
    )
    return TaskActivityMetrics(pages, commands, api, files, scoped)
}

/** Compact top-bar trigger. It always offers exactly two actions. */
@Composable
fun TaskInfoMenu(
    metrics: TaskActivityMetrics,
    onSearch: () -> Unit,
    onTaskInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val iconTint = if (metrics.isEligible) SemanticError else AiriTheme.onBackground.copy(alpha = 0.65f)
    androidx.compose.foundation.layout.Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = stringResource(R.string.task_info_title),
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(AiriTheme.surfaceVariant, AIRIShapes.md),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_search), color = AiriTheme.onBackground) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = CosmicAccent) },
                onClick = { expanded = false; onSearch() },
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.task_info_title), color = AiriTheme.onBackground)
                        if (!metrics.isEligible) {
                            Text(
                                stringResource(R.string.task_info_locked_hint),
                                color = AiriTheme.onSurfaceVariant,
                                fontSize = 10.sp,
                            )
                        }
                    }
                },
                leadingIcon = { Icon(Icons.Outlined.History, null, tint = if (metrics.isEligible) SemanticError else AiriTheme.onSurfaceVariant) },
                enabled = metrics.isEligible,
                onClick = { expanded = false; onTaskInfo() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskInfoBottomSheet(
    isWorking: Boolean,
    executionId: String?,
    onDismiss: () -> Unit,
) {
    val events by AgentActivityBus.recentEvents.collectAsState()
    val metrics = remember(events, executionId) { taskActivityMetrics(events, executionId) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val started = metrics.scopedEvents.minOfOrNull { it.timestampMs }
    val elapsed = started?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L
    val cards = listOf(
        TaskMetric(Icons.Outlined.Description, stringResource(R.string.task_metric_pages), metrics.pagesViewed, CosmicAccent),
        TaskMetric(Icons.Outlined.Terminal, stringResource(R.string.task_metric_tools), metrics.commandsExecuted, SemanticError),
        TaskMetric(Icons.Outlined.Cloud, stringResource(R.string.task_metric_api), metrics.apiCalls, SemanticWarn),
        TaskMetric(Icons.Outlined.FolderOpen, stringResource(R.string.task_metric_files), metrics.filesCreated, SemanticSuccess),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = AiriTheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = SemanticError, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(stringResource(R.string.task_info_title), color = AiriTheme.onSurface, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(if (isWorking) stringResource(R.string.task_info_status_running) else stringResource(R.string.task_info_status_finished), color = if (isWorking) SemanticError else SemanticSuccess, fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.cd_close), tint = AiriTheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.task_info_signal_title), color = AiriTheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.task_info_signal_value), color = AiriTheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(156.dp),
            ) { items(cards) { MetricCard(it) } }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().background(AiriTheme.surfaceVariant, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.History, null, tint = CosmicAccent, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(stringResource(R.string.task_info_when), color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
                    Text(stringResource(R.string.task_info_when_value, formatDuration(elapsed)), color = AiriTheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("${metrics.satisfiedConditions}/4", color = SemanticError, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = AiriTheme.outline.copy(alpha = 0.5f))
            Text(stringResource(R.string.task_info_conditions), color = AiriTheme.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.task_info_conditions_value), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (metrics.scopedEvents.any { it.severity == ActivitySeverity.ERROR }) {
                Text(stringResource(R.string.task_info_has_errors), color = SemanticError, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private data class TaskMetric(val icon: ImageVector, val label: String, val value: Int, val color: Color)

@Composable
private fun MetricCard(metric: TaskMetric) {
    Row(Modifier.fillMaxWidth().background(AiriTheme.surfaceVariant, RoundedCornerShape(18.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(metric.icon, null, tint = metric.color, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(horizontal = 8.dp)) {
            Text(metric.value.toString(), color = AiriTheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(metric.label, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}
