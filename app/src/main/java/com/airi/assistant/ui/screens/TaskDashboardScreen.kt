package com.airi.assistant.ui.screens

// ─────────────────────────────────────────────────────────────────────────────
// TaskDashboardScreen — full visual redesign; all ViewModel wiring preserved.
// Uses NeuralComponents for consistency with the AIRI design system.
// ─────────────────────────────────────────────────────────────────────────────

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.core.runtime.PersistentTaskSession
import com.airi.assistant.core.runtime.SessionStatus
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TaskDashboardScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val arm = remember { runCatching { ServiceLocator.autonomousRuntimeManager }.getOrNull() }
    val armSessionsFlow = remember(arm) {
        arm?.sessions ?: MutableStateFlow(emptyList<PersistentTaskSession>())
    }
    val armSessions by armSessionsFlow.collectAsState()
    val runningCount = armSessions.count { it.status == SessionStatus.RUNNING }

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("المجدول", "المكتمل", "متعدد الخطوات")

    Scaffold(
        containerColor = Surface0,
        topBar = {
            AiriScreenHeader(title = "المهام", onBack = onBack) {
                IconButton(onClick = { onNavigate(AiriRoute.OBSERVABILITY) }) {
                    Icon(Icons.Outlined.BarChart, contentDescription = "Stats", tint = PrimaryAccent)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Runtime status banner
            AnimatedVisibility(
                visible = runningCount > 0,
                enter = expandVertically() + fadeIn(),
                exit  = shrinkVertically() + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(PrimaryAccent.copy(0.18f), AccentDark.copy(0.12f))
                            )
                        )
                        .border(
                            width = 0.5.dp,
                            brush = Brush.horizontalGradient(listOf(PrimaryAccent.copy(0.4f), Color.Transparent)),
                            shape = RoundedCornerShape(0.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val inf = rememberInfiniteTransition(label = "spin")
                        val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "r")
                        Icon(Icons.Default.Autorenew, tint = PrimaryAccent, modifier = Modifier.size(16.dp).rotate(rot), contentDescription = null)
                        Text("$runningCount مهمة قيد التشغيل", color = PrimaryAccent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        NeuralBadge("مباشر", color = SemanticSuccess)
                    }
                }
            }

            // Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEachIndexed { i, label ->
                    TaskTab(label, selectedTab == i) { selectedTab = i }
                }
            }

            // Content
            when (selectedTab) {
                0 -> ScheduledTasksTab(armSessions.filter { it.status == SessionStatus.RUNNING || it.status == SessionStatus.PENDING })
                1 -> ScheduledTasksTab(armSessions.filter { it.status == SessionStatus.COMPLETED || it.status == SessionStatus.FAILED })
                2 -> MultiStepTasksTab()
            }
        }
    }
}

@Composable
private fun TaskTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PrimaryAccent else Surface2)
            .border(1.dp, if (selected) PrimaryAccent else BorderLight, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ScheduledTasksTab(sessions: List<PersistentTaskSession>) {
    if (sessions.isEmpty()) {
        EmptyTasksPlaceholder()
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(sessions, key = { it.id }) { session ->
            SessionCard(session)
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SessionCard(session: PersistentTaskSession) {
    val statusColor = when (session.status) {
        SessionStatus.RUNNING   -> PrimaryAccent
        SessionStatus.COMPLETED -> SemanticSuccess
        SessionStatus.FAILED    -> SemanticError
        else                    -> TextTertiary
    }
    val statusLabel = when (session.status) {
        SessionStatus.RUNNING   -> "يعمل"
        SessionStatus.COMPLETED -> "مكتمل"
        SessionStatus.FAILED    -> "فشل"
        else                    -> "معلق"
    }
    val fmt = remember { SimpleDateFormat("d MMM · HH:mm", Locale("ar")) }
    val dateStr = remember(session.createdAt) { fmt.format(Date(session.createdAt)) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, BorderLight, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusColor.copy(alpha = 0.14f))
                        .border(0.5.dp, statusColor.copy(0.3f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val inf = rememberInfiniteTransition(label = "task_spin")
                    val rot by if (session.status == SessionStatus.RUNNING)
                        inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "rot")
                    else
                        remember { mutableStateOf(0f) }
                    Icon(
                        if (session.status == SessionStatus.RUNNING) Icons.Default.Autorenew
                        else if (session.status == SessionStatus.COMPLETED) Icons.Default.CheckCircle
                        else Icons.Default.Error,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp).rotate(rot)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(session.goal, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(dateStr, color = TextTertiary, fontSize = 11.sp)
                }
                NeuralBadge(statusLabel, statusColor)
            }
            if (session.status == SessionStatus.RUNNING) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).height(3.dp),
                    color = PrimaryAccent,
                    trackColor = Surface3
                )
            }
        }
    }
}

@Composable
private fun MultiStepTasksTab() {
    val durableManager = remember { runCatching { ServiceLocator.durableTaskManager }.getOrNull() }
    val tasks by (durableManager?.tasks?.collectAsState()
        ?: remember { mutableStateOf<List<DurableTask>>(emptyList()) })

    if (tasks.isEmpty()) {
        EmptyTasksPlaceholder()
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(tasks, key = { it.id }) { task ->
            DurableTaskCard(task)
        }
    }
}

@Composable
private fun DurableTaskCard(task: DurableTask) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, BorderLight, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(PrimaryAccent.copy(0.14f)).border(0.5.dp, PrimaryAccent.copy(0.3f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AccountTree, contentDescription = null, tint = PrimaryAccent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.description, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeuralStatusDot(color = PrimaryAccent, size = 7.dp, animate = false)
                Text("${task.currentStepIndex + 1} / ${task.totalSteps} خطوة", color = TextSecondary, fontSize = 12.sp)
            }
            LinearProgressIndicator(
                progress = { if (task.totalSteps > 0) (task.currentStepIndex + 1f) / task.totalSteps else 0f },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).height(3.dp),
                color = PrimaryAccent,
                trackColor = Surface3
            )
        }
    }
}

@Composable
private fun EmptyTasksPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Task, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(48.dp))
            Text("لا توجد مهام بعد", color = TextTertiary, fontSize = 14.sp)
            Text("ستظهر المهام المجدولة هنا", color = TextTertiary, fontSize = 12.sp)
        }
    }
}
