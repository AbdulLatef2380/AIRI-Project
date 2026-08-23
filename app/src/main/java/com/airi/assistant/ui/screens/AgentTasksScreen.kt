package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import kotlinx.coroutines.launch
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
import com.airi.assistant.agent.durable.ApprovalGrantScope
import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskStatus
import com.airi.assistant.agent.durable.TaskApproval
import com.airi.assistant.agent.durable.TaskTimelineEvent
import com.airi.assistant.agent.scheduler.ManualRunRequestResult
import com.airi.assistant.agent.scheduler.ScheduledJob
import com.airi.assistant.agent.scheduler.ScheduledJobOrchestrator
import com.airi.assistant.agent.scheduler.ScheduleType
import com.airi.assistant.agent.scheduler.ScheduledJobOutcome
import com.airi.assistant.R
import com.airi.assistant.core.ServiceLocator
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
 *  - [ScheduledJobOrchestrator.cancel] cancels the WorkManager job.
 *  - [ScheduledAgentWorker] routes the saved payload to a registered
 *    sub-agent or the production orchestrator and records the outcome.
 *
 * The editor currently accepts a delay in minutes. Calendar-style recurring
 * expressions require a dedicated scheduling contract before they are exposed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTasksScreen(
    onBack: () -> Unit,
    onNavigateToAgentControl: () -> Unit = {}
) {
    val context = LocalContext.current
    val approvalResumeScope = rememberCoroutineScope()
    val orchestrator = remember { ServiceLocator.scheduledJobOrchestrator }
    val durableTaskManager = remember { ServiceLocator.durableTaskManager }
    val activeWorkStopController = remember { ServiceLocator.activeWorkStopController }
    val permissionGovernance = remember { ServiceLocator.permissionGovernanceLayer }
    val durableTasks by durableTaskManager.tasks.collectAsState()
    val liveApprovals by permissionGovernance.pendingApprovals.collectAsState()
    val taskApprovals = remember(durableTasks) {
        durableTasks.flatMap { task ->
            task.approvals.map { approval -> task to approval }
        }.filter { (_, approval) -> approval.status.name == "PENDING" }
    }

    val taskApprovalIds = remember(taskApprovals) { taskApprovals.mapTo(mutableSetOf()) { (_, approval) -> approval.id } }
    val unboundLiveApprovals = remember(liveApprovals, taskApprovalIds) {
        liveApprovals.filterNot { it.id in taskApprovalIds }
    }
    val trustRequestCount = taskApprovals.size + unboundLiveApprovals.size

    var selectedTab    by remember { mutableStateOf(0) }
    var showAddDialog  by remember { mutableStateOf(false) }
    var showStopConfirmation by remember { mutableStateOf(false) }
    var focusedExecutionId by remember { mutableStateOf<String?>(null) }
    var runNowCandidate by remember { mutableStateOf<ScheduledJob?>(null) }
    var jobs           by remember { mutableStateOf(orchestrator.listJobs()) }
    var errorMessage   by remember { mutableStateOf<String?>(null) }
    var calendarReviewCandidate by remember {
        mutableStateOf<Pair<String, Pair<ApprovalGrantScope, com.airi.assistant.agent.calendar.CalendarCreateRuntime.PrivateReview>>?>(null)
    }

    fun reload() { jobs = orchestrator.listJobs() }

    fun approveAndResume(approvalId: String, approvalScope: ApprovalGrantScope) {
        if (!permissionGovernance.approveAction(approvalId, approvalScope)) {
            ServiceLocator.calendarCreateRuntime.reconcileExpiredApprovals()
            ServiceLocator.calendarCreateRuntime.reconcileApproval(approvalId)
            return
        }
        approvalResumeScope.launch {
            val connectorResult = ServiceLocator.approvalContinuationRuntime.resume(approvalId)
            if (connectorResult == null) {
                val fileResult = ServiceLocator.projectFileEditRuntime.resume(approvalId)
                if (fileResult == null) {
                    ServiceLocator.calendarCreateRuntime.resume(approvalId)
                }
            }
        }
    }

    fun denyAndReconcile(approvalId: String) {
        if (!permissionGovernance.denyAction(approvalId)) return
        ServiceLocator.projectFileEditRuntime.reconcileApproval(approvalId)
        ServiceLocator.calendarCreateRuntime.reconcileApproval(approvalId)
    }

    // Keep completed and failed persisted jobs visible as execution evidence.
    // A user refreshes explicitly rather than the screen polling WorkManager.
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0) reload()
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
                    IconButton(onClick = { reload() }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.scheduled_task_refresh),
                            tint = AiriTheme.onBackground
                        )
                    }
                    IconButton(onClick = { showStopConfirmation = true }) {
                        Icon(
                            Icons.Outlined.Cancel,
                            contentDescription = stringResource(R.string.active_work_stop_title),
                            tint = Color(0xFFFF6B6B)
                        )
                    }
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
                TaskTab(label = stringResource(R.string.agent_task_tab_scheduled, jobs.size), isSelected = selectedTab == 0,
                    modifier = Modifier.weight(1f)) { selectedTab = 0 }
                TaskTab(label = stringResource(R.string.execution_center_tab_runs, durableTasks.size), isSelected = selectedTab == 1,
                    modifier = Modifier.weight(1f)) {
                        focusedExecutionId = null
                        selectedTab = 1
                    }
                TaskTab(label = stringResource(R.string.trust_center_tab, trustRequestCount), isSelected = selectedTab == 2,
                    modifier = Modifier.weight(1f)) { selectedTab = 2 }
            }

            when (selectedTab) {
                0 -> ScheduledTasksContent(
                    jobs = jobs,
                    onCancel = { jobId ->
                        orchestrator.cancel(jobId)
                        reload()
                    },
                    onOpenExecution = { taskId ->
                        focusedExecutionId = taskId
                        selectedTab = 1
                    },
                    onRunNow = { job -> runNowCandidate = job }
                )
                1 -> DurableExecutionContent(
                    tasks = durableTasks,
                    focusedTaskId = focusedExecutionId,
                    onClearFocus = { focusedExecutionId = null },
                    onCancel = { taskId -> durableTaskManager.cancel(taskId) }
                )
                else -> TrustCenterContent(
                    taskApprovals = taskApprovals,
                    unboundLiveApprovals = unboundLiveApprovals,
                    onDecision = { approvalId, approvalScope, approved ->
                        if (approved) {
                            val calendarReview = ServiceLocator.calendarCreateRuntime
                                .privateReviewForApproval(approvalId)
                            if (calendarReview != null) {
                                calendarReviewCandidate = approvalId to (approvalScope to calendarReview)
                            } else {
                                approveAndResume(approvalId, approvalScope)
                            }
                        } else {
                            denyAndReconcile(approvalId)
                        }
                    }
                )
            }
        }
    }

    calendarReviewCandidate?.let { (approvalId, scopedReview) ->
        CalendarCreateApprovalDialog(
            review = scopedReview.second,
            onDismiss = { calendarReviewCandidate = null },
            onConfirm = {
                calendarReviewCandidate = null
                approveAndResume(approvalId, scopedReview.first)
            }
        )
    }

    if (showStopConfirmation) {
        ActiveWorkStopDialog(
            onDismiss = { showStopConfirmation = false },
            onConfirm = {
                val report = activeWorkStopController.stopActiveUserWork()
                reload()
                errorMessage = context.getString(
                    R.string.active_work_stop_summary,
                    report.cancelledDurableTaskCount,
                    report.cancelledScheduledJobCount,
                    if (report.terminalCommandCancelled) 1 else 0
                )
                showStopConfirmation = false
            }
        )
    }

    runNowCandidate?.let { job ->
        AlertDialog(
            onDismissRequest = { runNowCandidate = null },
            title = { Text(stringResource(R.string.scheduled_task_run_now_title)) },
            text = { Text(stringResource(R.string.scheduled_task_run_now_message, job.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (orchestrator.runNow(job.id)) {
                            ManualRunRequestResult.QUEUED -> {
                                reload()
                                runNowCandidate = null
                            }
                            ManualRunRequestResult.ALREADY_ACTIVE -> {
                                errorMessage = context.getString(R.string.scheduled_task_run_now_active)
                                runNowCandidate = null
                            }
                            ManualRunRequestResult.NOT_FOUND,
                            ManualRunRequestResult.NOT_ALLOWED -> {
                                errorMessage = context.getString(R.string.scheduled_task_run_now_unavailable)
                                runNowCandidate = null
                            }
                        }
                    }
                ) { Text(stringResource(R.string.scheduled_task_run_now)) }
            },
            dismissButton = {
                TextButton(onClick = { runNowCandidate = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
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
private fun CalendarCreateApprovalDialog(
    review: com.airi.assistant.agent.calendar.CalendarCreateRuntime.PrivateReview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val formattedStart = remember(review.startMs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(review.startMs))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calendar_approval_review_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.calendar_approval_review_message))
                Text(review.title, color = AiriTheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.calendar_approval_review_start, formattedStart))
                Text(
                    stringResource(
                        R.string.calendar_approval_review_duration,
                        review.durationMinutes
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.calendar_approval_review_confirm), color = CosmicAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ActiveWorkStopDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.active_work_stop_title)) },
        text = { Text(stringResource(R.string.active_work_stop_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.active_work_stop_confirm), color = Color(0xFFFF6B6B))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ScheduledTasksContent(
    jobs: List<ScheduledJob>,
    onCancel: (String) -> Unit,
    onOpenExecution: (String) -> Unit,
    onRunNow: (ScheduledJob) -> Unit
) {
    if (jobs.isEmpty()) {
        EmptyCenterState(
            icon = Icons.Outlined.Schedule,
            message = stringResource(R.string.agent_task_no_scheduled)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp)
    ) {
        items(jobs, key = { it.id }) { job ->
            RealTaskItem(
                job = job,
                onCancel = { onCancel(job.id) },
                onOpenExecution = onOpenExecution,
                onRunNow = if (job.agentId != "system") ({ onRunNow(job) }) else null
            )
        }
    }
}

@Composable
private fun DurableExecutionContent(
    tasks: List<DurableTask>,
    focusedTaskId: String?,
    onClearFocus: () -> Unit,
    onCancel: (String) -> Unit
) {
    val focusedTask = focusedTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
    if (focusedTaskId != null && focusedTask == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.execution_center_linked_run_missing),
                color = AiriTheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = onClearFocus) {
                Text(stringResource(R.string.execution_center_show_all_runs))
            }
        }
        return
    }
    val displayedTasks = focusedTask?.let(::listOf) ?: tasks
    if (displayedTasks.isEmpty()) {
        EmptyCenterState(
            icon = Icons.Outlined.Timeline,
            message = stringResource(R.string.execution_center_no_runs)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp)
    ) {
        if (focusedTask != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.execution_center_linked_run),
                        color = CosmicAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearFocus) {
                        Text(stringResource(R.string.execution_center_show_all_runs), fontSize = 12.sp)
                    }
                }
            }
        }
        items(displayedTasks, key = { it.id }) { task ->
            DurableExecutionCard(task = task, onCancel = { onCancel(task.id) })
        }
    }
}

