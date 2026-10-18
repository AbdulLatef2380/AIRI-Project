package com.airi.assistant.ui.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
 * Presents the agent's real action/observation stream as a quiet part of the assistant turn.
 *
 * The component deliberately has no outer Card: Manus-style task progress is a compact
 * conversation affordance, not a developer console. The caller owns the immutable task list.
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
    AgentLifecycleTrackerPanelContent(tasks, isWorking, modifier)
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
    val completedCount = tasks.count { it.status == TaskStatus.COMPLETED }
    val summaryText = if (tasks.isEmpty()) {
        headerText
    } else {
        "$headerText · $completedCount/${tasks.size}"
    }

    // While an execution is live, show the stream. Once it is finished, retain the stream in
    // the response but allow the user to collapse it, avoiding a full-screen history by default.
    var expanded by rememberSaveable { mutableStateOf(isWorking) }
    val listScroll = rememberScrollState()
    val lastTaskKey = tasks.lastOrNull()?.let { it.id to it.status }

    LaunchedEffect(lastTaskKey, expanded) {
        if (expanded && tasks.isNotEmpty()) {
            listScroll.animateScrollTo(listScroll.maxValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackerHeaderIndicator(
                running = hasRunningTask,
                failed = hasFailedTask,
                completed = allTasksCompleted,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = summaryText,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (hasRunningTask) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.agent_lifecycle_hide_details
                    else R.string.agent_lifecycle_show_details,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            if (tasks.isEmpty()) {
                Text(
                    text = stringResource(R.string.agent_lifecycle_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 30.dp, top = 2.dp, bottom = 4.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(listScroll)
                        .padding(start = 30.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    tasks.forEach { task ->
                        AgentLifecycleTaskRow(task)
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
            modifier = Modifier.size(16.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        failed -> Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = stringResource(R.string.agent_lifecycle_status_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        completed -> Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = stringResource(R.string.agent_lifecycle_status_completed),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
        )
        else -> Icon(
            imageVector = Icons.Outlined.RadioButtonUnchecked,
            contentDescription = stringResource(R.string.agent_lifecycle_status_pending),
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun AgentLifecycleTaskRow(task: AgentTask) {
    val canExpand = !task.detailLog.isNullOrBlank()
    var detailsExpanded by rememberSaveable(task.id) { mutableStateOf(false) }
    val titleWeight = if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.FAILED) {
        FontWeight.Medium
    } else {
        FontWeight.Normal
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (canExpand) {
                        Modifier.clickable(role = Role.Button) { detailsExpanded = !detailsExpanded }
                    } else Modifier,
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskStatusIndicator(task.status)
            Spacer(Modifier.width(8.dp))
            Text(
                text = task.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = titleWeight,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (canExpand) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (detailsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(
                        if (detailsExpanded) R.string.agent_lifecycle_hide_details
                        else R.string.agent_lifecycle_show_details,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        AnimatedVisibility(visible = detailsExpanded && canExpand) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 26.dp, end = 4.dp, bottom = 4.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                SelectionContainer {
                    Text(
                        text = task.detailLog.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .heightIn(max = 140.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
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
            modifier = Modifier.size(16.dp),
        )
        TaskStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(15.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        TaskStatus.COMPLETED -> Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = stringResource(R.string.agent_lifecycle_status_completed),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(16.dp),
        )
        TaskStatus.FAILED -> Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = stringResource(R.string.agent_lifecycle_status_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
    }
}
