package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.AiriTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.core.debug.*
import com.airi.assistant.ui.theme.CosmicAccent
// RuntimeDiagnosticsPanel — production-grade runtime diagnostics UI
//
// All composables in this file accept IMMUTABLE state parameters already
// collected by the caller (PerformanceScreen). No Flow collection, no
// coroutine launch, no polling inside composables.
//
// Recomposition is driven solely by changes to the immutable snapshots
// passed as parameters — zero jank during streaming because the diagnostics
// state is only updated at generation lifecycle boundaries, not per-token.
private val WarnColor    = Color(0xFFFFB74D)  // amber
private val ErrorColor   = Color(0xFFEF5350)  // red
private val OkColor      = CosmicAccent
private val DimWhite     = Color.White.copy(alpha = 0.55f)
private val SubtleWhite  = Color.White.copy(alpha = 0.35f)
// Public entry-points
/**
 * Full runtime status panel — mode, thermal, memory, context, model, generation.
 *
 * Embed once in PerformanceScreen. State is passed in as a fully-formed
 * [RuntimeDiagnosticsState] snapshot; this composable never collects Flows.
 */
@Composable
fun RuntimeStatusPanel(diagnostics: RuntimeDiagnosticsState) {
    SettingsSurface {
        SettingsCategoryHeader(
            icon  = Icons.Outlined.MonitorHeart,
            title = "Runtime Status"
        )
        Spacer(Modifier.height(12.dp))
        DiagRow(
            label = "Effective Mode",
            value = diagnostics.effectiveMode,
            valueColor = when (diagnostics.effectiveMode) {
                "FAST"     -> WarnColor
                "BALANCED" -> OkColor
                "QUALITY"  -> OkColor
                else       -> DimWhite
            }
        )
        DiagRow(
            label = "Mode Source",
            value = when (diagnostics.modeSource) {
                ModeSource.USER               -> "USER"
                ModeSource.SUPERVISOR_THERMAL -> "SUPERVISOR — THERMAL"
                ModeSource.SUPERVISOR_MEMORY  -> "SUPERVISOR — MEMORY"
                ModeSource.MANUAL_OVERRIDE    -> "MANUAL OVERRIDE"
            },
            valueColor = when (diagnostics.modeSource) {
                ModeSource.USER            -> OkColor
                ModeSource.MANUAL_OVERRIDE -> DimWhite
                else                       -> WarnColor
            }
        )

        DiagDivider()
        DiagRow(
            label = "Thermal Status",
            value = diagnostics.thermalLevel.name + if (diagnostics.thermalRaw > 0)
                " (raw=${diagnostics.thermalRaw})" else "",
            valueColor = when (diagnostics.thermalLevel) {
                ThermalLevel.NONE, ThermalLevel.LIGHT -> OkColor
                ThermalLevel.MODERATE                 -> WarnColor
                ThermalLevel.SEVERE, ThermalLevel.CRITICAL -> ErrorColor
            }
        )
        DiagRow(
            label = "Available RAM",
            value = "${diagnostics.availRamMb} MB" + if (diagnostics.isLowMemory) " ⚠ LOW" else "",
            valueColor = when {
                diagnostics.isLowMemory         -> ErrorColor
                diagnostics.availRamMb < 300L   -> ErrorColor
                diagnostics.availRamMb < 600L   -> WarnColor
                else                            -> OkColor
            }
        )

        DiagDivider()
        val kvPct = remember(diagnostics.kvUsed, diagnostics.kvMax) {
            if (diagnostics.kvMax > 0) (diagnostics.kvUsed * 100) / diagnostics.kvMax else 0
        }
        DiagRow(
            label = "Context Usage",
            value = if (diagnostics.kvMax > 0)
                "${diagnostics.kvUsed} / ${diagnostics.kvMax} tokens ($kvPct%)"
            else "—",
            valueColor = when {
                kvPct >= 90 -> ErrorColor
                kvPct >= 75 -> WarnColor
                else        -> OkColor
            }
        )

        DiagDivider()
        DiagRow(label = "Active Model", value = diagnostics.modelName)
        DiagRow(label = "Quantization", value = diagnostics.modelQuant)

        DiagDivider()
        DiagRow(
            label = "Generation State",
            value = diagnostics.generationPhase.name,
            valueColor = when (diagnostics.generationPhase) {
                GenerationPhase.IDLE      -> DimWhite
                GenerationPhase.PREFILL   -> WarnColor
                GenerationPhase.GENERATE  -> OkColor
                GenerationPhase.CANCELLED -> WarnColor
                GenerationPhase.CLEANUP   -> ErrorColor
            }
        )
        DiagRow(
            label = "Throughput",
            value = if (diagnostics.tokensPerSec > 0f)
                "%.1f t/s".format(diagnostics.tokensPerSec) else "—"
        )

        DiagDivider()
        DiagRow(
            label = "Draft Model",
            value = if (diagnostics.draftModelActive) "ACTIVE" else "INACTIVE",
            valueColor = if (diagnostics.draftModelActive) OkColor else SubtleWhite
        )
        DiagRow(
            label = "GPU / Vulkan",
            value = if (diagnostics.gpuVulkanActive) "ACTIVE" else "CPU only",
            valueColor = if (diagnostics.gpuVulkanActive) OkColor else SubtleWhite
        )
    }
}

