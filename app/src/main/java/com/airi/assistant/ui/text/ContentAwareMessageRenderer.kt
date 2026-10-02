package com.airi.assistant.ui.text

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme

/** Routes assistant content to the existing specialized presentation primitives. */
@Composable
fun ContentAwareMessageRenderer(
    text: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    onCopyCode: (String) -> Unit = {},
) {
    val analysis = remember(text, isStreaming) { AssistantContentClassifier.analyse(text, isStreaming) }
    when (analysis.kind) {
        AssistantContentKind.TABLE -> MarkdownTableRenderer(text, modifier)
        AssistantContentKind.STRUCTURED_CODE -> BidiAwareMarkdownRenderer(
            text = "```text\n${text.trim()}\n```",
            modifier = modifier,
            isStreaming = isStreaming,
        )
        AssistantContentKind.CODE_BLOCK -> CodeAwareMarkdownRenderer(text, modifier, analysis, onCopyCode)
        else -> BidiAwareMarkdownRenderer(text = text, modifier = modifier, isStreaming = isStreaming)
    }
}

@Composable
private fun CodeAwareMarkdownRenderer(
    text: String,
    modifier: Modifier,
    analysis: AssistantContentAnalysis,
    onCopyCode: (String) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val blocks = analysis.codeBlocks
        val codeOnly = text.trim().startsWith("```") && text.trim().endsWith("```")
        if (blocks.isEmpty() || !codeOnly) {
            BidiAwareMarkdownRenderer(text = text, modifier = Modifier.fillMaxWidth())
        } else {
            blocks.forEach { block ->
                CodeCard(block, onCopyCode)
            }
        }
    }
}

@Composable
private fun CodeCard(block: AssistantCodeBlock, onCopyCode: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AIRIShapes.md,
        color = Color(0xFF0D1118),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = block.language,
                    color = Color(0xFF79C0FF),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onCopyCode(block.code) },
                    modifier = Modifier.semantics { contentDescription = "Copy code" },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy code", tint = Color(0xFFB8C7D9))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = block.code,
                    color = Color(0xFFE6EDF3),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MarkdownTableRenderer(text: String, modifier: Modifier = Modifier) {
    val rows = remember(text) { parseTable(text) }
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            rows.forEachIndexed { index, cells ->
                Surface(color = if (index == 0) AiriTheme.surfaceVariant else AiriTheme.surface, shape = AIRIShapes.sm) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        cells.forEachIndexed { cellIndex, cell ->
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
}

private fun parseTable(text: String): List<List<String>> = text.lines()
    .map(String::trim)
    .filter { it.contains('|') && it.trim('|', ' ', '\t').isNotEmpty() }
    .filterNot { it.matches(Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$")) }
    .map { line -> line.trim('|').split('|').map(String::trim) }
