package com.airi.assistant.domain

/**
 * Product policy for keeping large pasted prompts out of the composer/IPC payload.
 * This is intentionally separate from Android Binder and file-size limits.
 */
object LongTextAttachmentPolicy {
    const val AUTO_CONVERT_CHAR_THRESHOLD = 3_000
    const val AUTO_CONVERT_LINE_THRESHOLD = 40

    fun logicalLineCount(text: String): Int = text.lineSequence().count()

    fun shouldAutoConvert(text: String): Boolean =
        text.length >= AUTO_CONVERT_CHAR_THRESHOLD ||
            logicalLineCount(text) > AUTO_CONVERT_LINE_THRESHOLD

    fun utf8SizeBytes(text: String): Long =
        text.toByteArray(Charsets.UTF_8).size.toLong()
}
