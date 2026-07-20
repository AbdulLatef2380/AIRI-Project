package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.AiriTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
private val WarnColor    = Color(0xFFFFB74D)  // amber
private val ErrorColor   = Color(0xFFEF5350)  // red
private val OkColor      = CosmicAccent

@Composable
private fun dimWhite() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
@Composable
private fun subtleWhite() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

private const val MAX_VISIBLE_EVENTS = 20

/**
 * Full runtime status panel — mode, thermal, memory, context, model, generation.
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
                else       -> dimWhite()
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
                ModeSource.MANUAL_OVERRIDE -> dimWhite()
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
                GenerationPhase.IDLE      -> dimWhite()
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
            valueColor = if (diagnostics.draftModelActive) OkColor else subtleWhite()
        )
        DiagRow(
            label = "GPU / Vulkan",
            value = if (diagnostics.gpuVulkanActive) "ACTIVE" else "CPU only",
            valueColor = if (diagnostics.gpuVulkanActive) OkColor else subtleWhite()
        )
    }
}

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
                tint = dimWhite(),
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
                        color = subtleWhite(),
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
                            color = subtleWhite(),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AdvancedDiagnosticsSection(diagnostics: RuntimeDiagnosticsState) {
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
                tint = dimWhite(),
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
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    val finalColor = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = dimWhite(), fontSize = 12.sp)
        Text(value, color = finalColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AdvancedRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = subtleWhite(), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = dimWhite(), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun EventRow(event: RuntimeEvent) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(6.dp).padding(top = 6.dp).clip(CircleShape)
                .background(if (event.severity == EventSeverity.ERROR) ErrorColor else OkColor)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(event.reason, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
            Text(event.timestampMs.toString(), color = subtleWhite(), fontSize = 9.sp)
        }
    }
}

@Composable
private fun DiagDivider() {
    Divider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
}
