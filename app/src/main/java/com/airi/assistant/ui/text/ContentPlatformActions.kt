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
    /** Writes to Clipboard and reports the actual platform outcome. */
    fun copyPlainText(context: Context, label: String, text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val write = { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching { write(); true }.getOrDefault(false)
        } else {
            val completed = CountDownLatch(1)
            var succeeded = false
            Handler(Looper.getMainLooper()).post {
                try {
                    write()
                    succeeded = true
                } catch (_: RuntimeException) {
                    succeeded = false
                } finally {
                    completed.countDown()
                }
            }
            val finished = runCatching { completed.await(2, TimeUnit.SECONDS) }.getOrDefault(false)
            return finished && succeeded
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