@Composable
private fun DurableExecutionCard(task: DurableTask, onCancel: () -> Unit) {
    val statusColor = when (task.status) {
        DurableTaskStatus.RUNNING -> CosmicAccent
        DurableTaskStatus.COMPLETED -> Color(0xFF4CAF50)
        DurableTaskStatus.FAILED, DurableTaskStatus.CANCELLED -> Color(0xFFFF6B6B)
        else -> Color(0xFFFFB74D)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AIRIShapes.md,
        color = AiriTheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.22f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Timeline, null, tint = statusColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(task.title, color = AiriTheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(task.status.name.lowercase(), color = statusColor, fontSize = 11.sp)
            }
            if (task.progressMessage.isNotBlank()) {
                Text(task.progressMessage, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (task.progressPercent in 0..100) {
                LinearProgressIndicator(
                    progress = { task.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = statusColor,
                    trackColor = AiriTheme.onSurface.copy(alpha = 0.08f)
                )
            }
            val recent = task.timeline.takeLast(3)
            if (recent.isNotEmpty()) {
                Text(stringResource(R.string.execution_center_replay), color = CosmicAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                recent.forEach { event ->
                    TimelineLine(event)
                }
            }
            if (!task.isTerminal) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel), color = Color(0xFFFF6B6B))
                }
            }
        }
    }
}

