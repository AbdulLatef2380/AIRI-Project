package com.airi.assistant.ui.text

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import com.airi.assistant.ui.util.MarkdownText

/**
 * BidiAwareMarkdownRenderer — renders markdown text with correct BiDi isolation.
 *
 * Key behaviours:
 *  - Code blocks (```` ``` ```` and `` ` ``) are always rendered LTR regardless
 *    of surrounding text direction (code is always LTR)
 *  - Arabic prose surrounding inline English words gets LTR isolation marks
 *    injected via [LanguageRuntimeManager.isolateLatinRuns]
 *  - The outer [DirectionalContent] wrapper sets the correct LayoutDirection
 *    so Compose layout (alignment, icon mirroring) matches the text
 *  - Streaming text is stable: direction is re-derived on each recomposition
 *    but only the final AnnotatedString allocation is expensive
 */
@Composable
fun BidiAwareMarkdownRenderer(
    text: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false
) {
    val direction = remember(text.take(80)) {   // key on first 80 chars — stable during streaming
        LanguageRuntimeManager.analyseDirection(text)
    }
    val layoutDir = LanguageRuntimeManager.toLayoutDirection(direction)

    // Process text: isolate inline Latin runs for RTL/mixed content
    val processedText = remember(text) {
        when (direction) {
            LanguageRuntimeManager.DominantDirection.RTL,
            LanguageRuntimeManager.DominantDirection.MIXED  -> LanguageRuntimeManager.isolateLatinRuns(text)
            else                                             -> text
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        MarkdownText(
            markdown = processedText,
            modifier = modifier.fillMaxWidth()
        )
    }
}

/**
 * MixedLanguageTextFormatter — formats an [AnnotatedString] with proper
 * span styles for code, bold, italic across BiDi boundaries.
 *
 * Used for inline rendering where MarkdownText overhead is too high
 * (e.g., chat message title spans).
 */
object MixedLanguageTextFormatter {

    fun format(raw: String): AnnotatedString = buildAnnotatedString {
        var cursor = 0
        val codeBlockRegex = Regex("```[\\s\\S]*?```|`[^`]+`")
        val boldRegex      = Regex("\\*\\*[^*]+\\*\\*")
        val italicRegex    = Regex("\\*[^*]+\\*")

        val matches = (codeBlockRegex.findAll(raw) + boldRegex.findAll(raw) + italicRegex.findAll(raw))
            .sortedBy { it.range.first }

        for (match in matches) {
            if (match.range.first > cursor) {
                val segment = raw.substring(cursor, match.range.first)
                append(LanguageRuntimeManager.isolateLatinRuns(segment))
            }
            val inner = match.value
            when {
                inner.startsWith("```") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    append(inner.removeSurrounding("```").trim())
                }
                inner.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    append(inner.removeSurrounding("`"))
                }
                inner.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(inner.removeSurrounding("**"))
                }
                inner.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(inner.removeSurrounding("*"))
                }
                else -> append(inner)
            }
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) append(LanguageRuntimeManager.isolateLatinRuns(raw.substring(cursor)))
    }
}
