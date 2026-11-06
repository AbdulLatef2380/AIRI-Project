package com.airi.assistant.attachments

/**
 * Canonical document capability catalog. A format is advertised only when a
 * real local reader or generator exists in AIRI; unknown extensions are never
 * guessed as UTF-8 or reported as supported.
 */
object DocumentFormatRegistry {
    enum class Family { TEXT, PDF, OOXML, ODF, EPUB, IMAGE, BINARY }

    data class Format(
        val extension: String,
        val mimeTypes: Set<String>,
        val family: Family,
        val readable: Boolean,
        val generatable: Boolean,
    )

    private val formats = listOf(
        Format("txt", setOf("text/plain"), Family.TEXT, readable = true, generatable = true),
        Format("md", setOf("text/markdown", "text/x-markdown"), Family.TEXT, true, true),
        Format("csv", setOf("text/csv"), Family.TEXT, true, true),
        Format("tsv", setOf("text/tab-separated-values"), Family.TEXT, true, true),
        Format("json", setOf("application/json"), Family.TEXT, true, true),
        Format("xml", setOf("application/xml", "text/xml"), Family.TEXT, true, true),
        Format("html", setOf("text/html"), Family.TEXT, true, true),
        Format("htm", setOf("text/html"), Family.TEXT, true, true),
        Format("yaml", setOf("application/yaml", "text/yaml"), Family.TEXT, true, true),
        Format("yml", setOf("application/yaml", "text/yaml"), Family.TEXT, true, true),
        Format("log", setOf("text/plain"), Family.TEXT, true, true),
        Format("pdf", setOf("application/pdf"), Family.PDF, true, false),
        Format("docx", setOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"), Family.OOXML, true, true),
        Format("xlsx", setOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), Family.OOXML, true, true),
        Format("pptx", setOf("application/vnd.openxmlformats-officedocument.presentationml.presentation"), Family.OOXML, true, false),
        Format("odt", setOf("application/vnd.oasis.opendocument.text"), Family.ODF, true, true),
        Format("ods", setOf("application/vnd.oasis.opendocument.spreadsheet"), Family.ODF, true, true),
        Format("odp", setOf("application/vnd.oasis.opendocument.presentation"), Family.ODF, true, true),
        Format("epub", setOf("application/epub+zip"), Family.EPUB, true, true),
        Format("png", setOf("image/png"), Family.IMAGE, false, false),
        Format("jpg", setOf("image/jpeg"), Family.IMAGE, false, false),
        Format("jpeg", setOf("image/jpeg"), Family.IMAGE, false, false),
    )

    private val byExtension = formats.associateBy { it.extension }
    private val byMime = formats.flatMap { format -> format.mimeTypes.map { it to format } }.toMap()

    fun find(mimeType: String?, fileName: String?): Format? {
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase()
        val ext = fileName.orEmpty().substringAfterLast('.', "").lowercase()
        return byMime[mime] ?: byExtension[ext]
    }

    fun readable(mimeType: String?, fileName: String?): Boolean = find(mimeType, fileName)?.readable == true

    fun generatable(extension: String): Boolean = byExtension[extension.lowercase().removePrefix(".")]?.generatable == true

    fun supportedExtensions(readableOnly: Boolean = false): Set<String> = formats
        .filter { !readableOnly || it.readable }
        .map { it.extension }
        .toSet()
}
