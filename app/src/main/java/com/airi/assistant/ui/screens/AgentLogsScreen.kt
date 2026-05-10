package com.airi.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ui.viewmodel.AgentViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AgentLogsScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit,
    onTraceSelected: () -> Unit
) {
    val traces by viewModel.traces.collectAsState()

    Scaffold(
        containerColor = Surface0,
        topBar = {
            AiriScreenHeader(title = "سجل العميل", onBack = onBack) {
                IconButton(onClick = { viewModel.clearLogs() }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "مسح", tint = SemanticError.copy(0.75f))
                }
            }
        }
    ) { padding ->
        if (traces.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.ManageHistory, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                    Text("لا توجد سجلات بعد", color = TextTertiary, fontSize = 14.sp)
                    Text("ستظهر هنا نشاطات العميل عند التشغيل", color = TextTertiary, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(traces, key = { it.id }) { trace ->
                    AgentTraceCard(trace = trace, onClick = {
                        viewModel.selectTrace(trace)
                        onTraceSelected()
                    })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun AgentTraceCard(
    trace: AgentTrace,
    onClick: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("d MMM · HH:mm:ss", Locale("ar")) }
    val dateStr = remember(trace.timestamp) { fmt.format(Date(trace.timestamp)) }

    val traceStatus = if (trace.success) "SUCCESS" else "FAILED"
    val statusColor = when (traceStatus) {
        "SUCCESS"  -> SemanticSuccess
        "FAILED"   -> SemanticError
        else       -> TextTertiary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .border(1.dp, BorderLight, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeuralGlowDot(color = statusColor, size = 8.dp, animate = false)
            Column(modifier = Modifier.weight(1f)) {
                Text(trace.originalInput, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(dateStr, color = TextTertiary, fontSize = 11.sp)
                    Text("·", color = TextTertiary, fontSize = 11.sp)
                    Text("${trace.stepCount} خطوة", color = TextTertiary, fontSize = 11.sp)
                }
            }
            NeuralBadge(
                text = if (trace.success) "نجاح" else "فشل",
                color = statusColor
            )
        }
    }
}
