package com.airi.assistant.ui.screens

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.agent.tracker.GoalTracker
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * PlanningScreen — Live planning dashboard and goal execution trace UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanningScreen(
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val goalTracker = remember {
        runCatching { ServiceLocator.goalTracker }.getOrNull()
    }
    val goals by (goalTracker?.goals ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()

    val activeGoals   = goals.filter { !it.isTerminal }
    val terminalGoals = goals.filter { it.isTerminal }.sortedByDescending { it.updatedAtMs }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface1),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                title = {
                    Column {
                        Text("Planning", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 17.sp)
                        Text("${activeGoals.size} active · ${terminalGoals.size} completed",
                            color = TextSecondary, fontSize = 11.sp)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            goalTracker?.pruneTerminal()
                        }
                    }) {
                        Icon(Icons.Default.CleaningServices, contentDescription = "Prune", tint = TextSecondary)
                    }
                }
            )
        }
    ) { padding ->
        if (goals.isEmpty()) {
            EmptyPlanningState(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (activeGoals.isNotEmpty()) {
                    item {
                        SectionHeader("Active Goals", activeGoals.size, PrimaryAccent)
                    }
                    items(activeGoals, key = { it.id }) { goal ->
                        GoalCard(goal = goal, goalTracker = goalTracker)
                    }
                }

                if (terminalGoals.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        SectionHeader("Completed", terminalGoals.size, TextSecondary)
                    }
                    items(terminalGoals.take(20), key = { it.id }) { goal ->
                        GoalCard(goal = goal, goalTracker = goalTracker)
                    }
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

// ── Sub-components ────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, count: Int, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(title, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text("($count)", color = TextTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun GoalCard(
    goal:        GoalTracker.TrackedGoal,
    goalTracker: GoalTracker?,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(!goal.isTerminal) }
    val accentColor = when (goal.status) {
        GoalTracker.GoalStatus.IN_PROGRESS -> PrimaryAccent
        GoalTracker.GoalStatus.DONE        -> SemanticSuccess
        GoalTracker.GoalStatus.FAILED      -> SemanticError
        GoalTracker.GoalStatus.CANCELLED   -> TextSecondary
        GoalTracker.GoalStatus.PAUSED      -> SemanticWarning
        GoalTracker.GoalStatus.PENDING     -> SecondaryAccent
    }

    val progressAnim by animateFloatAsState(
        targetValue    = goal.progressPct / 100f,
        animationSpec  = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label          = "progress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded }
            .padding(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(goal.status, accentColor)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = goal.description.take(80),
                    color    = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (goal.agentId.isNotBlank()) {
                    Text(goal.agentId, color = TextTertiary, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusBadge(goal.status, accentColor)
        }

        Spacer(Modifier.height(12.dp))

        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Surface2)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressAnim)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(listOf(accentColor, accentColor.copy(alpha = 0.6f)))
                    )
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${goal.progressPct}%", color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(formatTimestamp(goal.updatedAtMs), color = TextTertiary, fontSize = 11.sp)
        }

        // Expanded: milestones + error + actions
        AnimatedVisibility(visible = expanded) {
            Column {
                if (goal.milestones.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Divider(color = BorderLight, thickness = 0.5.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("Milestones", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    goal.milestones.takeLast(6).forEach { milestone ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier          = Modifier.padding(vertical = 2.dp)
                        ) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(TextTertiary))
                            Spacer(Modifier.width(8.dp))
                            Text(milestone.text.take(80), color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }

                if (goal.errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "⚠ ${goal.errorMessage.take(120)}",
                        color = SemanticError,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SemanticError.copy(alpha = 0.1f))
                            .padding(8.dp)
                    )
                }

                if (!goal.isTerminal && goalTracker != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    goalTracker.cancel(goal.id)
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = SemanticError),
                        ) {
                            Icon(Icons.Default.Cancel, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: GoalTracker.GoalStatus, color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val alpha by if (status == GoalTracker.GoalStatus.IN_PROGRESS) {
        infiniteTransition.animateFloat(
            initialValue   = 0.4f,
            targetValue    = 1.0f,
            animationSpec  = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label          = "dot_pulse"
        )
    } else {
        remember { mutableStateOf(1.0f) }
    }

    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

@Composable
private fun StatusBadge(status: GoalTracker.GoalStatus, color: Color) {
    Text(
        text     = status.name.replace("_", " "),
        color    = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun EmptyPlanningState(modifier: Modifier = Modifier) {
    Box(
        modifier        = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.AutoAwesome, null,
                tint = TextTertiary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("No goals yet", color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text("Start a task in the chat to see it here",
                color = TextTertiary, fontSize = 13.sp)
        }
    }
}

private fun formatTimestamp(ms: Long): String = runCatching {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    sdf.format(Date(ms))
}.getOrDefault("")
