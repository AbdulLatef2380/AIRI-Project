package com.airi.assistant.ui.screens

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

    // B-18: SCHEDULE_EXACT_ALARM — required on API 31+ for exact WorkManager timing.
    val canScheduleExact = remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager)
                    .canScheduleExactAlarms()
            } else true
        )
    }

    var selectedTab    by remember { mutableStateOf(0) }
    var showAddDialog  by remember { mutableStateOf(false) }
    var jobs           by remember { mutableStateOf(orchestrator.listJobs()) }
    var errorMessage   by remember { mutableStateOf<String?>(null) }

    fun reload() { jobs = orchestrator.listJobs() }

    val now = remember { System.currentTimeMillis() }
    val pending   = jobs.filter { it.triggerAtMs > now || it.type == ScheduleType.PERIODIC }
    val completed = jobs.filter { it.triggerAtMs <= now && it.type == ScheduleType.ONE_TIME }

    Scaffold(
        containerColor = AiriTheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AiriTheme.background.copy(alpha = 0.92f)
                ),
                navigationIcon = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add task", tint = CosmicAccent)
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AiriTheme.onBackground)
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
            // ── Error banner ───────────────────────────────────────────────────
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

            // ── Tab row ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
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
                    // B-18: Warn if SCHEDULE_EXACT_ALARM not granted (API 31+)
                    if (!canScheduleExact.value && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF2A2010),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFFFD60A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.exact_alarms_title), fontSize = 12.sp,
                                            color = Color(0xFFFFD60A), fontWeight = FontWeight.SemiBold)
                                        Text(stringResource(R.string.exact_alarms_body),
                                            fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = {
                                        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                            android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        } else null
                                        intent?.let { context.startActivity(it) }
                                        canScheduleExact.value = (context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
                                    }) { Text(stringResource(R.string.fix), color = Color(0xFF0A84FF), fontSize = 12.sp) }
                                }
                            }
                        }
                    }

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
            onAdd     = { label, delayMinutes ->
                if (label.isBlank()) {
                    errorMessage = context.getString(R.string.agent_task_name_required)
                    return@AddTaskDialog
                }
                runCatching {
                    orchestrator.scheduleOnce(
                        agentId     = "productivity",
                        payload     = label,
                        label       = label,
                        delayMs     = delayMinutes * 60_000L,
                        requiresNet = false
                    )
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

// ── Composables ────────────────────────────────────────────────────────────────

@Composable
private fun TaskTab(label: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) CosmicAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = label,
            color      = if (isSelected) Color.White else Color.White.copy(0.50f),
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
    val isPast = job.triggerAtMs <= System.currentTimeMillis() && job.type == ScheduleType.ONE_TIME

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AiriTheme.surface)
            .border(
                1.dp,
                if (!isPast) CosmicAccent.copy(0.15f) else Color.White.copy(0.06f),
                RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Cancel button (visible only for pending jobs)
        if (!isPast) {
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
            }
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(0.25f))
            )
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
            Text(job.label, color = AiriTheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(triggerDate, color = CosmicAccent.copy(0.8f), fontSize = 12.sp)
            Text(typeLabel, color = AiriTheme.onBackground.copy(0.45f), fontSize = 11.sp)
        }

        Spacer(Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CosmicAccent.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (!isPast) Icons.Outlined.Schedule else Icons.Outlined.CheckCircle,
                null,
                tint = if (!isPast) CosmicAccent else Color(0xFF4CAF50),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskDialog(onDismiss: () -> Unit, onAdd: (label: String, delayMinutes: Long) -> Unit) {
    var taskName    by remember { mutableStateOf("") }
    var delayInput  by remember { mutableStateOf("60") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor    = Color(0xFF131728),
        titleContentColor = Color.White,
        textContentColor  = Color.White,
        shape = RoundedCornerShape(20.dp),
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
                        unfocusedBorderColor = Color.White.copy(0.15f),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White
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
                        unfocusedBorderColor = Color.White.copy(0.15f),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White,
                        focusedLabelColor    = CosmicAccent
                    ),
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End)
                )
                Text(
                    stringResource(R.string.agent_task_delay_note),
                    color = AiriTheme.onBackground.copy(0.3f), fontSize = 11.sp, lineHeight = 15.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val delay = delayInput.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
                    onAdd(taskName.trim(), delay)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = AiriTheme.onBackground),
                shape  = RoundedCornerShape(12.dp),
                enabled = taskName.isNotBlank()
            ) { Text(stringResource(R.string.schedule_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = AiriTheme.onBackground.copy(0.6f)) }
        }
    )
}
