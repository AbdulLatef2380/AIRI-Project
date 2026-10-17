package com.airi.assistant.ui.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

/**
 * Displays the lifecycle of agent steps inline with the conversation.
 *
 * The caller owns [tasks] and should publish a new immutable list when a runtime event arrives.
 * Compose observes that new value and recomposes the visible rows without polling.
 */
@Composable
fun AgentLifecycleTrackerPanel(
    tasks: List<AgentTask>,
    modifier: Modifier = Modifier,
) {
    AgentLifecycleTrackerPanelContent(
        tasks = tasks,
        isWorking = tasks.any { it.status == TaskStatus.RUNNING },
        modifier = modifier,
    )
}

/** Internal overload used by the chat trace when the runtime is active before its first event. */
@Composable
internal fun AgentLifecycleTrackerPanel(
    tasks: List<AgentTask>,
    isWorking: Boolean,
    modifier: Modifier = Modifier,
) {
    AgentLifecycleTrackerPanelContent(
        tasks = tasks,
        isWorking = isWorking,
        modifier = modifier,
    )
}

@Composable
private fun AgentLifecycleTrackerPanelContent(
    tasks: List<AgentTask>,
    isWorking: Boolean,
    modifier: Modifier,
) {
    val hasRunningTask = isWorking || tasks.any { it.status == TaskStatus.RUNNING }
    val hasFailedTask = tasks.any { it.status == TaskStatus.FAILED }
    val allTasksCompleted = tasks.isNotEmpty() && tasks.all { it.status == TaskStatus.COMPLETED }
    val headerText = when {
        hasRunningTask -> stringResource(R.string.agent_lifecycle_working)
        hasFailedTask -> stringResource(R.string.agent_lifecycle_failed)
        allTasksCompleted -> stringResource(R.string.agent_lifecycle_completed)
        else -> stringResource(R.string.activity_feed_title)
    }

    // Remember expanded rows across recompositions, including live status updates.
    var expandedTaskIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val expandedCount = tasks.count { it.id in expandedTaskIds && !it.detailLog.isNullOrBlank() }
    val listState = rememberLazyListState()
    val lastTaskKey = tasks.lastOrNull()?.let { it.id to it.status }

    // Follow new events only while the reader is already near the bottom. This keeps the latest
    // action visible during normal streaming without pulling the list away from someone reading
    // earlier details.
    LaunchedEffect(lastTaskKey) {
        if (tasks.isNotEmpty()) {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (totalItems == 0 || lastVisible >= totalItems - 2) {
                listState.animateScrollToItem(tasks.lastIndex)
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackerHeaderIndicator(
                    running = hasRunningTask,
                    failed = hasFailedTask,
                    completed = allTasksCompleted,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = headerText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (tasks.isEmpty()) {
                Text(
                    text = stringResource(R.string.agent_lifecycle_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                // LazyColumn virtualizes longer traces and keeps them scrollable within the chat.
                // Its viewport grows with the number of steps/details and is capped on small screens.
                val desiredHeightDp = (tasks.size * 60 + expandedCount * 112)
                    .coerceAtLeast(64)
                    .coerceAtMost(360)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(desiredHeightDp.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = tasks,
                        key = { task -> task.id },
                    ) { task ->
                        val expanded = task.id in expandedTaskIds
                        AgentLifecycleTaskRow(
                            task = task,
                            expanded = expanded,
                            onToggleDetails = {
                                expandedTaskIds = if (expanded) {
                                    expandedTaskIds - task.id
                                } else {
                                    expandedTaskIds + task.id
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackerHeaderIndicator(
    running: Boolean,
    failed: Boolean,
    completed: Boolean,
) {
    when {
        running -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        failed -> Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = stringResource(R.string.agent_lifecycle_status_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        completed -> Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = stringResource(R.string.agent_lifecycle_status_completed),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp),
        )
        else -> Icon(
            imageVector = Icons.Outlined.RadioButtonUnchecked,
            contentDescription = stringResource(R.string.agent_lifecycle_status_pending),
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun AgentLifecycleTaskRow(
    task: AgentTask,
    expanded: Boolean,
    onToggleDetails: () -> Unit,
) {
    val canExpand = !task.detailLog.isNullOrBlank()
    val titleWeight = if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.FAILED) {
        FontWeight.SemiBold
    } else {
        FontWeight.Normal
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (canExpand) {
                            Modifier.clickable(role = Role.Button, onClick = onToggleDetails)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TaskStatusIndicator(task.status)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = task.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = titleWeight,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canExpand) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.agent_lifecycle_hide_details
                            else R.string.agent_lifecycle_show_details,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            AnimatedVisibility(visible = expanded && canExpand) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 42.dp, end = 12.dp, bottom = 12.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ) {
                    SelectionContainer {
                        Box(
                            modifier = Modifier
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            Text(
                                text = task.detailLog.orEmpty(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskStatusIndicator(status: TaskStatus) {
    when (status) {
        TaskStatus.PENDING -> Icon(
            imageVector = Icons.Outlined.RadioButtonUnchecked,
            contentDescription = stringResource(R.string.agent_lifecycle_status_pending),
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
        TaskStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        TaskStatus.COMPLETED -> Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = stringResource(R.string.agent_lifecycle_status_completed),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp),
        )
        TaskStatus.FAILED -> Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = stringResource(R.string.agent_lifecycle_status_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
    }
}
