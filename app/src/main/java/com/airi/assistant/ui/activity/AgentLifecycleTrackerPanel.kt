package com.airi.assistant.ui.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.trace.UnifiedExecutionTraceGroup
import com.airi.assistant.ui.trace.UnifiedExecutionTraceProjection
import com.airi.assistant.ui.trace.UnifiedTraceCategory

/** Compact, borderless activity surface embedded in the assistant turn. */
@Composable
fun AgentLifecycleTrackerPanel(
    projection: UnifiedExecutionTraceProjection,
    isWorking: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val groups = projection.groups
    var expanded by rememberSaveable { mutableStateOf(true) }
    val groupIds = groups.map { it.id }
    var expandedGroups by androidx.compose.runtime.remember(groupIds) { mutableStateOf(groupIds.toSet()) }
    var expandedEvents by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val lastEventKey = projection.events.lastOrNull()?.let { it.eventId to it.status }

    LaunchedEffect(lastEventKey, expanded) {
        if (expanded && groups.isNotEmpty() && listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 >= listState.layoutInfo.totalItemsCount - 2) {
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }

    if (projection.events.isEmpty() && !isWorking) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        ActivitySummaryRow(
            summary = projection,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            isWorking = isWorking,
        )
        AnimatedVisibility(visible = expanded) {
            if (groups.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 238.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    groups.forEach { group ->
                        item(key = "group-${group.id}") {
                            ActivityGroupHeader(
                                group = group,
                                expanded = group.id in expandedGroups,
                                onToggle = {
                                    expandedGroups = if (group.id in expandedGroups) expandedGroups - group.id else expandedGroups + group.id
                                },
                            )
                        }
                        if (group.id in expandedGroups) {
                            items(group.events, key = { "event-${it.eventId}" }) { event ->
                                ActivityEventRow(
                                    event = event,
                                    expanded = event.eventId in expandedEvents,
                                    onToggle = {
                                        expandedEvents = if (event.eventId in expandedEvents) expandedEvents - event.eventId else expandedEvents + event.eventId
                                    },
                                )
                            }
                        }
                    }
                }
            } else if (isWorking) {
                Text(
                    text = stringResource(R.string.agent_lifecycle_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ActivitySummaryRow(
    summary: UnifiedExecutionTraceProjection,
    expanded: Boolean,
    onToggle: () -> Unit,
    isWorking: Boolean,
) {
    val items = buildList {
        summary.summary.filesRead.takeIf { it > 0 }?.let { add(stringResource(R.string.agent_trace_summary_files, it)) }
        summary.summary.commandsExecuted.takeIf { it > 0 }?.let { add(stringResource(R.string.agent_trace_summary_commands, it)) }
        summary.summary.toolsUsed.takeIf { it > 0 }?.let { add(stringResource(R.string.agent_trace_summary_tools, it)) }
        summary.summary.searches.takeIf { it > 0 }?.let { add(stringResource(R.string.agent_trace_summary_searches, it)) }
        summary.summary.tasks.takeIf { it > 0 }?.let { add(stringResource(R.string.agent_trace_summary_tasks, it)) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isWorking) Icons.Outlined.Build else Icons.Outlined.TaskAlt,
            contentDescription = null,
            tint = if (isWorking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = items.joinToString(" · ").ifBlank { stringResource(R.string.agent_lifecycle_working) },
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ActivityGroupHeader(group: UnifiedExecutionTraceGroup, expanded: Boolean, onToggle: () -> Unit) {
    val title = when (group.category) {
        UnifiedTraceCategory.FILE -> stringResource(R.string.agent_trace_group_files)
        UnifiedTraceCategory.COMMAND -> stringResource(R.string.agent_trace_group_commands)
        UnifiedTraceCategory.TOOL, UnifiedTraceCategory.CONNECTOR -> stringResource(R.string.agent_trace_group_tools)
        UnifiedTraceCategory.SEARCH -> stringResource(R.string.agent_trace_group_search)
        UnifiedTraceCategory.MEMORY -> stringResource(R.string.agent_trace_group_memory)
        UnifiedTraceCategory.PLAN, UnifiedTraceCategory.TASK -> stringResource(R.string.agent_trace_group_tasks)
        UnifiedTraceCategory.SYSTEM -> stringResource(R.string.agent_trace_group_activity)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(start = 26.dp, end = 6.dp, top = 5.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityCategoryIcon(group.category, Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            text = "$title · ${group.events.size}",
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun ActivityEventRow(
    event: com.airi.assistant.ui.trace.UnifiedExecutionTraceEvent,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val canExpand = !event.detailLog.isNullOrBlank()
    val color = when (event.status) {
        TaskStatus.RUNNING -> MaterialTheme.colorScheme.onSurface
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        TaskStatus.PENDING, TaskStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (canExpand) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
                .padding(start = 48.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActivityCategoryIcon(event.category, Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                text = event.title,
                modifier = Modifier.weight(1f),
                color = color,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (event.status == TaskStatus.RUNNING || event.status == TaskStatus.FAILED) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusIcon(event.status)
            if (canExpand) {
                Spacer(Modifier.width(3.dp))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        AnimatedVisibility(visible = expanded && canExpand) {
            Text(
                text = event.detailLog.orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(start = 70.dp, end = 10.dp, bottom = 5.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActivityCategoryIcon(category: UnifiedTraceCategory, modifier: Modifier) {
    val icon = when (category) {
        UnifiedTraceCategory.FILE -> Icons.Outlined.Description
        UnifiedTraceCategory.COMMAND -> Icons.Outlined.Terminal
        UnifiedTraceCategory.TOOL -> Icons.Outlined.Build
        UnifiedTraceCategory.SEARCH -> Icons.Outlined.Search
        UnifiedTraceCategory.MEMORY -> Icons.Outlined.Memory
        UnifiedTraceCategory.CONNECTOR -> Icons.Outlined.Language
        UnifiedTraceCategory.PLAN, UnifiedTraceCategory.TASK -> Icons.Outlined.Folder
        UnifiedTraceCategory.SYSTEM -> Icons.Outlined.TaskAlt
    }
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f), modifier = modifier)
}

@Composable
private fun StatusIcon(status: TaskStatus) {
    when (status) {
        TaskStatus.PENDING -> Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), modifier = Modifier.size(14.dp))
        TaskStatus.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        TaskStatus.COMPLETED -> Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.78f), modifier = Modifier.size(14.dp))
        TaskStatus.FAILED -> Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
    }
}