@Composable
private fun TimelineLine(event: TaskTimelineEvent) {
    Text(
        text = "${event.type.name.lowercase().replace('_', ' ')} · ${event.summary}",
        color = AiriTheme.onSurfaceVariant.copy(alpha = 0.82f),
        fontSize = 11.sp,
        maxLines = 2
    )
}

@Composable
private fun TrustCenterContent(
    taskApprovals: List<Pair<DurableTask, TaskApproval>>,
    unboundLiveApprovals: List<com.airi.assistant.security.PermissionGovernanceLayer.PendingApproval>,
    onDecision: (String, ApprovalGrantScope, Boolean) -> Unit
) {
    if (taskApprovals.isEmpty() && unboundLiveApprovals.isEmpty()) {
        EmptyCenterState(
            icon = Icons.Outlined.VerifiedUser,
            message = stringResource(R.string.trust_center_no_requests)
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp)
    ) {
        item {
            TrustCenterHeader(requestCount = taskApprovals.size + unboundLiveApprovals.size)
        }
        if (taskApprovals.isNotEmpty()) {
            item { TrustSectionLabel(stringResource(R.string.trust_center_mission_requests)) }
            items(taskApprovals, key = { (_, approval) -> approval.id }) { (task, approval) ->
                TrustRequestCard(
                    title = task.title,
                    action = approval.action,
                    description = approval.description,
                    riskLevel = approval.riskLevel,
                    runId = approval.runId,
                    stepId = approval.stepId,
                    approvalId = approval.id,
                    onDecision = onDecision
                )
            }
        }
        if (unboundLiveApprovals.isNotEmpty()) {
            item { TrustSectionLabel(stringResource(R.string.trust_center_runtime_requests)) }
            items(unboundLiveApprovals, key = { it.id }) { approval ->
                TrustRequestCard(
                    title = stringResource(R.string.trust_center_unscoped_request),
                    action = approval.action,
                    description = approval.description,
                    riskLevel = approval.riskLevel.name,
                    runId = approval.runId,
                    stepId = approval.stepId,
                    approvalId = approval.id,
                    onDecision = onDecision
                )
            }
        }
    }
}

