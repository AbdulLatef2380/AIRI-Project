package com.airi.assistant.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.core.runtime.PersistentTaskSession
import com.airi.assistant.core.runtime.SessionStatus
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.CosmicBlack
import com.airi.assistant.ui.theme.CosmicDarkBlue
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TaskDashboardScreen — live view of all Autonomous Runtime Manager (ARM) sessions.
 *
 * ── WHAT IT SHOWS ─────────────────────────────────────────────────────────
 *
 *   - All [PersistentTaskSession]s from [AutonomousRuntimeManager.sessions]
 *   - Color-coded status badges (PENDING/RUNNING/SUSPENDED/COMPLETED/FAILED/CANCELLED)
 *   - Animated progress bar per session (step count + % if total steps known)
 *   - Goal text (first 120 chars) + agent ID + timestamps
 *   - Result summary / error message for terminal sessions
 *   - Cancel and Suspend buttons for active sessions
 *   - Running session count badge in header
 *
 * ── DATA FLOW ─────────────────────────────────────────────────────────────
 *
 *   Reads from [ServiceLocator.autonomousRuntimeManager.sessions] StateFlow.
 *   All mutations go through ARM — cancel(), suspend() — never directly to
 *   the checkpoint store. This ensures lifecycle integrity.
 *
 * ── AIRI_PROOF ────────────────────────────────────────────────────────────
 *
 *   TASK_DASHBOARD_CANCEL_REQUESTED  — user tapped Cancel on a session
 *   TASK_DASHBOARD_SUSPEND_REQUESTED — user tapped Suspend on a session
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDashboardScreen(onBack: () -> Unit) {
    val arm      = remember { ServiceLocator.autonomousRuntimeManager }
    val sessions by arm.sessions.collectAsState()

    val active    = sessions.count { it.status == SessionStatus.RUNNING }
    val suspended = sessions.count { it.status == SessionStatus.SUSPENDED }
    val total     = sessions.size

    Scaffold(
        containerColor = CosmicBlack,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector        = Icons.Filled.Timeline,
                            contentDescription = null,
                            tint               = CosmicAccent,
                            modifier           = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Task Dashboard", fontWeight = FontWeight.Bold)
                        if (active > 0) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color  = CosmicAccent,
                                shape  = CircleShape
                            ) {
                                Text(
                                    text     = "$active",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color    = Color.Black
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CosmicDarkBlue)
            )
        }
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(
                modifier          = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment  = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector        = Icons.Filled.Timeline,
                        contentDescription = null,
                        tint               = Color.White.copy(alpha = 0.2f),
                        modifier           = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No autonomous sessions yet",
                        color    = Color.White.copy(alpha = 0.4f),
                        fontSize = 14.sp
                    )
                    Text(
                        "Sessions appear when AIRI runs autonomous tasks",
                        color    = Color.White.copy(alpha = 0.25f),
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // ── Summary bar ────────────────────────────────────────────
                Surface(
                    color    = CosmicDarkBlue,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        SummaryPill("Running",   active,    CosmicAccent)
                        SummaryPill("Suspended", suspended, SemanticWarn)
                        SummaryPill("Total",     total,     Color.White.copy(alpha = 0.5f))
                    }
                }

                // ── Session list ───────────────────────────────────────────
                LazyColumn(
                    modifier            = Modifier.fillMaxSize(),
                    contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.sessionId }) { session ->
                        SessionCard(
                            session   = session,
                            onCancel  = {
                                Log.i("AIRI_PROOF", "TASK_DASHBOARD_CANCEL_REQUESTED sessionId=${session.sessionId}")
                                arm.cancelSession(session.sessionId)
                            },
                            onSuspend = {
                                Log.i("AIRI_PROOF", "TASK_DASHBOARD_SUSPEND_REQUESTED sessionId=${session.sessionId}")
                                arm.suspendSession(session.sessionId)
                            }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

// ── Session Card ──────────────────────────────────────────────────────────────

@Composable
private fun SessionCard(
    session:   PersistentTaskSession,
    onCancel:  () -> Unit,
    onSuspend: () -> Unit,
) {
    val statusColor = statusColor(session.status)

    // Animated progress
    val progressTarget = session.progressPercent / 100f
    val progressAnim by animateFloatAsState(
        targetValue   = progressTarget,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label         = "sessionProgress"
    )

    // Running pulse
    val infiniteTransition = rememberInfiniteTransition(label = "runningPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = 0.3f,
        animationSpec = infiniteRepeatable(
            animation  = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Surface(
        color = Color(0xFF0F1224),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Header row: status + session ID ───────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (session.status == SessionStatus.RUNNING)
                                statusColor.copy(alpha = dotAlpha)
                            else statusColor
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text       = session.status.name,
                    fontSize   = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color      = statusColor,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text       = session.sessionId.take(8),
                    fontSize   = 9.sp,
                    color      = Color.White.copy(alpha = 0.25f),
                    fontFamily = FontFamily.Monospace
                )
            }

            // ── Goal text ──────────────────────────────────────────────────
            Text(
                text     = session.goalText,
                fontSize = 13.sp,
                color    = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )

            // ── Agent + timestamps ─────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetaChip("agent: ${session.agentId}")
                MetaChip(formatTime(session.createdAtMs))
                if (session.isTerminal && session.finishedAtMs > 0) {
                    MetaChip("⏱ ${formatDuration(session.finishedAtMs - session.createdAtMs)}")
                }
            }

            // ── Progress bar ──────────────────────────────────────────────
            if (!session.isTerminal || session.totalSteps > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier          = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${session.stepIndex} / ${if (session.totalSteps > 0) session.totalSteps else "?"} steps",
                            fontSize = 10.sp,
                            color    = Color.White.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "${session.progressPercent}%",
                            fontSize = 10.sp,
                            color    = statusColor,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(statusColor.copy(alpha = 0.15f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressAnim)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(statusColor)
                        )
                    }
                }
            }

            // ── Result or error ────────────────────────────────────────────
            if (session.resultSummary.isNotBlank()) {
                Text(
                    text     = "✓ ${session.resultSummary}",
                    fontSize = 11.sp,
                    color    = SemanticSuccess.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (session.errorMessage.isNotBlank()) {
                Text(
                    text     = "✗ ${session.errorMessage}",
                    fontSize = 11.sp,
                    color    = SemanticError.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── Action buttons (only for non-terminal sessions) ───────────
            if (!session.isTerminal) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (session.status == SessionStatus.RUNNING) {
                        OutlinedButton(
                            onClick     = onSuspend,
                            modifier    = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            colors      = ButtonDefaults.outlinedButtonColors(
                                contentColor = SemanticWarn
                            )
                        ) {
                            Icon(Icons.Filled.Pause, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Suspend", fontSize = 11.sp)
                        }
                    }
                    OutlinedButton(
                        onClick  = onCancel,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = SemanticError)
                    ) {
                        Icon(Icons.Filled.Cancel, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun SummaryPill(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text("$label: $count", fontSize = 11.sp, color = color, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MetaChip(text: String) {
    Text(
        text       = text,
        fontSize   = 9.sp,
        color      = Color.White.copy(alpha = 0.35f),
        fontFamily = FontFamily.Monospace
    )
}

private fun statusColor(status: SessionStatus): Color = when (status) {
    SessionStatus.PENDING    -> Color(0xFF6B70A0)
    SessionStatus.RUNNING    -> CosmicAccent
    SessionStatus.SUSPENDED  -> SemanticWarn
    SessionStatus.COMPLETED  -> SemanticSuccess
    SessionStatus.FAILED     -> SemanticError
    SessionStatus.CANCELLED  -> Color(0xFF6B70A0)
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun formatTime(ms: Long): String = timeFmt.format(Date(ms))
private fun formatDuration(ms: Long): String = when {
    ms < 1_000         -> "${ms}ms"
    ms < 60_000        -> "${ms / 1000}s"
    ms < 3_600_000     -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
    else               -> "${ms / 3600000}h ${(ms % 3600000) / 60000}m"
}
