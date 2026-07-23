package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep

import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airi.assistant.R
import com.airi.assistant.ui.theme.*
import com.airi.assistant.perf.ModelBenchmark
import com.airi.assistant.perf.ModelBenchmarkRepository
import com.airi.assistant.perf.PerfClass
import com.airi.assistant.perf.QuantSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Empirical per-quantization benchmark dashboard.
 *
 * Reads runs persisted by [com.airi.assistant.perf.ModelBenchmarkRepository]
 * after each local-LLM generation. Shows:
 *   1) per-quant aggregate table (average tps / first-token / memory)
 *   2) latest 25 runs with classification badges
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPerformanceScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var runs    by remember { mutableStateOf(ModelBenchmarkRepository.all(ctx)) }
    var summary by remember { mutableStateOf(ModelBenchmarkRepository.summaryByQuant(ctx)) }

    fun refresh() {
        runs    = ModelBenchmarkRepository.all(ctx)
        summary = ModelBenchmarkRepository.summaryByQuant(ctx)
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_perf_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        ModelBenchmarkRepository.clear(ctx)
                        refresh()
                    }) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        if (runs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.model_perf_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AiriTheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.model_perf_summary_header),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            item { QuantComparisonTable(summary) }
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.model_perf_runs_header, runs.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(runs.asReversed().take(25)) { run -> RunRow(run) }
        }
    }
}

@Composable
private fun QuantComparisonTable(rows: List<QuantSummary>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header.
            Row(modifier = Modifier.fillMaxWidth()) {
                TableHeader(stringResource(R.string.model_perf_col_quant), 1.4f)
                TableHeader(stringResource(R.string.model_perf_col_runs),  0.7f)
                TableHeader(stringResource(R.string.model_perf_col_tps),   1.0f)
                TableHeader(stringResource(R.string.model_perf_col_ftl),   1.1f)
                TableHeader(stringResource(R.string.model_perf_col_mem),   1.0f)
                TableHeader(stringResource(R.string.model_perf_col_class), 1.0f)
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            for (r in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TableCell(r.quantLabel, 1.4f, bold = true)
                    TableCell("${r.runs}", 0.7f)
                    TableCell("%.1f".format(r.avgTokensPerSec), 1.0f)
                    TableCell("${r.avgFirstTokenMs} ms", 1.1f)
                    TableCell(if (r.avgMemMb > 0) "${r.avgMemMb} MB" else "—", 1.0f)
                    Box(modifier = Modifier.weight(1.0f)) {
                        ClassBadge(r.dominantClass)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TableHeader(label: String, weight: Float) {
    Text(
        label,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.labelSmall,
        color = AiriTheme.onSurfaceVariant
    )
}

@Composable
private fun RowScope.TableCell(label: String, weight: Float, bold: Boolean = false) {
    Text(
        label,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
    )
}

@Composable
private fun ClassBadge(p: PerfClass) {
    val (bg, fg) = when (p) {
        PerfClass.FAST     -> Color(0xFF1B5E20) to AiriTheme.onSurface
        PerfClass.BALANCED -> Color(0xFF1565C0) to AiriTheme.onSurface
        PerfClass.SLOW     -> Color(0xFFB71C1C) to AiriTheme.onSurface
    }
    Box(
        modifier = Modifier
            .background(bg, AIRIShapes.xs)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(p.name, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
private fun RunRow(r: ModelBenchmark) {
    val ts = remember(r.timestamp) {
        SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(r.timestamp))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.quantLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                ClassBadge(r.perfClass)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "$ts • ${r.modelDesc}",
                style = MaterialTheme.typography.labelSmall,
                color = AiriTheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    R.string.model_perf_run_line,
                    "%.1f".format(r.tokensPerSec),
                    r.firstTokenMs,
                    r.totalLatencyMs,
                    r.decodedTokens,
                    r.modelSizeMb,
                    r.nCtx,
                    r.nThreads
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
