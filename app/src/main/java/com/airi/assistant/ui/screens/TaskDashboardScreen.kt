package com.airi.assistant.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentTraceManager
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.core.runtime.PersistentTaskSession
import com.airi.assistant.core.runtime.SessionStatus
import com.airi.assistant.ui.theme.BorderLight
import com.airi.assistant.ui.theme.BorderMid
import com.airi.assistant.ui.theme.PrimaryAccent
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarning
import com.airi.assistant.ui.theme.SecondaryAccent
import com.airi.assistant.ui.theme.Surface0
import com.airi.assistant.ui.theme.Surface1
import com.airi.assistant.ui.theme.Surface2
import com.airi.assistant.ui.theme.TextPrimary
import com.airi.assistant.ui.theme.TextSecondary
import com.airi.assistant.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// TaskDashboardScreen — production-grade ARM session dashboard
//
// Visual hierarchy:
//   1. Header with live running-count badge
//   2. Stat chips row: Running / Paused / Done / Failed counts
//   3. Horizontal filter strip
//   4. Session cards with:
//      - Animated status pill (pulsing dot for RUNNING)
//      - Bold goal text
//      - Agent ID + timing metadata
//      - Animated progress bar with step counter
//      - Result / error banners for terminal sessions
//      - Pause + Cancel actions for active sessions
//   5. Empty state with contextual messaging
//
// AIRI_PROOF log tags preserved:
//   TASK_DASHBOARD_CANCEL_REQUESTED
//   TASK_DASHBOARD_SUSPEND_REQUESTED
// ─────────────────────────────────────────────────────────────────────────────

private enum class DashFilter(val label: String) {
    ALL("All"), RUNNING("Running"), PAUSED("Paused"), DONE("Done"), FAILED("Failed")
}

