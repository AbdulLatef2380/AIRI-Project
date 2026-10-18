package com.airi.assistant.ui.activity

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
 * Displays a compact, borderless live timeline inside the assistant turn.
 * The conversation owns the visual surface; this component only renders the event rows.
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

/** Internal overload used by the chat trace while the runtime is active before its first event. */
@Composable
internal fun AgentLifecycleTrackerPanel(
    tasks: List<AgentTask>,
    isWorking: Boolean,
    modifier: Modifier = Modifier,
) {
    AgentLifecycleTrackerPanelContent(tasks, isWorking, modifier)
}

@Composable
private fun AgentLifecycleTrackerPanelContent(
    tasks: List<AgentTask>,
    isWorking: Boolean,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    var expandedTaskIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val expandedCount = tasks.count { it.id in expandedTaskIds && !it.detailLog.isNullOrBlank() }
    val lastTaskKey = tasks.lastOrNull()?.let { it.id to it.status }

    LaunchedEffect(lastTaskKey) {
        if (tasks.isNotEmpty()) {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (totalItems == 0 || lastVisible >= totalItems - 2) {
                listState.animateScrollToItem(tasks.lastIndex)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (tasks.isEmpty()) {
            if (isWorking) {
                Text(
                    text = stringResource(R.string.agent_lifecycle_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        } else {
            val desiredHeightDp = (tasks.size * 38 + expandedCount * 68)
                .coerceAtLeast(56)
                .coerceAtMost(220)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(desiredHeightDp.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    val expanded = task.id in expandedTaskIds
                    AgentLifecycleTaskRow(
                        task = task,
                        expanded = expanded,
                        onToggleDetails = {
                            expandedTaskIds = if (expanded) expandedTaskIds - task.id
                            else expandedTaskIds + task.id
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentLifecycleTaskRow(
    task: AgentTask,
    expanded: Boolean,
    onToggleDetails: () -> Unit,
) {
    val canExpand = !task.detailLog.isNullOrBlank()
    val textColor = when (task.status) {
        TaskStatus.RUNNING -> MaterialTheme.colorScheme.onSurface
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        TaskStatus.PENDING,
        TaskStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (canExpand) Modifier.clickable(role = Role.Button, onClick = onToggleDetails) else Modifier)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskStatusIndicator(task.status)
            Spacer(Modifier.width(8.dp))
            Text(
                text = task.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.FAILED) FontWeight.Medium else FontWeight.Normal,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canExpand) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.agent_lifecycle_hide_details
                        else R.string.agent_lifecycle_show_details,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded && canExpand) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 30.dp, end = 6.dp, bottom = 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
            ) {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = task.detailLog.orEmpty(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
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
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
            modifier = Modifier.size(15.dp),
        )
        TaskStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(15.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        TaskStatus.COMPLETED -> Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = stringResource(R.string.agent_lifecycle_status_completed),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
            modifier = Modifier.size(15.dp),
        )
        TaskStatus.FAILED -> Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = stringResource(R.string.agent_lifecycle_status_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
    }
}
