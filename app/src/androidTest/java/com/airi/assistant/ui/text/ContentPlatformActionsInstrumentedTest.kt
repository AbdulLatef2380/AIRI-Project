package com.airi.assistant.ui.text

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ClipboardManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airi.assistant.ui.text.ContentPlatformActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentPlatformActionsInstrumentedTest {
    @Test
    fun clipboardReceivesOnlyTheScopedBlockPayload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = "| Name | State |\n| --- | --- |\n| AIRI | Ready |"
        val target = ContentTarget.Block(
            messageId = "clipboard-message",
            blockId = "table-1",
            kind = ContentUnitKind.TABLE,
            source = source,
            actions = setOf(ContentUnitAction.COPY),
        )

        ContentPlatformActions.copyPlainText(context, "AIRI", target.copyPayload)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals(1, clip.itemCount)
        assertEquals("Name\tState\nAIRI\tReady", clip.getItemAt(0).coerceToText(context).toString())
    }

    @Test
    fun shareLaunchUsesChooserWithPlainTextSendIntentAndExactPayload() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val recordingContext = RecordingContext(baseContext)
        val payload = "val onlyThisBlock = true"

        ContentPlatformActions.launchShare(recordingContext, payload, "Share AIRI content")

        val chooser = requireNotNull(recordingContext.startedIntent)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share AIRI content", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
        @Suppress("DEPRECATION")
        val sendIntent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(sendIntent)
        assertEquals(Intent.ACTION_SEND, sendIntent?.action)
        assertEquals("text/plain", sendIntent?.type)
        assertEquals(payload, sendIntent?.getStringExtra(Intent.EXTRA_TEXT))
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var startedIntent: Intent? = null
            private set

        override fun startActivity(intent: Intent) {
            startedIntent = intent
        }
    }
}
