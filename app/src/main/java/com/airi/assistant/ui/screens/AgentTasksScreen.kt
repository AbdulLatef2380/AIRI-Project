package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.agent.scheduler.ScheduledJob
import com.airi.assistant.agent.scheduler.ScheduledJobOrchestrator
import com.airi.assistant.agent.scheduler.ScheduleType
import com.airi.assistant.agent.scheduler.ScheduledJobOutcome
import com.airi.assistant.R
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicBlack
import com.airi.assistant.ui.theme.SurfaceCard
import java.text.DateFormat
import java.util.Date

/**
 * Scheduled tasks screen — wired to the real [ScheduledJobOrchestrator].
 *
 * WHAT IS REAL:
 *  - [ScheduledJobOrchestrator.listJobs] reads from SharedPreferences
 *    (JSON) — survives app restarts.
 *  - [ScheduledJobOrchestrator.scheduleOnce] enqueues a WorkManager
 *    OneTimeWorkRequest with a real delay.
 *  - [ScheduledJobOrchestrator.cancelJob] cancels the WorkManager job.
 *
 * WHAT IS STILL LIMITED:
 *  - Natural-language schedule parsing (e.g. "daily at 9am") is not yet
 *    implemented. Users enter a delay in minutes for now.
 *  - [ScheduledAgentWorker.doWork] posts to EventBus but does not yet
 *    call the full SubAgentRegistry dispatch. That is a backend wiring
 *    gap, not a UI gap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTasksScreen(
    onBack: () -> Unit,
    onNavigateToAgentControl: () -> Unit = {}
) {
    val context = LocalContext.current
    val orchestrator = remember { ScheduledJobOrchestrator(context) }

    var selectedTab    by remember { mutableStateOf(0) }
    var showAddDialog  by remember { mutableStateOf(false) }
    var jobs           by remember { mutableStateOf(orchestrator.listJobs()) }
    var errorMessage   by remember { mutableStateOf<String?>(null) }

    fun reload() { jobs = orchestrator.listJobs() }

    val pending = jobs.filter {
        it.type == ScheduleType.PERIODIC ||
            it.lastOutcome == ScheduledJobOutcome.PENDING ||
            it.lastOutcome == ScheduledJobOutcome.RETRYING
    }
    val completed = jobs.filter {
        it.type == ScheduleType.ONE_TIME &&
            (it.lastOutcome == ScheduledJobOutcome.COMPLETED || it.lastOutcome == ScheduledJobOutcome.FAILED)
    }

    Scaffold(
        containerColor = AiriTheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AiriTheme.background.copy(alpha = 0.92f)
                ),
                navigationIcon = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_task), tint = CosmicAccent)
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.agent_tasks),
                        color = AiriTheme.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            errorMessage?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x22FF4444))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Warning, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp))
                    Text(msg, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { errorMessage = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Close, null, tint = Color(0xFFFF6B6B))
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(AIRIShapes.md)
                    .background(AiriTheme.surface),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TaskTab(label = stringResource(R.string.agent_task_tab_scheduled, pending.size), isSelected = selectedTab == 0,
                    modifier = Modifier.weight(1f)) { selectedTab = 0 }
                TaskTab(label = stringResource(R.string.agent_task_tab_completed, completed.size), isSelected = selectedTab == 1,
                    modifier = Modifier.weight(1f)) { selectedTab = 1 }
            }

            val displayJobs = if (selectedTab == 0) pending else completed

            if (displayJobs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Schedule, null, tint = AiriTheme.onBackground.copy(0.25f), modifier = Modifier.size(52.dp))
                        Text(
                            if (selectedTab == 0) stringResource(R.string.agent_task_no_scheduled)
                            else stringResource(R.string.agent_task_no_completed),
                            color = AiriTheme.onBackground.copy(0.35f),
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp)
                ) {
                    items(displayJobs, key = { it.id }) { job ->
                        RealTaskItem(
                            job      = job,
                            onCancel = {
                                orchestrator.cancel(job.id)
                                reload()
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddTaskDialog(
            onDismiss = { showAddDialog = false },
            onAdd     = { label, delayMinutes, isPeriodic, requiresNetwork ->
                if (label.isBlank()) {
                    errorMessage = context.getString(R.string.agent_task_name_required)
                    return@AddTaskDialog
                }
                runCatching {
                    if (isPeriodic) {
                        orchestrator.schedulePeriodic(
                            agentId = "productivity",
                            payload = label,
                            label = label,
                            intervalMinutes = delayMinutes,
                            requiresNet = requiresNetwork
                        )
                    } else {
                        orchestrator.scheduleOnce(
                            agentId = "productivity",
                            payload = label,
                            label = label,
                            delayMs = delayMinutes * 60_000L,
                            requiresNet = requiresNetwork
                        )
                    }
                }.onSuccess {
                    reload()
                    showAddDialog = false
                }.onFailure {
                    errorMessage = context.getString(R.string.agent_task_schedule_failed, it.message ?: "")
                }
            }
        )
    }
}
@Composable
private fun TaskTab(label: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .clip(AIRIShapes.sm)
            .background(if (isSelected) CosmicAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = label,
            color      = if (isSelected) AiriTheme.onSurface else AiriTheme.onSurface.copy(0.50f),
            fontSize   = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun RealTaskItem(job: ScheduledJob, onCancel: () -> Unit) {
    val triggerDate = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(job.triggerAtMs))
    val context = androidx.compose.ui.platform.LocalContext.current
    val typeLabel = when (job.type) {
        ScheduleType.ONE_TIME -> stringResource(R.string.agent_task_type_once)
        ScheduleType.PERIODIC -> stringResource(R.string.agent_task_type_periodic, (job.intervalMs ?: 0) / 60_000)
    }
    val isCancellable = job.type == ScheduleType.PERIODIC ||
        job.lastOutcome == ScheduledJobOutcome.PENDING ||
        job.lastOutcome == ScheduledJobOutcome.RETRYING
    val statusLabel = when (job.lastOutcome) {
        ScheduledJobOutcome.PENDING -> stringResource(R.string.agent_task_status_pending)
        ScheduledJobOutcome.RETRYING -> stringResource(R.string.agent_task_status_retrying)
        ScheduledJobOutcome.COMPLETED -> stringResource(R.string.agent_task_status_completed)
        ScheduledJobOutcome.FAILED -> stringResource(R.string.agent_task_status_failed)
    }
    val statusIcon = when (job.lastOutcome) {
        ScheduledJobOutcome.PENDING, ScheduledJobOutcome.RETRYING -> Icons.Outlined.Schedule
        ScheduledJobOutcome.COMPLETED -> Icons.Outlined.CheckCircle
        ScheduledJobOutcome.FAILED -> Icons.Outlined.ErrorOutline
    }
    val statusTint = when (job.lastOutcome) {
        ScheduledJobOutcome.PENDING -> CosmicAccent
        ScheduledJobOutcome.RETRYING -> Color(0xFFFFB74D)
        ScheduledJobOutcome.COMPLETED -> Color(0xFF4CAF50)
        ScheduledJobOutcome.FAILED -> Color(0xFFFF6B6B)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AIRIShapes.md)
            .background(AiriTheme.surface)
            .border(
                1.dp,
                if (isCancellable) CosmicAccent.copy(0.15f) else AiriTheme.onSurface.copy(0.06f),
                AIRIShapes.md
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (isCancellable) {
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = Color(0xFFFF6B6B),
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AiriTheme.onSurface.copy(alpha = 0.25f))
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
            Text(job.label, color = AiriTheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(triggerDate, color = CosmicAccent.copy(0.8f), fontSize = 12.sp)
            Text(typeLabel, color = AiriTheme.onBackground.copy(0.45f), fontSize = 11.sp)
            Text(statusLabel, color = statusTint.copy(0.85f), fontSize = 11.sp)
        }

        Spacer(Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(AIRIShapes.sm)
                .background(CosmicAccent.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                statusIcon,
                contentDescription = null,
                tint = statusTint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    onAdd: (label: String, delayMinutes: Long, isPeriodic: Boolean, requiresNetwork: Boolean) -> Unit
) {
    var taskName by remember { mutableStateOf("") }
    var delayInput by remember { mutableStateOf("60") }
    var isPeriodic by remember { mutableStateOf(false) }
    var requiresNetwork by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor    = Color(0xFF131728),
        titleContentColor = AiriTheme.onSurface,
        textContentColor  = AiriTheme.onSurface,
        shape = AIRIShapes.xl,
        title = {
            Text(stringResource(R.string.new_task_title), fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = taskName,
                    onValueChange = { taskName = it },
                    placeholder = { Text(stringResource(R.string.task_name_hint), color = AiriTheme.onBackground.copy(0.35f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = CosmicAccent,
                        unfocusedBorderColor = AiriTheme.onSurface.copy(0.15f),
                        focusedTextColor     = AiriTheme.onSurface,
                        unfocusedTextColor   = AiriTheme.onSurface
                    ),
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End)
                )
                OutlinedTextField(
                    value = delayInput,
                    onValueChange = { if (it.all { c -> c.isDigit() }) delayInput = it },
                    label = { Text(stringResource(R.string.delay_minutes_label), fontSize = 12.sp, color = AiriTheme.onBackground.copy(0.55f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = CosmicAccent,
                        unfocusedBorderColor = AiriTheme.onSurface.copy(0.15f),
                        focusedTextColor     = AiriTheme.onSurface,
                        unfocusedTextColor   = AiriTheme.onSurface,
                        focusedLabelColor    = CosmicAccent
                    ),
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.agent_task_repeat), color = AiriTheme.onSurface, fontSize = 13.sp)
                    Switch(checked = isPeriodic, onCheckedChange = { isPeriodic = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.agent_task_requires_network), color = AiriTheme.onSurface, fontSize = 13.sp)
                    Switch(checked = requiresNetwork, onCheckedChange = { requiresNetwork = it })
                }
                Text(
                    stringResource(if (isPeriodic) R.string.agent_task_periodic_note else R.string.agent_task_delay_note),
                    color = AiriTheme.onBackground.copy(0.3f), fontSize = 11.sp, lineHeight = 15.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val minimumDelay = if (isPeriodic) 15L else 1L
                    val delay = delayInput.toLongOrNull()?.coerceAtLeast(minimumDelay) ?: minimumDelay
                    onAdd(taskName.trim(), delay, isPeriodic, requiresNetwork)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = AiriTheme.onBackground),
                shape  = AIRIShapes.md,
                enabled = taskName.isNotBlank()
            ) { Text(stringResource(R.string.schedule_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = AiriTheme.onBackground.copy(0.6f)) }
        }
    )
}
