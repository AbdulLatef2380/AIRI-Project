package com.airi.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AgentTimeline — scrollable step-by-step execution history composable.
 *
 * Renders a vertical timeline of [TimelineEntry] items, each representing
 * one executed plan step.  Mimics the Manus / Claude Code style:
 *
 *   ✓  Reading build.gradle.kts
 *   ✓  Locating failing dependency
 *   ●  Patching configuration         ← current (pulsing dot)
 *   ○  Retrying build
 *
 * The timeline auto-scrolls to the last item when a new entry is added.
 * It is designed to sit inside a bottomSheet or a dedicated AgentLogsScreen tab.
 */

enum class TimelineEntryStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
}

data class TimelineEntry(
    val id:          String,
    val action:      String,
    val label:       String,
    val status:      TimelineEntryStatus = TimelineEntryStatus.PENDING,
    val detail:      String              = "",
    val elapsedMs:   Long               = 0L,
)

@Composable
fun AgentTimeline(
    entries:  List<TimelineEntry>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    if (entries.isEmpty()) {
        Box(
            modifier           = modifier.fillMaxWidth().padding(vertical = 24.dp),
            contentAlignment   = Alignment.Center,
        ) {
            Text(
                "No steps executed yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        return
    }

    LazyColumn(
        state       = listState,
        modifier    = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(entries, key = { it.id }) { entry ->
            TimelineRow(entry = entry, isLast = entry == entries.last())
        }
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntry, isLast: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Connector line + status dot column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(24.dp),
        ) {
            StatusDot(entry.status)
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(28.dp)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        // Content
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = actionIcon(entry.action),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint     = actionIconColor(entry.status),
                )
                Text(
                    text  = entry.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (entry.status == TimelineEntryStatus.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
                    color = when (entry.status) {
                        TimelineEntryStatus.SUCCESS  -> MaterialTheme.colorScheme.onSurface
                        TimelineEntryStatus.FAILED   -> MaterialTheme.colorScheme.error
                        TimelineEntryStatus.RUNNING  -> MaterialTheme.colorScheme.primary
                        TimelineEntryStatus.SKIPPED  -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        TimelineEntryStatus.PENDING  -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.elapsedMs > 0) {
                    Text(
                        text  = "${entry.elapsedMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        fontSize = 10.sp,
                    )
                }
            }

            AnimatedVisibility(
                visible = entry.detail.isNotBlank() && entry.status != TimelineEntryStatus.PENDING,
                enter   = fadeIn(tween(150)) + expandVertically(tween(200)),
                exit    = fadeOut(tween(100)) + shrinkVertically(tween(150)),
            ) {
                Text(
                    text  = entry.detail.take(120),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusDot(status: TimelineEntryStatus) {
    val dotSize = 16.dp
    when (status) {
        TimelineEntryStatus.SUCCESS -> Box(
            modifier         = Modifier.size(dotSize).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(10.dp))
        }
        TimelineEntryStatus.FAILED -> Box(
            modifier         = Modifier.size(dotSize).clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(10.dp))
        }
        TimelineEntryStatus.RUNNING -> Box(
            modifier         = Modifier.size(dotSize).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(10.dp))
        }
        TimelineEntryStatus.SKIPPED -> Box(
            modifier = Modifier.size(dotSize).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {}
        TimelineEntryStatus.PENDING -> Box(
            modifier = Modifier.size(dotSize).clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        ) {}
    }
}

@Composable
private fun actionIconColor(status: TimelineEntryStatus): Color = when (status) {
    TimelineEntryStatus.SUCCESS -> MaterialTheme.colorScheme.primary
    TimelineEntryStatus.FAILED  -> MaterialTheme.colorScheme.error
    TimelineEntryStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
    else                        -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
}

private fun actionIcon(action: String): ImageVector = when {
    action.contains("file") || action.contains("dir") || action.contains("read") ||
    action.contains("write") || action.contains("list") -> Icons.Filled.FolderOpen
    action.contains("exec") || action.contains("shell") ||
    action.contains("gradle") || action.contains("git") -> Icons.Filled.Terminal
    action.contains("http") || action.contains("url") ||
    action.contains("fetch") || action.contains("post") -> Icons.Filled.Http
    action.contains("memory") || action.contains("recall") ||
    action.contains("rag") || action.contains("retrieve") -> Icons.Filled.Memory
    action.contains("observe") || action.contains("logcat") ||
    action.contains("log") -> Icons.Filled.Visibility
    action.contains("code") || action.contains("analyze") -> Icons.Filled.Code
    else -> Icons.Filled.Code
}
