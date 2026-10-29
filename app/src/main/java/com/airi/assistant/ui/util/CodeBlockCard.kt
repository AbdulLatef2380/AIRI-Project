package com.airi.assistant.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.text.selection.SelectionContainer
import com.airi.assistant.R
import com.airi.assistant.ui.text.ContentTarget
import com.airi.assistant.ui.text.ContentUnitAction
import com.airi.assistant.ui.text.canPerform
import com.airi.assistant.ui.theme.AiriTheme

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
    val highlightedCode = remember(code, language) {
        CodeSyntaxHighlighter.highlight(code, language)
    }
    var expanded by rememberSaveable(target?.messageId, target?.blockId) { mutableStateOf(false) }
    fun copy() {
        val scopedTarget = target
        if (scopedTarget != null) {
            if (scopedTarget.canPerform(ContentUnitAction.COPY)) {
                onContentAction?.invoke(scopedTarget, ContentUnitAction.COPY)
            }
        } else {
            onCopyCode?.invoke(code)
        }
    }

    fun share() {
        val scopedTarget = target ?: return
        if (scopedTarget.canPerform(ContentUnitAction.SHARE)) onContentAction?.invoke(scopedTarget, ContentUnitAction.SHARE)
    }

    fun expand() {
        if (target != null) {
            if (!target.canPerform(ContentUnitAction.EXPAND) || onContentAction == null) return
            onContentAction(target, ContentUnitAction.EXPAND)
        }
        expanded = true
    }

    fun save() {
        val block = target ?: return
        if (block.canPerform(ContentUnitAction.SAVE)) onContentAction?.invoke(block, ContentUnitAction.SAVE)
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(shape = RoundedCornerShape(10.dp), color = codeBack, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(language.ifBlank { "text" }, color = codeFore, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    if ((target?.canPerform(ContentUnitAction.COPY) == true && onContentAction != null) || (target == null && onCopyCode != null)) {
                        IconButton(onClick = ::copy, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = context.getString(R.string.copy_code), tint = AiriTheme.onSurfaceVariant)
                        }
                    }
                    if (target != null && target.canPerform(ContentUnitAction.EXPAND) && onContentAction != null) {
                        IconButton(onClick = ::expand, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.OpenInFull, contentDescription = context.getString(R.string.artifact_expand), tint = AiriTheme.onSurfaceVariant)
                        }
                    }
                    if (target != null && target.canPerform(ContentUnitAction.SHARE) && onContentAction != null) {
                        IconButton(onClick = ::share, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.Share, contentDescription = context.getString(R.string.artifact_share), tint = AiriTheme.onSurfaceVariant)
                        }
                    }
                    if (target != null && target.canPerform(ContentUnitAction.SAVE) && onContentAction != null) {
                        IconButton(onClick = ::save, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.FileDownload, contentDescription = context.getString(R.string.artifact_save), tint = AiriTheme.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Text(
                        text = highlightedCode,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = CodeSyntaxHighlighter.defaultColor, textDirection = TextDirection.Ltr),
                        softWrap = false,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }

    if (expanded) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(modifier = Modifier.fillMaxWidth().padding(14.dp), shape = RoundedCornerShape(14.dp), color = codeBack, tonalElevation = 8.dp) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(language.ifBlank { "text" }, color = codeFore, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            IconButton(onClick = { expanded = false }) {
                                Icon(Icons.Outlined.Close, contentDescription = context.getString(R.string.close), tint = AiriTheme.onSurfaceVariant)
                            }
                        }
                        SelectionContainer {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                Text(
                                    text = highlightedCode,
                                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = CodeSyntaxHighlighter.defaultColor, textDirection = TextDirection.Ltr),
                                    softWrap = false,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
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
