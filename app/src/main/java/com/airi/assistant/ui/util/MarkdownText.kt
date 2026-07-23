package com.airi.assistant.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.AiriTheme

/**
 * Streaming-safe Markdown renderer. Zero external dependencies — uses only
 * Compose AnnotatedString. Supports the full set of formatting common in
 * AI chat responses:
 *
 *   **bold** / __bold__          *italic* / _italic_
 *   `inline code`                ~~strikethrough~~
 *   # H1 / ## H2 / ### H3       - bullet / * bullet / 1. numbered
 *   ```lang\ncode block\n```     --- horizontal rule
 *
 * Streaming safety: every unclosed delimiter is emitted as literal text so
 * partial mid-stream responses never produce corrupted rendering.
 *
 * Performance: all parsing and AnnotatedString construction is done inside
 * remember(rawText) — exactly zero extra work on recompositions that don't
 * change the text content.
 */
@Composable
fun MarkdownText(
    rawText     : String,
    modifier    : Modifier = Modifier,
    textColor   : Color    = AiriTheme.onSurface.copy(alpha = 0.93f),
    baseFontSp  : Float    = 15f,
    lineHeightSp: Float    = 23f
) {
    val codeBack  = Color(0xFF0D1118)
    val codeFore  = Color(0xFF79C0FF)
    val ruleColor = textColor.copy(alpha = 0.12f)

    val blocks = remember(rawText) {
        buildRenderBlocks(rawText, textColor, codeFore, baseFontSp)
    }

    Column(modifier = modifier) {
        blocks.forEachIndexed { idx, block ->
            when (block) {
                is RenderBlock.Prose -> {
                    if (block.topPad && idx > 0) Spacer(Modifier.height(6.dp))
                    Text(
                        text       = block.annotated,
                        fontSize   = block.fontSizeSp.sp,
                        lineHeight = (block.fontSizeSp * (lineHeightSp / baseFontSp)).sp,
                        fontWeight = block.fontWeight ?: FontWeight.Normal
                    )
                }
                is RenderBlock.Code -> {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(codeBack, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text       = block.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 13.sp,
                            lineHeight = 20.sp,
                            color      = codeFore
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                is RenderBlock.HRule -> {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(ruleColor)
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

private sealed class RenderBlock {
    data class Prose(
        val annotated  : AnnotatedString,
        val topPad     : Boolean     = false,
        val fontSizeSp : Float       = 15f,
        val fontWeight : FontWeight? = null
    ) : RenderBlock()
    data class Code(val text: String) : RenderBlock()
    object HRule : RenderBlock()
}

private fun buildRenderBlocks(
    text     : String,
    textColor: Color,
    codeFore : Color,
    baseFsp  : Float
): List<RenderBlock> {
    val out   = mutableListOf<RenderBlock>()
    val lines = text.split('\n')
    var i     = 0

    while (i < lines.size) {
        val line    = lines[i]
        val trimmed = line.trimStart()

        // Fenced code block — consume until closing ``` or EOF (streaming-safe)
        if (trimmed.startsWith("```")) {
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i]); i++
            }
            if (i < lines.size) i++
            out += RenderBlock.Code(codeLines.joinToString("\n"))
            continue
        }

        // Horizontal rule
        if (line.matches(Regex("^[-*_]{3,}\\s*$"))) {
            out += RenderBlock.HRule; i++; continue
        }

        // Heading  # / ## / ###
        val hm = Regex("^(#{1,6})\\s+(.+)").find(line)
        if (hm != null) {
            val level  = hm.groupValues[1].length
            val fsp    = when (level) { 1 -> baseFsp + 7f; 2 -> baseFsp + 4f; else -> baseFsp + 2f }
            val weight = if (level <= 2) FontWeight.Bold else FontWeight.SemiBold
            out += RenderBlock.Prose(
                annotated  = inlineAnnotated(hm.groupValues[2], textColor, codeFore),
                topPad     = out.isNotEmpty(),
                fontSizeSp = fsp,
                fontWeight = weight
            )
            i++; continue
        }

        // Bullet list item  - / * / +
        val bm = Regex("^(\\s*)[-*+]\\s+(.+)").find(line)
        if (bm != null) {
            val indent = (bm.groupValues[1].length / 2).coerceAtMost(3)
            out += RenderBlock.Prose(
                annotated = buildAnnotatedString {
                    withStyle(SpanStyle(color = textColor.copy(alpha = 0.42f))) {
                        append("${"  ".repeat(indent)}• ")
                    }
                    append(inlineAnnotated(bm.groupValues[2], textColor, codeFore))
                }
            )
            i++; continue
        }

        // Ordered list item  1. 2. 3. …
        val nm = Regex("^(\\s*)(\\d+)\\.\\s+(.+)").find(line)
        if (nm != null) {
            val indent = (nm.groupValues[1].length / 2).coerceAtMost(3)
            val num    = nm.groupValues[2]
            out += RenderBlock.Prose(
                annotated = buildAnnotatedString {
                    withStyle(SpanStyle(color = textColor.copy(alpha = 0.42f))) {
                        append("${"  ".repeat(indent)}$num. ")
                    }
                    append(inlineAnnotated(nm.groupValues[3], textColor, codeFore))
                }
            )
            i++; continue
        }

        // Blank line — skip
        if (line.isBlank()) { i++; continue }

        // Regular paragraph
        out += RenderBlock.Prose(annotated = inlineAnnotated(line, textColor, codeFore))
        i++
    }
    return out
}

private fun inlineAnnotated(text: String, textColor: Color, codeFore: Color): AnnotatedString =
    buildAnnotatedString {
        withStyle(SpanStyle(color = textColor)) {
            parseInline(text, this, codeFore)
        }
    }

private val boldSpan   = SpanStyle(fontWeight = FontWeight.Bold)
private val italicSpan = SpanStyle(fontStyle  = FontStyle.Italic)
private val strikeSpan = SpanStyle(textDecoration = TextDecoration.LineThrough)

private fun parseInline(text: String, out: AnnotatedString.Builder, codeFore: Color) {
    val codeSpan = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = Color(0xFF1A1F2E),
        color      = codeFore,
        fontSize   = 13.sp
    )
    var i = 0
    while (i < text.length) {

        // Bold: **text** or __text__
        if (i + 1 < text.length &&
            ((text[i] == '*' && text[i + 1] == '*') || (text[i] == '_' && text[i + 1] == '_'))) {
            val delim = text.substring(i, i + 2)
            val end   = text.indexOf(delim, i + 2)
            if (end > i + 2) {
                out.withStyle(boldSpan) {
                    parseInline(text.substring(i + 2, end), this, codeFore)
                }
                i = end + 2; continue
            }
        }

        // Italic: *text* or _text_ (guarded against ** and __ already consumed above)
        if ((text[i] == '*' || text[i] == '_') &&
            (i + 1 < text.length && text[i + 1] != text[i]) &&
            (i == 0 || text[i - 1] != text[i])) {
            val delim = text[i].toString()
            val end   = text.indexOf(delim, i + 1)
            if (end > i + 1 && (end + 1 >= text.length || text[end + 1] != text[i])) {
                out.withStyle(italicSpan) {
                    parseInline(text.substring(i + 1, end), this, codeFore)
                }
                i = end + 1; continue
            }
        }

        // Inline code: `code`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                out.withStyle(codeSpan) { append(text.substring(i + 1, end)) }
                i = end + 1; continue
            }
        }

        // Strikethrough: ~~text~~
        if (i + 1 < text.length && text[i] == '~' && text[i + 1] == '~') {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                out.withStyle(strikeSpan) {
                    parseInline(text.substring(i + 2, end), this, codeFore)
                }
                i = end + 2; continue
            }
        }

        // Literal character
        out.append(text[i])
        i++
    }
}
