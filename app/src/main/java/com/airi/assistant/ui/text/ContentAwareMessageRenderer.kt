package com.airi.assistant.ui.text

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme

/** Routes assistant content to shared presentation primitives across all providers. */
@Composable
fun ContentAwareMessageRenderer(
    text: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    messageId: String = "",
    onCopyCode: ((String) -> Unit)? = null,
    onCopyTable: ((String) -> Unit)? = null,
    onShareTable: ((String) -> Unit)? = null,
    onContentAction: ((ContentTarget, ContentUnitAction) -> Unit)? = null,
) {
    val plan = remember(text, isStreaming, messageId) {
        AdaptiveContentPolicy.plan(text, isStreaming, messageId = messageId.ifBlank { "message-${text.hashCode().toUInt().toString(16)}" })
    }
    val analysis = remember(text, isStreaming) { AssistantContentClassifier.analyse(text, isStreaming) }
    val codeTargets = plan.units
        .filter { it.kind == ContentUnitKind.CODE || it.kind == ContentUnitKind.STRUCTURED_DATA }
        .map { it.toContentTarget(plan.messageId) }
    when (analysis.kind) {
        AssistantContentKind.TABLE -> MarkdownTableRenderer(
            text = text,
            modifier = modifier,
            onCopyTable = onCopyTable,
            onShareTable = onShareTable,
            tableTarget = plan.units.firstOrNull { it.kind == ContentUnitKind.TABLE }?.toContentTarget(plan.messageId),
            onContentAction = onContentAction,
        )
        AssistantContentKind.STRUCTURED_CODE -> BidiAwareMarkdownRenderer(
            text = "```text\n${text.trim()}\n```",
            modifier = modifier,
            isStreaming = isStreaming,
            onCopyCode = onCopyCode,
            codeTargets = codeTargets,
            onContentAction = onContentAction,
        )
        AssistantContentKind.CODE_BLOCK -> BidiAwareMarkdownRenderer(
            text = text,
            modifier = modifier,
            isStreaming = isStreaming,
            onCopyCode = onCopyCode,
            codeTargets = codeTargets,
            onContentAction = onContentAction,
        )
        else -> BidiAwareMarkdownRenderer(
            text = text,
            modifier = modifier,
            isStreaming = isStreaming,
            onCopyCode = onCopyCode,
            codeTargets = codeTargets,
            onContentAction = onContentAction,
        )
    }
}

@Composable
private fun MarkdownTableRenderer(
    text: String,
    modifier: Modifier = Modifier,
    onCopyTable: ((String) -> Unit)? = null,
    onShareTable: ((String) -> Unit)? = null,
    tableTarget: ContentTarget.Block? = null,
    onContentAction: ((ContentTarget, ContentUnitAction) -> Unit)? = null,
) {
    val tableBounds = remember(text) { findTableBounds(text) }
    val rows = remember(tableBounds.table) { AdaptiveContentPolicy.parseMarkdownTable(tableBounds.table) }
    Column(modifier.fillMaxWidth()) {
        tableBounds.before.takeIf(String::isNotBlank)?.let { prose ->
            BidiAwareMarkdownRenderer(text = prose, fillWidth = false, onCopyCode = null)
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                rows.forEachIndexed { index, cells ->
                    Surface(
                        color = if (index == 0) AiriTheme.surfaceVariant else AiriTheme.surface,
                        shape = AIRIShapes.sm,
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                            cells.forEach { cell ->
                                Text(
                                    text = cell,
                                    color = AiriTheme.onSurface,
                                    fontSize = if (index == 0) 12.sp else 11.sp,
                                    modifier = Modifier.width(140.dp).padding(end = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (onCopyTable != null || onShareTable != null || onContentAction != null) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (onCopyTable != null || onContentAction != null) {
                    IconButton(
                        onClick = {
                            if (tableTarget != null && onContentAction != null) {
                                onContentAction(tableTarget, ContentUnitAction.COPY)
                            } else {
                                onCopyTable?.invoke(AdaptiveContentPolicy.tableClipboardText(tableBounds.table))
                            }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_table), tint = AiriTheme.onSurfaceVariant)
                    }
                }
                if (onShareTable != null || onContentAction != null) {
                    IconButton(
                        onClick = {
                            if (tableTarget != null && onContentAction != null) {
                                onContentAction(tableTarget, ContentUnitAction.SHARE)
                            } else {
                                onShareTable?.invoke(AdaptiveContentPolicy.tableShareText(tableBounds.table))
                            }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share_table), tint = AiriTheme.onSurfaceVariant)
                    }
                }
            }
        }
        tableBounds.after.takeIf(String::isNotBlank)?.let { prose ->
            BidiAwareMarkdownRenderer(text = prose, fillWidth = false, onCopyCode = null)
        }
    }
}

private data class TableBounds(val before: String, val table: String, val after: String)

private fun findTableBounds(text: String): TableBounds {
    val lines = text.lines()
    val separator = lines.indexOfFirst { it.trim().matches(TABLE_SEPARATOR) }
    if (separator <= 0) return TableBounds("", text, "")
    var start = separator - 1
    while (start > 0 && lines[start - 1].contains('|')) start--
    var end = separator
    while (end + 1 < lines.size && lines[end + 1].contains('|')) end++
    return TableBounds(
        before = lines.subList(0, start).joinToString("\n"),
        table = lines.subList(start, end + 1).joinToString("\n"),
        after = lines.subList(end + 1, lines.size).joinToString("\n"),
    )
}

private val TABLE_SEPARATOR = Regex(
    "^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$",
)
