package com.airi.assistant.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ToolUsageCard — inline chat-bubble card shown when the agent executes a tool.
 *
 * Mimics the Manus / Claude Code tool-call display:
 *
 *  ┌─────────────────────────────────────────────────┐
 *  │  📂  read_file                          ✓ 43ms  │
 *  │  internal://notes.txt                           │
 *  │  ─────────────────────────────────────────────  │
 *  │  Hello from AIRI notes file...                  │
 *  └─────────────────────────────────────────────────┘
 *
 * Used in chat message items when a message has associated tool calls,
 * and in the AgentLogsScreen Traces tab.
 */

data class ToolUsage(
    val toolName:   String,
    val params:     Map<String, String> = emptyMap(),
    val output:     String             = "",
    val success:    Boolean            = true,
    val elapsedMs:  Long               = 0L,
)

@Composable
fun ToolUsageCard(
    usage:    ToolUsage,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (usage.success)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.errorContainer

    val bgColor = if (usage.success)
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    else
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Header row: icon + tool name + status + elapsed
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = toolIcon(usage.toolName),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = if (usage.success) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                )
                Text(
                    text = usage.toolName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (usage.elapsedMs > 0) {
                    Text(
                        text  = "${usage.elapsedMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                    )
                }
                Icon(
                    imageVector = if (usage.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (usage.success) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                )
            }
        }

        // Params row (compact key=value)
        if (usage.params.isNotEmpty()) {
            val paramLine = usage.params.entries
                .take(3)
                .joinToString("  ") { (k, v) -> "$k=${v.take(40)}" }
            Text(
                text  = paramLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Output (animated content)
        if (usage.output.isNotBlank()) {
            AnimatedContent(
                targetState = usage.output.take(300),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "tool_output",
            ) { text ->
                Text(
                    text  = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun toolIcon(toolName: String): ImageVector = when {
    toolName.contains("file") || toolName.contains("dir") ||
    toolName.contains("read") || toolName.contains("write") -> Icons.Filled.FolderOpen
    toolName.contains("exec") || toolName.contains("shell") ||
    toolName.contains("git") || toolName.contains("gradle") -> Icons.Filled.Terminal
    toolName.contains("http") || toolName.contains("url") ||
    toolName.contains("fetch") || toolName.contains("post") -> Icons.Filled.Http
    toolName.contains("memory") || toolName.contains("recall") ||
    toolName.contains("rag") -> Icons.Filled.Memory
    else -> Icons.Filled.Code
}
