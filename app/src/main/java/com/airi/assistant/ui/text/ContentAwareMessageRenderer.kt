package com.airi.assistant.ui.text

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    toolOutputs: List<AssistantToolOutput> = emptyList(),
    onContentAction: (ContentTarget, ContentUnitAction) -> Unit,
) {
    val resolvedMessageId = messageId.ifBlank {
        remember { "message-${text.hashCode().toUInt().toString(16)}" }
    }
    val contentSession = remember(resolvedMessageId) { AdaptiveContentSession(resolvedMessageId) }
    val plan = remember(text, isStreaming, resolvedMessageId, toolOutputs) {
        contentSession.plan(text, isStreaming, toolOutputs)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        plan.units.forEach { unit ->
            key(unit.id) {
                val target = unit.toContentTarget(plan.messageId)
                when (unit.kind) {
                    ContentUnitKind.PROSE -> BidiAwareMarkdownRenderer(
                        text = unit.source,
                        modifier = Modifier.fillMaxWidth(),
                        isStreaming = isStreaming,
                    )
                    ContentUnitKind.CODE,
                    ContentUnitKind.STRUCTURED_DATA -> BidiAwareMarkdownRenderer(
                        text = fencedUnit(unit),
                        modifier = Modifier.fillMaxWidth(),
                        isStreaming = isStreaming,
                        codeTargets = listOf(target),
                        onContentAction = onContentAction,
                    )
                    ContentUnitKind.TABLE -> MarkdownTableRenderer(
                        text = unit.source,
                        modifier = Modifier.fillMaxWidth(),
                        tableTarget = target,
                        onContentAction = onContentAction,
                    )
                }
            }
        }
        plan.toolOutputs.forEach { output ->
            key(output.id) {
                ToolOutputCard(
                    output = output,
                    target = output.toContentTarget(plan.messageId),
                    onContentAction = onContentAction,
                )
            }
        }
    }
}

@Composable
private fun ToolOutputCard(
    output: AssistantToolOutput,
    target: ContentTarget.ToolOutput,
    onContentAction: (ContentTarget, ContentUnitAction) -> Unit,
) {
    var expanded by rememberSaveable(output.id) { mutableStateOf(false) }
    val canExpand = target.canPerform(ContentUnitAction.EXPAND)
    if (!canExpand) return

    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        color = AiriTheme.surfaceVariant,
        shape = AIRIShapes.sm,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.assistant_tool_output_label, output.toolName, output.step),
                    color = AiriTheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        if (target.canPerform(ContentUnitAction.EXPAND)) {
                            onContentAction(target, ContentUnitAction.EXPAND)
                            expanded = !expanded
                        }
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.assistant_tool_output_hide else R.string.assistant_tool_output_show,
                        ),
                        tint = AiriTheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        text = if (output.truncated) "${output.text}\n…" else output.text,
                        color = AiriTheme.onSurface,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
}

private fun fencedUnit(unit: AssistantContentUnit): String {
    val language = unit.language?.takeIf { it.isNotBlank() } ?: "text"
    val opening = "```$language\n"
    return if (unit.complete) "$opening${unit.source}\n```" else "$opening${unit.source}"
}

@Composable
private fun MarkdownTableRenderer(
    text: String,
    modifier: Modifier = Modifier,
    tableTarget: ContentTarget.Block,
    onContentAction: (ContentTarget, ContentUnitAction) -> Unit,
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
        if (tableTarget.canPerform(ContentUnitAction.COPY) || tableTarget.canPerform(ContentUnitAction.SHARE)) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (tableTarget.canPerform(ContentUnitAction.COPY)) {
                    IconButton(
                        onClick = { onContentAction(tableTarget, ContentUnitAction.COPY) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_table), tint = AiriTheme.onSurfaceVariant)
                    }
                }
                if (tableTarget.canPerform(ContentUnitAction.SHARE)) {
                    IconButton(
                        onClick = { onContentAction(tableTarget, ContentUnitAction.SHARE) },
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
