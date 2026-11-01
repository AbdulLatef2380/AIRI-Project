package com.airi.assistant.ui.util

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
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
        if (scopedTarget.canPerform(ContentUnitAction.SHARE)) {
            onContentAction?.invoke(scopedTarget, ContentUnitAction.SHARE)
        }
    }

    fun save() {
        val block = target ?: return
        if (block.canPerform(ContentUnitAction.SAVE)) {
            onContentAction?.invoke(block, ContentUnitAction.SAVE)
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
                    if ((target?.canPerform(ContentUnitAction.COPY) == true && onContentAction != null) || (target == null && onCopyCode != null)) {
                        IconButton(onClick = ::copy, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = context.getString(R.string.copy_code), tint = AiriTheme.onSurfaceVariant)
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
                Text(
                    text = highlightedCode,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = CodeSyntaxHighlighter.defaultColor,
                        textDirection = TextDirection.Ltr,
                    ),
                    softWrap = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                )
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
