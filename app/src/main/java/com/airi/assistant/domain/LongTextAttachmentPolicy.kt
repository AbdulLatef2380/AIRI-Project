package com.airi.assistant.domain

/**
 * Product policy for keeping large pasted prompts out of the composer/IPC payload.
 * This is intentionally separate from Android Binder and file-size limits.
 */
object LongTextAttachmentPolicy {
    /** Maximum number of lines kept visible in an inline chat bubble. */
    const val INLINE_VISIBLE_LINE_LIMIT = 15
    const val AUTO_CONVERT_CHAR_THRESHOLD = 3_000
    const val AUTO_CONVERT_LINE_THRESHOLD = 60

    /** Counts logical lines without allocating a sequence for huge drafts. */
    fun logicalLineCount(text: String): Int {
        if (text.isEmpty()) return 1
        var count = 1
        for (ch in text) if (ch == '\n') count++
        return count
    }

    fun shouldCollapseInline(text: String): Boolean =
        logicalLineCount(text) > INLINE_VISIBLE_LINE_LIMIT && !shouldAutoConvert(text)

    fun collapsedPreview(text: String): String =
        text.lineSequence().take(INLINE_VISIBLE_LINE_LIMIT).joinToString("\n")

    fun shouldAutoConvert(text: String): Boolean {
        if (text.length >= AUTO_CONVERT_CHAR_THRESHOLD) return true
        var lines = 1
        for (ch in text) {
            if (ch == '\n' && ++lines > AUTO_CONVERT_LINE_THRESHOLD) return true
        }
        return false
    }

    fun utf8SizeBytes(text: String): Long =
        text.toByteArray(Charsets.UTF_8).size.toLong()
}
