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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme

/** Routes assistant content to shared presentation primitives across all providers. */
@Composable
fun ContentAwareMessageRenderer(
    text: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    onCopyCode: ((String) -> Unit)? = null,
) {
    val analysis = remember(text, isStreaming) { AssistantContentClassifier.analyse(text, isStreaming) }
    when (analysis.kind) {
        AssistantContentKind.TABLE -> MarkdownTableRenderer(text, modifier)
        AssistantContentKind.STRUCTURED_CODE -> BidiAwareMarkdownRenderer(
            text = "```text\n${text.trim()}\n```",
            modifier = modifier,
            isStreaming = isStreaming,
            onCopyCode = onCopyCode,
        )
        AssistantContentKind.CODE_BLOCK -> BidiAwareMarkdownRenderer(
            text = text,
            modifier = modifier,
            isStreaming = isStreaming,
            onCopyCode = onCopyCode,
        )
        else -> BidiAwareMarkdownRenderer(
            text = text,
            modifier = modifier,
            isStreaming = isStreaming,
            onCopyCode = onCopyCode,
        )
    }
}

@Composable
private fun MarkdownTableRenderer(text: String, modifier: Modifier = Modifier) {
    val rows = remember(text) { parseTable(text) }
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
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
}

private fun parseTable(text: String): List<List<String>> = text.lines()
    .map(String::trim)
    .filter { it.contains('|') && it.trim('|', ' ', '\t').isNotEmpty() }
    .filterNot { it.matches(Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$")) }
    .map { line -> line.trim('|').split('|').map(String::trim) }
