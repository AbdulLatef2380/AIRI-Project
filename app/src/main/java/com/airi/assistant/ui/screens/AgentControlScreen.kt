package com.airi.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.AgentViewModel

@Composable
fun AgentControlScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val agentState   by viewModel.agentState.collectAsState()
    val isRunning    = agentState.isWorking

    Scaffold(
        containerColor = Surface0,
        topBar = {
            AiriScreenHeader(title = "التحكم في العميل", onBack = onBack)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            if (isRunning) listOf(PrimaryAccent.copy(0.18f), AccentDark.copy(0.10f))
                            else listOf(Surface2, Surface1)
                        )
                    )
                    .border(
                        1.dp,
                        if (isRunning) PrimaryAccent.copy(0.4f) else BorderLight,
                        RoundedCornerShape(20.dp)
                    )
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        modifier = Modifier.size(52.dp).clip(CircleShape)
                            .background(if (isRunning) PrimaryAccent.copy(0.22f) else Surface3),
                        contentAlignment = Alignment.Center
                    ) {
                        val inf = rememberInfiniteTransition(label = "agent_spin")
                        val rot by if (isRunning) inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "rot")
                        else remember { mutableStateOf(0f) }
                        Icon(
                            Icons.Outlined.SmartToy,
                            contentDescription = null,
                            tint = if (isRunning) PrimaryAccent else TextTertiary,
                            modifier = Modifier.size(26.dp).rotate(if (isRunning) rot else 0f)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (isRunning) "العميل نشط" else "العميل في وضع الراحة", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            agentState.currentGoal?.take(60) ?: "لا توجد مهمة حالية",
                            color = TextSecondary, fontSize = 12.sp, maxLines = 2
                        )
                    }
                    NeuralBadge(if (isRunning) "يعمل" else "خامل", if (isRunning) SemanticSuccess else TextTertiary)
                }
            }

            // Progress
            if (isRunning) {
                AnimatedVisibility(visible = true, enter = fadeIn() + expandVertically()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(Surface1).border(1.dp, BorderLight, RoundedCornerShape(16.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("التقدم", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).height(4.dp),
                            color = PrimaryAccent,
                            trackColor = Surface3
                        )
                        Text("${agentState.nodesCompleted} / ${agentState.totalNodes} خطوة", color = TextTertiary, fontSize = 11.sp)
                    }
                }
            }

            NeuralSectionLabel("الأدوات والوصول")
            NeuralSectionCard {
                NeuralRowItem(icon = Icons.Outlined.ManageHistory, title = "سجل العميل", subtitle = "عرض سجل الإجراءات والخطوات", onClick = { onNavigate(AiriRoute.AGENT_LOGS) })
                NeuralDivider()
                NeuralRowItem(icon = Icons.Outlined.Analytics, title = "المراقبة", subtitle = "مقاييس وحالة التشغيل", onClick = { onNavigate(AiriRoute.OBSERVABILITY) })
                NeuralDivider()
                NeuralRowItem(icon = Icons.Outlined.Dashboard, title = "لوحة المهام", subtitle = "المهام النشطة والمجدولة", onClick = { onNavigate(AiriRoute.TASK_DASHBOARD) })
            }

            if (isRunning) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { viewModel.stopAgent() },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SemanticError.copy(0.15f), contentColor = SemanticError),
                    border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp)
                ) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("إيقاف العميل", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
