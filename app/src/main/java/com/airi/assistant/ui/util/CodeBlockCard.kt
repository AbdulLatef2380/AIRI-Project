package com.airi.assistant.ui.util

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import com.airi.assistant.R
import com.airi.assistant.ui.text.ContentTarget
import com.airi.assistant.ui.text.ContentUnitAction
import com.airi.assistant.ui.text.canPerform
import com.airi.assistant.ui.theme.AiriTheme
import kotlinx.coroutines.delay

@Composable
fun CodeBlockCard(
    language: String,
    code: String,
    codeBack: Color,
    codeFore: Color,
    onCopyCode: ((String) -> Unit)?,
    target: ContentTarget.Block? = null,
    onContentAction: ((ContentTarget, ContentUnitAction) -> Unit)? = null,
) {
    val context = LocalContext.current
    val syntaxPalette = remember(codeBack) { CodeSyntaxHighlighter.palette(codeBack) }
    val highlightedCode = remember(code, language, syntaxPalette) {
        CodeSyntaxHighlighter.highlight(code, language, syntaxPalette)
    }
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val canCollapse = lineCount > 14
    var expanded by rememberSaveable(target?.blockId ?: "code-${code.hashCode()}") { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    fun copy() {
        val scopedTarget = target
        if (scopedTarget != null) {
            if (scopedTarget.canPerform(ContentUnitAction.COPY)) {
                onContentAction?.invoke(scopedTarget, ContentUnitAction.COPY)
                copied = true
            }
        } else {
            if (onCopyCode != null) {
                onCopyCode(code)
                copied = true
            }
        }
    }

    fun share() {
        val scopedTarget = target ?: return
        if (scopedTarget.canPerform(ContentUnitAction.SHARE)) {
            onContentAction?.invoke(scopedTarget, ContentUnitAction.SHARE)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = codeBack,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onDoubleClick = {
                        if (target?.canPerform(ContentUnitAction.FULLSCREEN) == true) {
                            onContentAction?.invoke(target, ContentUnitAction.FULLSCREEN)
                        }
                    },
                    onLongClick = {},
                ),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        language.ifBlank { "text" },
                        color = codeFore,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = context.resources.getQuantityString(R.plurals.code_line_count, lineCount, lineCount),
                        color = AiriTheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (canCollapse) {
                        IconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = context.getString(
                                    if (expanded) R.string.collapse_code else R.string.expand_code,
                                ),
                                tint = AiriTheme.onSurfaceVariant,
                            )
                        }
                    }
                    if ((target?.canPerform(ContentUnitAction.COPY) == true && onContentAction != null) || (target == null && onCopyCode != null)) {
                        IconButton(onClick = ::copy, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                contentDescription = context.getString(
                                    if (copied) R.string.code_copied else R.string.copy_code,
                                ),
                                tint = if (copied) AiriTheme.primary else AiriTheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (target != null && target.canPerform(ContentUnitAction.SHARE) && onContentAction != null) {
                        IconButton(onClick = ::share, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.Share, contentDescription = context.getString(R.string.artifact_share), tint = AiriTheme.onSurfaceVariant)
                        }
                    }
                }
                SelectionContainer {
                    Text(
                        text = highlightedCode,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = codeFore,
                            textDirection = TextDirection.Ltr,
                        ),
                        softWrap = true,
                        maxLines = if (canCollapse && !expanded) 14 else Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

private fun languageExtension(language: String): String = when (language.trim().lowercase()) {
    "kotlin" -> "kt"
    "python", "py" -> "py"
    "javascript", "js" -> "js"
    "typescript", "ts" -> "ts"
    "json" -> "json"
    "yaml", "yml" -> "yaml"
    "xml" -> "xml"
    "sql" -> "sql"
    "bash", "sh", "shell" -> "sh"
    "html" -> "html"
    "css" -> "css"
    else -> "txt"
}
