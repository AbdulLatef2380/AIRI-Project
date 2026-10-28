package com.airi.assistant.ui.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/** Android platform effects used by adaptive assistant-content actions. */
object ContentPlatformActions {
    fun copyPlainText(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun createShareIntent(text: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }

    fun launchShare(context: Context, text: String, chooserTitle: String) {
        context.startActivity(Intent.createChooser(createShareIntent(text), chooserTitle))
    }
}
