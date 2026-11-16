package com.airi.assistant.domain

/**
 * Keeps attachment transport text out of the user's chat bubble.
 * The complete file remains available through the attachment descriptor.
 */
object AttachmentBubbleTextPolicy {
    private const val ATTACHMENT_METADATA_MARKER = "[Attachment metadata:"
    private const val GENERATED_FILE_SUMMARY_PREFIX = "[Attached file:"

    fun visibleText(content: String): String {
        val metadataStart = content.indexOf(ATTACHMENT_METADATA_MARKER)
        val withoutMetadata = if (metadataStart >= 0) {
            content.substring(0, metadataStart).trim()
        } else {
            content.trim()
        }
        if (withoutMetadata.startsWith(GENERATED_FILE_SUMMARY_PREFIX)) {
            return withoutMetadata.lineSequence().firstOrNull().orEmpty().trim()
        }
        return withoutMetadata
    }
}
***
