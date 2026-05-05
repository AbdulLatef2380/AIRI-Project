package com.airi.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ExecutionConsole — dark terminal-style log viewer for live agent output.
 *
 * Renders a list of [ConsoleEntry] lines with level-based colouring, auto-scroll
 * to the latest entry, and optional start/stop/clear controls in the header bar.
 *
 * Intended for:
 *  - AgentLogsScreen "Live AIRI Log" tab (AIRI_PROOF logcat stream)
 *  - A fullscreen "Terminal" overlay when the agent runs exec / shell commands
 *
 * Visual style: dark surface, monospace font, coloured level prefixes —
 * similar to Logcat / VS Code terminal.
 */

enum class ConsoleLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

data class ConsoleEntry(
    val level:   ConsoleLevel,
    val tag:     String,
    val message: String,
    val timeLabel: String = "",
)

@Composable
fun ExecutionConsole(
    entries:     List<ConsoleEntry>,
    isStreaming: Boolean          = false,
    onStart:     (() -> Unit)?    = null,
    onStop:      (() -> Unit)?    = null,
    onClear:     (() -> Unit)?    = null,
    modifier:    Modifier         = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Surface(
        modifier      = modifier.fillMaxWidth(),
        color         = Color(0xFF0D1117),
        shape         = RoundedCornerShape(10.dp),
        tonalElevation = 0.dp,
    ) {
        Column {
            // ── Header bar ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (isStreaming) Color(0xFF3FB950) else Color(0xFF484F58))
                    )
                    Text(
                        text = if (isStreaming) "AIRI PROOF LOG — LIVE" else "AIRI PROOF LOG",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8B949E),
                        letterSpacing = 1.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onStart != null || onStop != null) {
                        FilledTonalIconButton(
                            onClick = { if (isStreaming) onStop?.invoke() else onStart?.invoke() },
                            modifier = Modifier.size(28.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xFF21262D),
                                contentColor   = if (isStreaming) Color(0xFFF85149) else Color(0xFF3FB950),
                            ),
                        ) {
                            Icon(
                                if (isStreaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = if (isStreaming) "Stop" else "Start",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (onClear != null) {
                        FilledTonalIconButton(
                            onClick = { onClear() },
                            modifier = Modifier.size(28.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xFF21262D),
                                contentColor   = Color(0xFF8B949E),
                            ),
                        ) {
                            Icon(Icons.Filled.Clear, "Clear", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // ── Log lines ────────────────────────────────────────────────────
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No entries — start streaming to capture AIRI_PROOF events",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF484F58),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            } else {
                LazyColumn(
                    state    = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    items(entries, key = { "${it.timeLabel}${it.message}" }) { entry ->
                        ConsoleLine(entry)
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ConsoleLine(entry: ConsoleEntry) {
    val levelColor = when (entry.level) {
        ConsoleLevel.VERBOSE -> Color(0xFF484F58)
        ConsoleLevel.DEBUG   -> Color(0xFF8B949E)
        ConsoleLevel.INFO    -> Color(0xFF3FB950)
        ConsoleLevel.WARN    -> Color(0xFFD29922)
        ConsoleLevel.ERROR   -> Color(0xFFF85149)
    }
    val levelTag = when (entry.level) {
        ConsoleLevel.VERBOSE -> "V"
        ConsoleLevel.DEBUG   -> "D"
        ConsoleLevel.INFO    -> "I"
        ConsoleLevel.WARN    -> "W"
        ConsoleLevel.ERROR   -> "E"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Time label
        if (entry.timeLabel.isNotBlank()) {
            Text(
                text = entry.timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF484F58),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.width(56.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        // Level indicator
        Text(
            text = levelTag,
            style = MaterialTheme.typography.labelSmall,
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier.width(12.dp),
        )
        Spacer(Modifier.width(6.dp))
        // Tag
        Text(
            text = entry.tag.take(16),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF58A6FF),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.width(112.dp),
        )
        Spacer(Modifier.width(4.dp))
        // Message
        Text(
            text = entry.message,
            style = MaterialTheme.typography.labelSmall,
            color = when (entry.level) {
                ConsoleLevel.ERROR -> Color(0xFFF85149)
                ConsoleLevel.WARN  -> Color(0xFFD29922)
                else               -> Color(0xFFE6EDF3)
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}
