package com.airi.assistant.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.run.code.CodeSpan
import com.airi.assistant.ui.run.code.DiffKind
import com.airi.assistant.ui.run.code.DiffRow
import com.airi.assistant.ui.run.code.DiffView
import com.airi.assistant.ui.run.code.LineDiff
import com.airi.assistant.ui.run.code.SyntaxHighlighter
import com.airi.assistant.ui.run.code.TokenKind
import com.airi.assistant.ui.theme.AiriTheme

/*
 * Part 5 §4: read-only code viewer (syntax colors, line numbers) and diff viewer. Code is always laid
 * out left-to-right, also in Arabic, because source text does not mirror.
 */

/** Rendering cap: the sheet is not lazy, so very long files show their first lines only. */
internal const val CODE_UI_MAX_LINES = 1_200

/** Same reason, for the Diff tab and the replay bar. */
internal const val DIFF_UI_MAX_ROWS = 600

internal data class CodePalette(val keyword: Color, val string: Color, val comment: Color, val number: Color) {
    fun colorOf(kind: TokenKind): Color = when (kind) {
        TokenKind.KEYWORD -> keyword
        TokenKind.STRING -> string
        TokenKind.COMMENT -> comment
        TokenKind.NUMBER -> number
    }
}

private val DarkPalette = CodePalette(Color(0xFFC792EA), Color(0xFFA5D6A7), Color(0xFF8A94A6), Color(0xFFFFB86B))
private val LightPalette = CodePalette(Color(0xFF7B3FA0), Color(0xFF1B7F45), Color(0xFF6B7280), Color(0xFFB45309))

@Composable
internal fun codePalette(): CodePalette = if (AiriTheme.background.luminance() < 0.5f) DarkPalette else LightPalette

/** Joins spans back into one styled string; spans are non-overlapping and ascending per line. */
internal fun annotateCode(lines: List<String>, spans: List<List<CodeSpan>>, palette: CodePalette): AnnotatedString =
    buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            var position = 0
            spans.getOrElse(index) { emptyList() }.forEach { span ->
                val start = span.start.coerceIn(position, line.length)
                val end = span.end.coerceIn(start, line.length)
                if (start > position) append(line.substring(position, start))
                if (end > start) withStyle(SpanStyle(color = palette.colorOf(span.kind))) { append(line.substring(start, end)) }
                position = end
            }
            if (position < line.length) append(line.substring(position))
            if (index < lines.lastIndex) append('\n')
        }
    }

@Composable
internal fun HighlightedCode(text: String, fileName: String, modifier: Modifier = Modifier) {
    val allLines = remember(text) { LineDiff.splitLines(text) }
    val lines = remember(allLines) { if (allLines.size > CODE_UI_MAX_LINES) allLines.take(CODE_UI_MAX_LINES) else allLines }
    val palette = codePalette()
    val annotated = remember(lines, fileName, palette) {
        annotateCode(lines, SyntaxHighlighter.highlight(lines, SyntaxHighlighter.languageFor(fileName)), palette)
    }
    val gutter = remember(lines) { (1..lines.size).joinToString("\n") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    gutter,
                    color = AiriTheme.outline, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End, softWrap = false,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Text(
                    annotated,
                    color = AiriTheme.onBackground, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
                    softWrap = false,
                )
            }
        }
        if (allLines.size > lines.size) {
            Text(stringResource(R.string.run_code_truncated, CODE_UI_MAX_LINES), color = AiriTheme.outline, fontSize = 11.sp)
        }
    }
}

private val AddedTint = Color(0xFF2E9E6B)
private val RemovedTint = Color(0xFFFF5252)

@Composable
internal fun CodeDiffViewer(view: DiffView, modifier: Modifier = Modifier) {
    if (!view.hasChanges) {
        StatusText(R.string.run_code_no_changes)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // "+3 −2": symbols and numbers only, the same in every language.
        val stats = "+${view.added}  −${view.removed}"
        Text(stats, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            view.rows.forEach { row ->
                when (row) {
                    is DiffRow.Skipped -> Text(
                        pluralStringResource(R.plurals.run_code_unchanged_lines, row.count, row.count),
                        color = AiriTheme.outline, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        textAlign = TextAlign.Center,
                    )
                    is DiffRow.Line -> {
                        val tint = when (row.line.kind) {
                            DiffKind.ADDED -> AddedTint
                            DiffKind.REMOVED -> RemovedTint
                            DiffKind.SAME -> Color.Transparent
                        }
                        val marker = when (row.line.kind) {
                            DiffKind.ADDED -> "+"
                            DiffKind.REMOVED -> "−"
                            DiffKind.SAME -> " "
                        }
                        Row(Modifier.fillMaxWidth().background(tint.copy(alpha = if (tint == Color.Transparent) 0f else 0.16f))) {
                            Text(
                                marker,
                                color = if (tint == Color.Transparent) AiriTheme.outline else tint,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(16.dp).padding(start = 3.dp),
                            )
                            Text(
                                row.line.text,
                                color = AiriTheme.onBackground, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        if (view.truncated) {
            Text(stringResource(R.string.run_code_diff_truncated), color = AiriTheme.outline, fontSize = 11.sp)
        }
    }
}
