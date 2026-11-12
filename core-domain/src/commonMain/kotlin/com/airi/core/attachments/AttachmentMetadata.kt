package com.airi.core.attachments

/**
 * Platform-neutral description of an attachment.
 *
 * This type deliberately contains no Android payload handles such as Uri or
 * Bitmap. Platform adapters may retain those handles separately and project
 * them into this metadata before persistence, prompting, or tracing.
 */
enum class AttachmentKind {
    IMAGE,
    CAMERA,
    VIDEO,
    FILE,
}

data class AttachmentMetadata(
    val id: String,
    val kind: AttachmentKind,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val uid: String,
    val fileName: String?,
) {
    init {
        require(id.isNotBlank()) { "Attachment id must not be blank." }
        require(uid.isNotBlank()) { "Attachment uid must not be blank." }
        require(sizeBytes == null || sizeBytes >= 0L) { "Attachment size must not be negative." }
    }

    val safeDisplayName: String
        get() = AttachmentPolicy.normalizedDisplayName(displayName)

    val normalizedMimeType: String
        get() = AttachmentPolicy.normalizedMimeType(mimeType)
}
