package com.airi.assistant.domain

import java.util.Locale

/**
 * Central validation and presentation policy for a pending chat attachment.
 *
 * The policy deliberately operates on metadata only. Content is copied into
 * application-private storage before it is persisted or supplied to a model.
 */
object AttachmentPolicy {
    const val MAX_ATTACHMENTS_PER_MESSAGE = 6
    const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L
    const val MAX_TEXT_ATTACHMENT_BYTES = 512L * 1024L
    const val MAX_TEXT_CONTENT_CHARS = 24_000

    enum class ContentType {
        IMAGE,
        VIDEO,
        TEXT,
        DOCUMENT,
        FILE
    }

    sealed interface ValidationResult {
        data object Accepted : ValidationResult
        data object TooLarge : ValidationResult
        data object TextTooLarge : ValidationResult
    }

    fun validateSize(sizeBytes: Long?, contentType: ContentType): ValidationResult = when {
        sizeBytes == null -> ValidationResult.Accepted
        sizeBytes > MAX_ATTACHMENT_BYTES -> ValidationResult.TooLarge
        contentType == ContentType.TEXT && sizeBytes > MAX_TEXT_ATTACHMENT_BYTES ->
            ValidationResult.TextTooLarge
        else -> ValidationResult.Accepted
    }

    fun contentType(mimeType: String?, fileName: String?): ContentType {
        val mime = normalizedMimeType(mimeType)
        val extension = fileName.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            mime.startsWith("image/") -> ContentType.IMAGE
            mime.startsWith("video/") -> ContentType.VIDEO
            mime.startsWith("text/") || extension in TEXT_EXTENSIONS -> ContentType.TEXT
            mime in DOCUMENT_MIME_TYPES || extension in DOCUMENT_EXTENSIONS -> ContentType.DOCUMENT
            else -> ContentType.FILE
        }
    }

    fun normalizedDisplayName(value: String?): String {
        val normalized = value.orEmpty()
            .replace(Regex("[\\p{Cntrl}\\r\\n]+"), " ")
            .trim()
            .take(120)
        return normalized.ifBlank { "attachment" }
    }

    fun normalizedMimeType(value: String?): String = value.orEmpty()
        .trim()
        .lowercase(Locale.ROOT)
        .takeIf { MIME_PATTERN.matches(it) }
        .orEmpty()

    fun isTextual(contentType: ContentType): Boolean = contentType == ContentType.TEXT

    fun isSameSource(firstUri: String?, secondUri: String?): Boolean =
        !firstUri.isNullOrBlank() && firstUri == secondUri

    private val MIME_PATTERN = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
    private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "csv", "json", "xml", "yaml", "yml", "log", "kt", "java", "py", "js", "ts", "html", "css", "sql")
    private val DOCUMENT_EXTENSIONS = setOf("pdf", "doc", "docx", "rtf", "odt", "xls", "xlsx", "ppt", "pptx")
    private val DOCUMENT_MIME_TYPES = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text"
    )
}