@Composable
private fun TrustCenterHeader(requestCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AIRIShapes.md,
        color = AiriTheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, CosmicAccent.copy(alpha = 0.28f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.trust_center_title), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.trust_center_summary, requestCount),
                color = AiriTheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun TrustSectionLabel(text: String) {
    Text(
        text = text,
        color = CosmicAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun TrustRequestCard(
    title: String,
    action: String,
    description: String,
    riskLevel: String,
    runId: String?,
    stepId: String?,
    approvalId: String,
    onDecision: (String, ApprovalGrantScope, Boolean) -> Unit
) {
    val riskColor = when (riskLevel.uppercase()) {
        "CRITICAL" -> Color(0xFFFF5252)
        "HIGH" -> Color(0xFFFF8A65)
        "MEDIUM" -> Color(0xFFFFB74D)
        else -> Color(0xFF81C784)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AIRIShapes.md,
        color = AiriTheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, riskColor.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = AiriTheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.trust_center_action, action),
                color = AiriTheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(description, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
            Text(
                stringResource(R.string.execution_center_approval_risk, riskLevel.lowercase()),
                color = riskColor,
                fontSize = 11.sp
            )
            if (runId != null || stepId != null) {
                Text(
                    stringResource(R.string.trust_center_execution_scope, runId ?: "—", stepId ?: "—"),
                    color = AiriTheme.onSurfaceVariant.copy(alpha = 0.82f),
                    fontSize = 11.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onDecision(approvalId, ApprovalGrantScope.ONCE, true) }) {
                    Text(stringResource(R.string.execution_center_approve_once), color = CosmicAccent)
                }
                TextButton(onClick = { onDecision(approvalId, ApprovalGrantScope.TASK, true) }) {
                    Text(stringResource(R.string.execution_center_approve_task), color = CosmicAccent)
                }
                TextButton(onClick = { onDecision(approvalId, ApprovalGrantScope.ONCE, false) }) {
                    Text(stringResource(R.string.execution_center_deny), color = Color(0xFFFF6B6B))
                }
            }
        }
    }
}

@Composable
private fun EmptyCenterState(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = AiriTheme.onBackground.copy(0.25f), modifier = Modifier.size(52.dp))
            Text(message, color = AiriTheme.onBackground.copy(0.35f), fontSize = 15.sp, textAlign = TextAlign.Center)
        }
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
private fun RealTaskItem(
    job: ScheduledJob,
    onCancel: () -> Unit,
    onOpenExecution: (String) -> Unit,
    onRunNow: (() -> Unit)?
) {
    val triggerDate = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(job.triggerAtMs))
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
            if (job.manualRunRequestId != null) {
                Text(
                    stringResource(R.string.scheduled_task_run_now_queued),
                    color = CosmicAccent,
                    fontSize = 11.sp
                )
            }
            if (onRunNow != null) {
                TextButton(
                    onClick = onRunNow,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text(
                        stringResource(R.string.scheduled_task_run_now),
                        color = CosmicAccent,
                        fontSize = 11.sp
                    )
                }
            }
            if (job.lastDurableTaskId != null) {
                TextButton(
                    onClick = { onOpenExecution(job.lastDurableTaskId) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text(
                        stringResource(R.string.scheduled_task_view_execution),
                        color = CosmicAccent,
                        fontSize = 11.sp
                    )
                }
            }
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
