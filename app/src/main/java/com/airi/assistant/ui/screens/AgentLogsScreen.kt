package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.AgentViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentLogsScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit,
    onTraceSelected: () -> Unit
) {
    val traces by viewModel.traces.collectAsState()
    val sorted = remember(traces) { traces.sortedByDescending { it.timestamp } }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.65f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.agent_logs), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground, fontSize = 16.sp)
                        Text("${traces.size} traces recorded", fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (traces.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearLogs() }) {
                            Text(stringResource(R.string.clear), color = Color(0xFFFF6B6B).copy(alpha = 0.8f), fontSize = 12.sp)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (sorted.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.ManageHistory,
                        contentDescription = null,
                        tint = CosmicAccent.copy(alpha = 0.3f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.agent_logs_no_traces), color = AiriTheme.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Traces appear when skills or tasks are executed",
                        color = AiriTheme.outline,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sorted, key = { it.id }) { trace ->
                    TraceListItem(
                        trace = trace,
                        onClick = {
                            viewModel.selectTrace(trace)
                            onTraceSelected()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TraceListItem(
    trace: AgentTrace,
    onClick: () -> Unit
) {
    val timeStr = remember(trace.timestamp) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(trace.timestamp))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(
                1.dp,
                if (trace.hasErrors) Color(0xFFFF5252).copy(alpha = 0.25f)
                else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (trace.success) CosmicAccent.copy(alpha = 0.15f)
                        else Color(0xFFFF5252).copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (trace.success) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                    contentDescription = null,
                    tint = if (trace.success) CosmicAccent else Color(0xFFFF5252),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trace.originalInput,
                    color = AiriTheme.onBackground.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeStr,
                        color = AiriTheme.outline,
                        fontSize = 11.sp
                    )
                    Text("•", color = AiriTheme.outline.copy(alpha = 0.6f), fontSize = 10.sp)
                    Text(
                        text = "${trace.stepCount} step${if (trace.stepCount != 1) "s" else ""}",
                        color = CosmicAccent.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                    if (trace.hasErrors) {
                        Text("•", color = AiriTheme.outline.copy(alpha = 0.6f), fontSize = 10.sp)
                        Text(
                            text = "Has errors",
                            color = Color(0xFFFF5252).copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = AiriTheme.outline.copy(alpha = 0.25f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
