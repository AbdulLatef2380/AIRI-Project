package com.airi.assistant.ui.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
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
    // Keep the live surface compact by default. Details are user-invoked so the
    // execution trace supports the answer instead of competing with it.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val executionId = projection.events.firstOrNull()?.executionId.orEmpty()
    var expandedGroups by rememberSaveable(executionId) {
        mutableStateOf<List<String>>(emptyList())
    }
    var expandedEvents by rememberSaveable(executionId) { mutableStateOf<List<String>>(emptyList()) }
    val panelDescription = stringResource(R.string.agent_trace_panel_description)
    val lastEventKey = projection.events.lastOrNull()?.let { it.eventId to it.status }

    LaunchedEffect(lastEventKey, expanded) {
        if (expanded && projection.groups.isNotEmpty() && listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 >= listState.layoutInfo.totalItemsCount - 2) {
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }

    if (projection.events.isEmpty() && !isWorking) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                RoundedCornerShape(12.dp),
            )
            .semantics {
                contentDescription = panelDescription
            },
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        ActivitySummaryRow(
            summary = projection,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            isWorking = isWorking,
        )
        AnimatedVisibility(visible = expanded) {
            if (projection.groups.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agent-trace-list")
                        .heightIn(max = 238.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    projection.groups.forEach { group ->
                        item(key = "group-${group.id}") {
                            ActivityGroupHeader(
                                group = group,
                                expanded = group.id in expandedGroups,
                                onToggle = {
                                    expandedGroups = if (group.id in expandedGroups) {
                                        expandedGroups - group.id
                                    } else {
                                        expandedGroups + group.id
                                    }
                                },
                            )
                        }
                        if (group.id in expandedGroups) {
                            items(group.events, key = { "event-${it.eventId}" }) { event ->
                                ActivityEventRow(
                                    event = event,
                                    depth = 0,
                                    hasChildren = !event.detailLog.isNullOrBlank(),
                                    expanded = event.eventId in expandedEvents,
                                    onToggle = {
                                        if (!event.detailLog.isNullOrBlank()) {
                                            expandedEvents = if (event.eventId in expandedEvents) {
                                                expandedEvents - event.eventId
                                            } else {
                                                expandedEvents + event.eventId
                                            }
                                        }
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
        summary.summary.memoryOperations.takeIf { it > 0 }?.let { add(stringResource(R.string.agent_trace_summary_memory, it)) }
        summary.summary.tasks.takeIf { it > 0 }?.let { add(stringResource(R.string.agent_trace_summary_tasks, it)) }
    }
    val toggleDescription = stringResource(
        if (expanded) R.string.agent_trace_collapse_panel else R.string.agent_trace_expand_panel,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent-trace-summary")
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics {
                contentDescription = toggleDescription
            }
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
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.agent_lifecycle_working),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                items.forEach { item ->
                    Text(
                        text = item,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
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
    val toggleDescription = stringResource(
        if (expanded) R.string.agent_trace_collapse_group else R.string.agent_trace_expand_group,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics {
                contentDescription = "$title · ${group.events.size}. $toggleDescription"
            }
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
    depth: Int = 0,
    hasChildren: Boolean = false,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val canExpand = hasChildren || !event.detailLog.isNullOrBlank()
    val accessibleStatus = statusLabel(event.status)
    val technicalEvent = event.category == UnifiedTraceCategory.TOOL ||
        event.category == UnifiedTraceCategory.CONNECTOR
    val humanTitle = stringResource(
        if (event.category == UnifiedTraceCategory.CONNECTOR) {
            R.string.agent_trace_event_connection_action
        } else {
            R.string.agent_trace_event_tool_action
        },
    )
    val detailToggleDescription = stringResource(
        if (expanded) R.string.agent_trace_collapse_event else R.string.agent_trace_expand_event,
    )
    val color = when (event.status) {
        TaskStatus.RUNNING -> MaterialTheme.colorScheme.onSurface
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        TaskStatus.WAITING, TaskStatus.PENDING, TaskStatus.COMPLETED, TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (canExpand) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
            .then(if (canExpand) Modifier.semantics(mergeDescendants = true) {
                    contentDescription = if (technicalEvent) {
                        "$humanTitle. ${event.title}. $accessibleStatus. $detailToggleDescription"
                    } else {
                        "${event.title}. $accessibleStatus. $detailToggleDescription"
                    }
                } else Modifier.semantics(mergeDescendants = true) {
                    contentDescription = if (technicalEvent) {
                        "$humanTitle. ${event.title}. $accessibleStatus"
                    } else {
                        "${event.title}. $accessibleStatus"
                    }
                })
                .padding(start = (48 + depth * 18).dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActivityCategoryIcon(event.category, Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            if (technicalEvent) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = humanTitle,
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (event.status == TaskStatus.RUNNING || event.status == TaskStatus.FAILED) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${stringResource(R.string.agent_trace_event_technical_name)}: ${event.title}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            textDirection = TextDirection.Ltr,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = event.title,
                    modifier = Modifier.weight(1f),
                    color = color,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (event.status == TaskStatus.RUNNING || event.status == TaskStatus.FAILED) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            Column(
                modifier = Modifier.padding(start = (70 + depth * 18).dp, end = 10.dp, bottom = 5.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                if (!event.detailLog.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.agent_trace_event_details),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = event.detailLog.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
        TaskStatus.PENDING -> Icon(Icons.Outlined.RadioButtonUnchecked, stringResource(R.string.agent_lifecycle_status_pending), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), modifier = Modifier.size(14.dp))
        TaskStatus.WAITING -> Icon(Icons.Outlined.RadioButtonUnchecked, stringResource(R.string.agent_lifecycle_status_waiting), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
        TaskStatus.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        TaskStatus.COMPLETED -> Icon(Icons.Outlined.Check, stringResource(R.string.agent_lifecycle_status_completed), tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.78f), modifier = Modifier.size(14.dp))
        TaskStatus.FAILED -> Icon(Icons.Outlined.ErrorOutline, stringResource(R.string.agent_lifecycle_status_failed), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
        TaskStatus.CANCELLED -> Icon(Icons.Outlined.ErrorOutline, stringResource(R.string.agent_lifecycle_status_cancelled), tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun statusLabel(status: TaskStatus): String = stringResource(
    when (status) {
        TaskStatus.PENDING -> R.string.agent_lifecycle_status_pending
        TaskStatus.WAITING -> R.string.agent_lifecycle_status_waiting
        TaskStatus.RUNNING -> R.string.agent_lifecycle_status_running
        TaskStatus.COMPLETED -> R.string.agent_lifecycle_status_completed
        TaskStatus.FAILED -> R.string.agent_lifecycle_status_failed
        TaskStatus.CANCELLED -> R.string.agent_lifecycle_status_cancelled
    },
)
