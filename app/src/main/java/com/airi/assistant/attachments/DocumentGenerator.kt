package com.airi.assistant.attachments

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Generates only formats with a deterministic local writer. */
object DocumentGenerator {
    data class GeneratedDocument(val fileName: String, val mimeType: String, val bytes: ByteArray)

    fun generate(fileName: String, content: String): GeneratedDocument {
        val safeName = fileName.trim().substringAfterLast('/').substringAfterLast('\\')
        require(safeName.isNotBlank()) { "A file name is required" }
        val extension = safeName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "yaml", "yml", "log" ->
                GeneratedDocument(safeName, mimeFor(extension), content.toByteArray(StandardCharsets.UTF_8))
            "odt" -> generateOdf(safeName, "application/vnd.oasis.opendocument.text", "text", content)
            "ods" -> generateOdf(safeName, "application/vnd.oasis.opendocument.spreadsheet", "spreadsheet", content)
            "odp" -> generateOdf(safeName, "application/vnd.oasis.opendocument.presentation", "presentation", content)
            "epub" -> generateEpub(safeName, content)
            else -> throw IllegalArgumentException("No local generator exists for .$extension")
        }
    }

    private fun generateOdf(fileName: String, mime: String, kind: String, content: String): GeneratedDocument {
        val escaped = escapeXml(content)
        val xml = when (kind) {
            "spreadsheet" -> """<?xml version="1.0" encoding="UTF-8"?><office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:spreadsheet><table:table table:name="AIRI"><table:table-row><table:table-cell office:value-type="string"><text:p>$escaped</text:p></table:table-cell></table:table-row></table:table></office:spreadsheet></office:body></office:document-content>"""
            "presentation" -> """<?xml version="1.0" encoding="UTF-8"?><office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:presentation><draw:page draw:name="Slide 1"><draw:frame><draw:text-box><text:p>$escaped</text:p></draw:text-box></draw:frame></draw:page></office:presentation></office:body></office:document-content>"""
            else -> """<?xml version="1.0" encoding="UTF-8"?><office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:text>${escaped.lineSequence().joinToString("") { "<text:p>$it</text:p>" }}</office:text></office:body></office:document-content>"""
        }
        val bytes = zipBytes { zip ->
            putStoredEntry(zip, "mimetype", mime.toByteArray())
            zip.putNextEntry(ZipEntry("content.xml")); zip.write(xml.toByteArray()); zip.closeEntry()
        }
        return GeneratedDocument(fileName, mime, bytes)
    }

    private fun generateEpub(fileName: String, content: String): GeneratedDocument {
        val title = fileName.substringBeforeLast('.').ifBlank { "AIRI document" }
        val html = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${escapeXml(title)}</title></head><body><h1>${escapeXml(title)}</h1>${content.lineSequence().joinToString("") { "<p>${escapeXml(it)}</p>" }}</body></html>"""
        val container = """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val packageXml = """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">urn:uuid:airi-${title.hashCode()}</dc:identifier><dc:title>${escapeXml(title)}</dc:title><dc:language>und</dc:language></metadata><manifest><item id="content" href="content.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="content"/></spine></package>"""
        val bytes = zipBytes { zip ->
            putStoredEntry(zip, "mimetype", "application/epub+zip".toByteArray())
            zip.putNextEntry(ZipEntry("META-INF/container.xml")); zip.write(container.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.opf")); zip.write(packageXml.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.xhtml")); zip.write(html.toByteArray()); zip.closeEntry()
        }
        return GeneratedDocument(fileName, "application/epub+zip", bytes)
    }

    private fun zipBytes(write: (ZipOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use(write)
        output.toByteArray()
    }

    private fun putStoredEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }.value
        zip.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc
        })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun mimeFor(extension: String): String = when (extension) {
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "yaml", "yml" -> "application/yaml"
        else -> "text/plain"
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