private val ALL_FILTERS = listOf(
    DashFilter.ALL, DashFilter.RUNNING, DashFilter.PAUSED, DashFilter.DONE, DashFilter.FAILED
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDashboardScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val arm      = remember { ServiceLocator.autonomousRuntimeManager }
    val sessions by arm.sessions.collectAsState()
    var filter   by remember { mutableStateOf(DashFilter.ALL) }

    val active    = sessions.count { it.status == SessionStatus.RUNNING }
    val suspended = sessions.count { it.status == SessionStatus.SUSPENDED }
    val completed = sessions.count { it.status == SessionStatus.COMPLETED }
    val failed    = sessions.count {
        it.status == SessionStatus.FAILED || it.status == SessionStatus.CANCELLED
    }

    val filtered = remember(sessions, filter) {
        when (filter) {
            DashFilter.ALL     -> sessions
            DashFilter.RUNNING -> sessions.filter { it.status == SessionStatus.RUNNING }
            DashFilter.PAUSED  -> sessions.filter { it.status == SessionStatus.SUSPENDED }
            DashFilter.DONE    -> sessions.filter { it.status == SessionStatus.COMPLETED }
            DashFilter.FAILED  -> sessions.filter {
                it.status == SessionStatus.FAILED || it.status == SessionStatus.CANCELLED
            }
        }
    }.sortedByDescending { it.createdAtMs }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text       = "Tasks",
                            fontWeight = FontWeight.Bold,
                            fontSize   = 20.sp,
                            color      = TextPrimary
                        )
                        if (active > 0) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(PrimaryAccent)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text       = "$active",
                                    fontSize   = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color      = Color.White
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = Surface1,
                    titleContentColor = TextPrimary
                ),
                actions = {
                    IconButton(onClick = { onNavigate(AiriRoute.AGENT_CONTROL) }) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = "Agent Control",
                            tint               = TextPrimary.copy(alpha = 0.7f),
                            modifier           = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = { onNavigate(AiriRoute.OBSERVABILITY) }) {
                        Icon(
                            Icons.Outlined.Assessment,
                            contentDescription = "Observability",
                            tint               = TextPrimary.copy(alpha = 0.7f),
                            modifier           = Modifier.size(22.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->

        if (sessions.isEmpty()) {
            // ── Empty state ──────────────────────────────────────────────
            TaskEmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier       = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {

                // ── Stat chips ─────────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TaskStatChip(
                            label  = "Running",
                            count  = active,
                            color  = PrimaryAccent,
                            modifier = Modifier.weight(1f)
                        )
                        TaskStatChip(
                            label  = "Paused",
                            count  = suspended,
                            color  = SemanticWarning,
                            modifier = Modifier.weight(1f)
                        )
                        TaskStatChip(
                            label  = "Done",
                            count  = completed,
                            color  = SemanticSuccess,
                            modifier = Modifier.weight(1f)
                        )
                        TaskStatChip(
                            label  = "Failed",
                            count  = failed,
                            color  = SemanticError,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // ── Filter strip ───────────────────────────────────────
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ALL_FILTERS.forEach { opt ->
                            val isSelected = opt == filter
                            val count = when (opt) {
                                DashFilter.ALL     -> sessions.size
                                DashFilter.RUNNING -> active
                                DashFilter.PAUSED  -> suspended
                                DashFilter.DONE    -> completed
                                DashFilter.FAILED  -> failed
                            }
                            Surface(
                                onClick      = { filter = opt },
                                modifier     = Modifier.height(32.dp),
                                shape        = CircleShape,
                                color        = if (isSelected) PrimaryAccent.copy(alpha = 0.15f) else Surface2,
                                contentColor = if (isSelected) PrimaryAccent else TextSecondary,
                                border       = BorderStroke(
                                    1.dp,
                                    if (isSelected) PrimaryAccent.copy(alpha = 0.45f) else BorderLight
                                )
                            ) {
                                Box(
                                    modifier         = Modifier.padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text       = if (count > 0) "${opt.label} ($count)" else opt.label,
                                        fontSize   = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(10.dp)) }

                // ── Filter-empty sub-state ─────────────────────────────
                if (filtered.isEmpty()) {
                    item {
                        Box(
                            modifier         = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text  = "No ${filter.label.lowercase()} tasks",
                                color = TextTertiary,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    // ── Session cards ──────────────────────────────────
                    items(filtered, key = { it.sessionId }) { session ->
                        TaskSessionCard(
                            session   = session,
                            onCancel  = {
                                Log.i("AIRI_PROOF",
                                    "TASK_DASHBOARD_CANCEL_REQUESTED sessionId=${session.sessionId}")
                                arm.cancelSession(session.sessionId)
                            },
                            onSuspend = {
                                Log.i("AIRI_PROOF",
                                    "TASK_DASHBOARD_SUSPEND_REQUESTED sessionId=${session.sessionId}")
                                arm.suspendSession(session.sessionId)
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stat Chip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TaskStatChip(
    label:    String,
    count:    Int,
    color:    Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.09f))
            .border(1.dp, color.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text       = count.toString(),
            fontSize   = 22.sp,
            fontWeight = FontWeight.Bold,
            color      = if (count > 0) color else color.copy(alpha = 0.30f)
        )
        Text(
            text       = label,
            fontSize   = 10.sp,
            color      = TextTertiary,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty State
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TaskEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier            = Modifier.padding(horizontal = 36.dp)
        ) {
            Icon(
                imageVector        = Icons.Outlined.Assignment,
                contentDescription = null,
                tint               = TextTertiary.copy(alpha = 0.35f),
                modifier           = Modifier.size(60.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text       = "No autonomous tasks yet",
                color      = TextSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 17.sp
            )
            Text(
                text      = "Task sessions appear here when AIRI executes autonomous multi-step goals. Ask AIRI to perform a complex, long-running task to get started.",
                color     = TextTertiary,
                fontSize  = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Session Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TaskSessionCard(
    session:   PersistentTaskSession,
    onCancel:  () -> Unit,
    onSuspend: () -> Unit,
    modifier:  Modifier = Modifier
) {
    val statusColor = taskStatusColor(session.status)
    val statusLabel = taskStatusLabel(session.status)
    val statusIcon  = taskStatusIcon(session.status)

    // Step timeline state
    val traceManager = remember { AgentTraceManager.instance }
    val traces by traceManager.traces.collectAsState()
    val matchedSteps = remember(traces, session.sessionId) {
        traces.firstOrNull { it.originalInput.trim() == session.goalText.trim() }?.steps
            ?: emptyList()
    }
    var showSteps by remember { mutableStateOf(false) }

    // Animated progress fill
    val progressAnim by animateFloatAsState(
        targetValue   = (session.progressPercent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label         = "sessionProgress"
    )

    // Running-state pulsing dot
    val infinite = rememberInfiniteTransition(label = "runPulse")
    val dotPulse by infinite.animateFloat(
        initialValue  = 0.45f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotPulse"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        color    = Surface1,
        border   = BorderStroke(
            width = 1.dp,
            color = if (session.status == SessionStatus.RUNNING)
                statusColor.copy(alpha = 0.40f)
            else BorderLight
        )
    ) {
        Column(
            modifier            = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {

            // ── Header: status pill ↔ session ID + timing ─────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Status pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(statusColor.copy(alpha = 0.11f))
                        .border(1.dp, statusColor.copy(alpha = 0.32f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (session.status == SessionStatus.RUNNING) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor.copy(alpha = dotPulse))
                        )
                    } else {
                        Icon(
                            imageVector        = statusIcon,
                            contentDescription = null,
                            tint               = statusColor,
                            modifier           = Modifier.size(11.dp)
                        )
                    }
                    Text(
                        text          = statusLabel,
                        fontSize      = 10.sp,
                        fontWeight    = FontWeight.Bold,
                        color         = statusColor,
                        letterSpacing = 0.7.sp
                    )
                }

                // Session ID + duration
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text          = session.sessionId.take(8).uppercase(Locale.getDefault()),
                        fontSize      = 9.sp,
                        color         = TextTertiary,
                        fontFamily    = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text       = if (session.isTerminal && session.finishedAtMs > 0)
                            formatDuration(session.finishedAtMs - session.createdAtMs)
                        else
                            formatTime(session.createdAtMs),
                        fontSize   = 9.sp,
                        color      = TextTertiary.copy(alpha = 0.65f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // ── Goal text ─────────────────────────────────────────────
            Text(
                text       = session.goalText,
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color      = TextPrimary,
                maxLines   = 3,
                overflow   = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )

            // ── Agent meta ────────────────────────────────────────────
            Text(
                text       = "agent · ${session.agentId}",
                fontSize   = 10.sp,
                color      = TextTertiary,
                fontFamily = FontFamily.Monospace
            )

            // ── Progress bar ──────────────────────────────────────────
            if (!session.isTerminal || session.totalSteps > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text(
                                text       = "Step ${session.stepIndex}",
                                fontSize   = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color      = statusColor.copy(alpha = 0.9f)
                            )
                            if (session.totalSteps > 0) {
                                Text(
                                    text     = "/ ${session.totalSteps}",
                                    fontSize = 11.sp,
                                    color    = TextTertiary
                                )
                            }
                        }
                        Text(
                            text          = "${session.progressPercent}%",
                            fontSize      = 11.sp,
                            fontWeight    = FontWeight.SemiBold,
                            color         = statusColor,
                            fontFamily    = FontFamily.Monospace
                        )
                    }
                    // Track
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.12f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressAnim)
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                    }
                }
            }

            // ── Result banner ─────────────────────────────────────────
            if (session.resultSummary.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(SemanticSuccess.copy(alpha = 0.08f))
                        .border(1.dp, SemanticSuccess.copy(alpha = 0.20f), RoundedCornerShape(9.dp))
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment     = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint               = SemanticSuccess,
                        modifier           = Modifier.size(14.dp).padding(top = 1.dp)
                    )
                    Text(
                        text       = session.resultSummary,
                        fontSize   = 12.sp,
                        color      = SemanticSuccess.copy(alpha = 0.85f),
                        maxLines   = 3,
                        overflow   = TextOverflow.Ellipsis,
                        lineHeight = 17.sp
                    )
                }
            }

            // ── Error banner ──────────────────────────────────────────
            if (session.errorMessage.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(SemanticError.copy(alpha = 0.08f))
                        .border(1.dp, SemanticError.copy(alpha = 0.20f), RoundedCornerShape(9.dp))
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment     = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        Icons.Outlined.Error,
                        contentDescription = null,
                        tint               = SemanticError,
                        modifier           = Modifier.size(14.dp).padding(top = 1.dp)
                    )
                    Text(
                        text       = session.errorMessage,
                        fontSize   = 12.sp,
                        color      = SemanticError.copy(alpha = 0.85f),
                        maxLines   = 3,
                        overflow   = TextOverflow.Ellipsis,
                        lineHeight = 17.sp
                    )
                }
            }

            // ── Step timeline toggle ──────────────────────────────────
            if (matchedSteps.isNotEmpty()) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick        = { showSteps = !showSteps },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier       = Modifier.height(30.dp)
                    ) {
                        Icon(
                            imageVector        = if (showSteps) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint               = PrimaryAccent,
                            modifier           = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text       = if (showSteps) "Hide steps" else "Steps (${matchedSteps.size})",
                            fontSize   = 11.sp,
                            color      = PrimaryAccent,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                AnimatedVisibility(
                    visible = showSteps,
                    enter   = expandVertically(),
                    exit    = shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(Surface2.copy(alpha = 0.55f))
                            .border(1.dp, BorderLight.copy(alpha = 0.45f), RoundedCornerShape(11.dp))
                            .padding(vertical = 6.dp)
                    ) {
                        matchedSteps.forEachIndexed { idx, step ->
                            StepTimelineRow(
                                step   = step,
                                isLast = idx == matchedSteps.lastIndex
                            )
                        }
                    }
                }
            }

            // ── Action buttons ────────────────────────────────────────
            if (!session.isTerminal) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (session.status == SessionStatus.RUNNING) {
                        OutlinedButton(
                            onClick        = onSuspend,
                            modifier       = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            shape          = RoundedCornerShape(10.dp),
                            border         = BorderStroke(1.dp, SemanticWarning.copy(alpha = 0.50f)),
                            colors         = ButtonDefaults.outlinedButtonColors(
                                contentColor = SemanticWarning
                            )
                        ) {
                            Icon(Icons.Filled.Pause, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Pause", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    OutlinedButton(
                        onClick        = onCancel,
                        modifier       = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        shape          = RoundedCornerShape(10.dp),
                        border         = BorderStroke(1.dp, SemanticError.copy(alpha = 0.45f)),
                        colors         = ButtonDefaults.outlinedButtonColors(
                            contentColor = SemanticError
                        )
                    ) {
                        Icon(Icons.Filled.Cancel, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Cancel", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Status helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun taskStatusColor(status: SessionStatus): Color = when (status) {
    SessionStatus.PENDING   -> BorderMid
    SessionStatus.RUNNING   -> PrimaryAccent
    SessionStatus.SUSPENDED -> SemanticWarning
    SessionStatus.COMPLETED -> SemanticSuccess
    SessionStatus.FAILED    -> SemanticError
    SessionStatus.CANCELLED -> TextTertiary
}

private fun taskStatusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.PENDING   -> "PENDING"
    SessionStatus.RUNNING   -> "RUNNING"
    SessionStatus.SUSPENDED -> "PAUSED"
    SessionStatus.COMPLETED -> "DONE"
    SessionStatus.FAILED    -> "FAILED"
    SessionStatus.CANCELLED -> "CANCELLED"
}

private fun taskStatusIcon(status: SessionStatus): ImageVector = when (status) {
    SessionStatus.PENDING   -> Icons.Outlined.HourglassEmpty
    SessionStatus.RUNNING   -> Icons.Filled.PlayArrow
    SessionStatus.SUSPENDED -> Icons.Outlined.PauseCircle
    SessionStatus.COMPLETED -> Icons.Outlined.CheckCircle
    SessionStatus.FAILED    -> Icons.Outlined.Error
    SessionStatus.CANCELLED -> Icons.Filled.Cancel
}

// ─────────────────────────────────────────────────────────────────────────────
// Step Timeline Row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepTimelineRow(step: AgentStep, isLast: Boolean) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment     = Alignment.Top
    ) {
        // Left column: outcome circle + connector line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.width(22.dp)
        ) {
            Box(
                modifier         = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (step.success) SemanticSuccess.copy(alpha = 0.14f)
                        else SemanticError.copy(alpha = 0.14f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = if (step.success) Icons.Filled.Check else Icons.Outlined.Error,
                    contentDescription = null,
                    tint               = if (step.success) SemanticSuccess else SemanticError,
                    modifier           = Modifier.size(12.dp)
                )
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(12.dp)
                        .background(BorderLight)
                )
            }
        }

        // Right column: type badge + step name + duration + output snippet
        Column(
            modifier            = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(SecondaryAccent.copy(alpha = 0.12f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text          = step.typeLabel.uppercase(),
                        fontSize      = 8.sp,
                        fontWeight    = FontWeight.Bold,
                        color         = SecondaryAccent,
                        letterSpacing = 0.5.sp
                    )
                }
                Text(
                    text      = step.displayName,
                    fontSize  = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color     = TextPrimary,
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis,
                    modifier  = Modifier.weight(1f, fill = false)
                )
                if (step.durationMs > 0L) {
                    Text(
                        text       = formatDuration(step.durationMs),
                        fontSize   = 9.sp,
                        color      = TextTertiary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (step.outputSummary.isNotBlank()) {
                Text(
                    text       = step.outputSummary,
                    fontSize   = 10.sp,
                    color      = TextSecondary.copy(alpha = 0.75f),
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }
            step.error?.let { err ->
                Text(
                    text       = err,
                    fontSize   = 10.sp,
                    color      = SemanticError.copy(alpha = 0.80f),
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Time / duration helpers
// ─────────────────────────────────────────────────────────────────────────────

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun formatTime(ms: Long): String = timeFmt.format(Date(ms))
private fun formatDuration(ms: Long): String = when {
    ms < 1_000L     -> "${ms}ms"
    ms < 60_000L    -> "${ms / 1000}s"
    ms < 3_600_000L -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    else            -> "${ms / 3_600_000}h ${(ms % 3_600_000) / 60_000}m"
}