/**
 * Active warnings panel. Hidden entirely when there are no warnings —
 * no empty card cluttering the screen.
 *
 * Each warning is shown with a prominent amber icon. The panel becomes
 * a hard red error card if any warning contains a critical keyword.
 */
@Composable
fun RuntimeWarningsPanel(warnings: List<String>) {
    if (warnings.isEmpty()) return

    val hasCritical = remember(warnings) {
        warnings.any { it.contains("overflow", ignoreCase = true) ||
                       it.contains("instability", ignoreCase = true) ||
                       it.contains("failure", ignoreCase = true) }
    }

    SettingsSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = if (hasCritical) ErrorColor else WarnColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Runtime Warnings",
                fontWeight = FontWeight.Bold,
                color = if (hasCritical) ErrorColor else WarnColor,
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(10.dp))

        warnings.forEachIndexed { idx, warning ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (hasCritical) ErrorColor.copy(alpha = 0.08f)
                        else WarnColor.copy(alpha = 0.07f)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = if (hasCritical) ErrorColor else WarnColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    warning,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
            if (idx < warnings.lastIndex) Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * Collapsible runtime event timeline.
 *
 * Displays the last [MAX_VISIBLE] events from the ring buffer in reverse
 * chronological order. Uses a simple Column (not LazyColumn) because the
 * visible set is bounded and small, avoiding LazyColumn's recycling overhead
 * for a static list.
 */
@Composable
fun RuntimeEventTimeline(events: List<com.airi.assistant.core.debug.RuntimeEvent>) {
    var expanded by remember { mutableStateOf(false) }

    SettingsSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = CosmicAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Event Timeline (${events.size})",
                    fontWeight = FontWeight.Bold,
                    color = CosmicAccent,
                    fontSize = 13.sp
                )
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = DimWhite,
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit  = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                if (events.isEmpty()) {
                    Text(
                        "No events yet",
                        color = SubtleWhite,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    val visible = remember(events) { events.takeLast(MAX_VISIBLE_EVENTS).reversed() }
                    visible.forEachIndexed { idx, event ->
                        EventRow(event)
                        if (idx < visible.lastIndex) {
                            Divider(
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                    if (events.size > MAX_VISIBLE_EVENTS) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "… ${events.size - MAX_VISIBLE_EVENTS} older events not shown",
                            color = SubtleWhite,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Collapsible advanced diagnostics section — session IDs, generation IDs,
 * thread counts, uptime, speculative state and other internal counters.
 * Intended for developers and support debugging — hidden by default.
 */
@Composable
fun AdvancedDiagnosticsSection(diagnostics: RuntimeDiagnosticsState) {
    var expanded by remember { mutableStateOf(false) }

    val genDurationStr = remember(diagnostics.generationDurationMs) {
        when {
            diagnostics.generationDurationMs <= 0L -> "—"
            diagnostics.generationDurationMs < 1000L ->
                "${diagnostics.generationDurationMs} ms"
            else ->
                "%.1f s".format(diagnostics.generationDurationMs / 1000.0)
        }
    }

    val uptimeStr = remember(diagnostics.runtimeUptimeMs) {
        when {
            diagnostics.runtimeUptimeMs <= 0L -> "—"
            diagnostics.runtimeUptimeMs < 60_000L ->
                "${diagnostics.runtimeUptimeMs / 1000}s"
            diagnostics.runtimeUptimeMs < 3_600_000L ->
                "${diagnostics.runtimeUptimeMs / 60_000}m ${(diagnostics.runtimeUptimeMs % 60_000) / 1000}s"
            else ->
                "${diagnostics.runtimeUptimeMs / 3_600_000}h ${(diagnostics.runtimeUptimeMs % 3_600_000) / 60_000}m"
        }
    }

    SettingsSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.BugReport,
                    contentDescription = null,
                    tint = CosmicAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Advanced Diagnostics",
                    fontWeight = FontWeight.Bold,
                    color = CosmicAccent,
                    fontSize = 13.sp
                )
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = DimWhite,
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit  = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                AdvancedRow("Session ID",
                    if (diagnostics.sessionId > 0L) diagnostics.sessionId.toString() else "—")
                AdvancedRow("Generation ID",
                    if (diagnostics.generationId > 0L) diagnostics.generationId.toString() else "—")
                AdvancedRow("Replay Token Count",
                    if (diagnostics.replayTokenCount > 0) diagnostics.replayTokenCount.toString() else "—")
                AdvancedRow("n_ctx",
                    if (diagnostics.nCtx > 0) diagnostics.nCtx.toString() else "—")
                AdvancedRow("Native Threads",
                    if (diagnostics.nThreads > 0) diagnostics.nThreads.toString() else "—")
                AdvancedRow("Runtime Uptime", uptimeStr)
                AdvancedRow("Generation Duration", genDurationStr)
                AdvancedRow("Speculative Decoding",
                    if (diagnostics.speculativeActive) "ACTIVE" else "INACTIVE")
            }
        }
    }
}
// Internal composables
private const val MAX_VISIBLE_EVENTS = 30

@Composable
private fun DiagRow(
    label:      String,
    value:      String,
    valueColor: Color = OkColor
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, color = DimWhite, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color      = valueColor,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AdvancedRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, color = DimWhite, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color      = SubtleWhite,
            fontSize   = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun EventRow(event: com.airi.assistant.core.debug.RuntimeEvent) {
    val (severityIcon, severityColor) = when (event.severity) {
        EventSeverity.INFO  -> Icons.Outlined.Info    to OkColor.copy(alpha = 0.7f)
        EventSeverity.WARN  -> Icons.Outlined.Warning to WarnColor
        EventSeverity.ERROR -> Icons.Outlined.Error   to ErrorColor
    }

    val relativeTime = remember(event.timestampMs) {
        val diffMs = System.currentTimeMillis() - event.timestampMs
        when {
            diffMs < 5_000L       -> "just now"
            diffMs < 60_000L      -> "${diffMs / 1000}s ago"
            diffMs < 3_600_000L   -> "${diffMs / 60_000}m ago"
            else                  -> "${diffMs / 3_600_000}h ago"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(severityIcon, contentDescription = null, tint = severityColor,
            modifier = Modifier.size(12.dp).padding(top = 1.dp))
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.reason,
                color    = AiriTheme.onBackground.copy(alpha = 0.80f),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
            Text(
                "${event.subsystem}  ·  $relativeTime",
                color    = SubtleWhite,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun DiagDivider() {
    Divider(
        color    = AiriTheme.onBackground.copy(alpha = 0.05f),
        modifier = Modifier.padding(vertical = 5.dp)
    )
}
