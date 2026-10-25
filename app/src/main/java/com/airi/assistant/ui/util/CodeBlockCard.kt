package com.airi.assistant.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.airi.assistant.ui.theme.AiriTheme

@Composable
fun CodeBlockCard(
    language: String,
    code: String,
    codeBack: Color,
    codeFore: Color,
    onCopyCode: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(code.toByteArray(Charsets.UTF_8)) }
                    ?: error("No output stream")
                android.widget.Toast.makeText(context, R.string.artifact_saved, android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(context, R.string.artifact_save_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun copy() {
        onCopyCode?.invoke(code) ?: run {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("AIRI code", code))
        }
        android.widget.Toast.makeText(context, R.string.artifact_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, code)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.artifact_share))) }
            .onFailure { android.widget.Toast.makeText(context, R.string.artifact_share_failed, android.widget.Toast.LENGTH_SHORT).show() }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(shape = RoundedCornerShape(10.dp), color = codeBack, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(language.ifBlank { "text" }, color = codeFore, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    IconButton(onClick = ::copy, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = context.getString(R.string.copy_code), tint = AiriTheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { expanded = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.OpenInFull, contentDescription = context.getString(R.string.artifact_expand), tint = AiriTheme.onSurfaceVariant)
                    }
                    IconButton(onClick = ::share, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Share, contentDescription = context.getString(R.string.artifact_share), tint = AiriTheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { saveLauncher.launch("airi-${language.ifBlank { "text" }.lowercase()}.${languageExtension(language)}") }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Save, contentDescription = context.getString(R.string.artifact_save), tint = AiriTheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Text(
                        text = CodeSyntaxHighlighter.highlight(code, language),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = CodeSyntaxHighlighter.defaultColor, textDirection = TextDirection.Ltr),
                        softWrap = false,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }

    if (expanded) {
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
                                text = CodeSyntaxHighlighter.highlight(code, language),
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
