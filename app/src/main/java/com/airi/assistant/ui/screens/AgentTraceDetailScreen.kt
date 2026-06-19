package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.AgentViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTraceDetailScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit
) {
    val trace by viewModel.selectedTrace.collectAsState()

    if (trace == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val t = trace!!
    val timeStr = remember(t.timestamp) {
        SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(t.timestamp))
    }

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
                        Text(stringResource(R.string.trace_detail_title), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground, fontSize = 16.sp)
                        Text(timeStr, fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Input ─────────────────────────────────────────────────────
            item {
                TraceDetailCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Forum, contentDescription = null,
                            tint = CosmicAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.trace_user_input_label), color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(t.originalInput, color = AiriTheme.onBackground.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 20.sp)
                }
            }

            // ── Summary banner ────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (t.success) Color(0xFF00C853).copy(alpha = 0.1f)
                            else Color(0xFFFF5252).copy(alpha = 0.1f)
                        )
                        .border(
                            1.dp,
                            if (t.success) Color(0xFF00C853).copy(alpha = 0.25f)
                            else Color(0xFFFF5252).copy(alpha = 0.25f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (t.success) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                            contentDescription = null,
                            tint = if (t.success) Color(0xFF00C853) else Color(0xFFFF5252),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (t.success) "Execution Successful" else "Execution Failed",
                            color = if (t.success) Color(0xFF00C853) else Color(0xFFFF5252),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        "${t.successCount}/${t.stepCount} steps",
                        color = AiriTheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            // ── Steps ─────────────────────────────────────────────────────
            if (t.steps.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Icon(Icons.Outlined.Timeline, contentDescription = null,
                            tint = CosmicAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.trace_execution_steps), color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                itemsIndexed(t.steps) { idx, step ->
                    AgentStepCard(index = idx, step = step)
                }
            }

            // ── Final Result ──────────────────────────────────────────────
            if (t.finalResult.isNotBlank()) {
                item {
                    TraceDetailCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = null,
                                tint = CosmicAccent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.trace_final_result), color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = t.finalResult,
                            color = AiriTheme.onBackground.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun AgentStepCard(index: Int, step: AgentStep) {
    val successColor = if (step.success) Color(0xFF00C853) else Color(0xFFFF5252)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, successColor.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(CosmicAccent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${index + 1}", color = CosmicAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(step.displayName, color = AiriTheme.onBackground.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(step.typeLabel, color = AiriTheme.outline, fontSize = 10.sp)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (step.durationMs > 0) {
                        Text(
                            text = "${step.durationMs}ms",
                            color = AiriTheme.outline,
                            fontSize = 10.sp
                        )
                    }
                    Icon(
                        if (step.success) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                        contentDescription = null,
                        tint = successColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Input params
            if (step.inputParams.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.03f))
                        .padding(8.dp)
                ) {
                    Column {
                        Text(stringResource(R.string.trace_input_badge), color = CosmicAccent.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        step.inputParams.forEach { (k, v) ->
                            Text(
                                text = "$k: $v",
                                color = AiriTheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // Output
            if (step.outputSummary.isNotBlank() || step.error != null) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (step.success) Color.White.copy(alpha = 0.03f)
                            else Color(0xFFFF5252).copy(alpha = 0.05f)
                        )
                        .padding(8.dp)
                ) {
                    Column {
                        Text(
                            text = if (step.success) "OUTPUT" else "ERROR",
                            color = if (step.success) CosmicAccent.copy(alpha = 0.5f)
                                    else Color(0xFFFF5252).copy(alpha = 0.7f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = step.error ?: step.outputSummary,
                            color = if (step.success) Color.White.copy(alpha = 0.7f)
                                    else Color(0xFFFF5252).copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceDetailCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column(content = content)
    }
}
