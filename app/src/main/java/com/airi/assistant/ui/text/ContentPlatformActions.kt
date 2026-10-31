package com.airi.assistant.ui.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android platform effects used by adaptive assistant-content actions. */
object ContentPlatformActions {
    fun copyPlainText(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val write = { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            write()
        } else {
            val completed = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    write()
                } finally {
                    completed.countDown()
                }
            }
            completed.await(2, TimeUnit.SECONDS)
        }
    }

    fun createShareIntent(text: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }

    fun launchShare(context: Context, text: String, chooserTitle: String) {
        context.startActivity(Intent.createChooser(createShareIntent(text), chooserTitle))
    }
}
