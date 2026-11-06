package com.airi.assistant.attachments

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Deterministic local writers for text, OOXML, ODF, and EPUB artifacts. */
object DocumentGenerator {
    data class GeneratedDocument(val fileName: String, val mimeType: String, val bytes: ByteArray)

    fun generate(fileName: String, content: String): GeneratedDocument {
        val safeName = fileName.trim().substringAfterLast('/').substringAfterLast('\\')
        require(safeName.isNotBlank()) { "A file name is required" }
        return when (safeName.substringAfterLast('.', "").lowercase()) {
            "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "yaml", "yml", "log" ->
                GeneratedDocument(safeName, mimeFor(safeName.substringAfterLast('.').lowercase()), content.toByteArray(StandardCharsets.UTF_8))
            "docx" -> generateDocx(safeName, content)
            "xlsx" -> generateXlsx(safeName, content)
            "odt" -> generateOdf(safeName, "application/vnd.oasis.opendocument.text", "text", content)
            "ods" -> generateOdf(safeName, "application/vnd.oasis.opendocument.spreadsheet", "spreadsheet", content)
            "odp" -> generateOdf(safeName, "application/vnd.oasis.opendocument.presentation", "presentation", content)
            "epub" -> generateEpub(safeName, content)
            else -> throw IllegalArgumentException("No local generator exists for .$safeName")
        }
    }

    private fun generateDocx(fileName: String, content: String): GeneratedDocument {
        val body = content.lineSequence().joinToString("") { line ->
            val trimmed = line.trimStart()
            val hashes = trimmed.takeWhile { it == '#' }.length
            val value = if (hashes > 0) trimmed.drop(hashes).trim() else line
            val style = if (hashes > 0) "<w:pPr><w:pStyle w:val=\"Heading${hashes.coerceIn(1, 6)}\"/></w:pPr>" else ""
            "<w:p>$style<w:r><w:t xml:space=\"preserve\">${escapeXml(value)}</w:t></w:r></w:p>"
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>"""
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>"""
        val bytes = zipBytes { zip ->
            entry(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""")
            entry(zip, "_rels/.rels", relationship("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument", "word/document.xml"))
            entry(zip, "word/document.xml", document)
            entry(zip, "word/styles.xml", styles)
            entry(zip, "word/_rels/document.xml.rels", emptyRelationships())
            entry(zip, "docProps/core.xml", coreProperties(fileName))
        }
        return GeneratedDocument(fileName, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes)
    }

    private fun generateXlsx(fileName: String, content: String): GeneratedDocument {
        val rows = content.lineSequence().map(::parseDelimitedRow).toList().ifEmpty { listOf(listOf("")) }
        val rowXml = rows.mapIndexed { rowIndex, cells ->
            val cellsXml = cells.mapIndexed { column, value ->
                "<c r=\"${columnName(column)}${rowIndex + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escapeXml(value)}</t></is></c>"
            }.joinToString("")
            "<row r=\"${rowIndex + 1}\">$cellsXml</row>"
        }.joinToString("")
        val sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rowXml</sheetData></worksheet>"""
        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="AIRI" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        val bytes = zipBytes { zip ->
            entry(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""")
            entry(zip, "_rels/.rels", relationship("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument", "xl/workbook.xml"))
            entry(zip, "xl/workbook.xml", workbook)
            entry(zip, "xl/_rels/workbook.xml.rels", relationship("http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet", "worksheets/sheet1.xml"))
            entry(zip, "xl/worksheets/sheet1.xml", sheet)
            entry(zip, "docProps/core.xml", coreProperties(fileName))
        }
        return GeneratedDocument(fileName, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes)
    }

    private fun parseDelimitedRow(line: String): List<String> {
        if ('\t' in line) return line.split('\t')
        if (!line.contains(',')) return listOf(line)
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"' && i + 1 < line.length && line[i + 1] == '"') { current.append('"'); i += 2; continue }
            if (c == '"') quoted = !quoted
            else if (c == ',' && !quoted) { values += current.toString(); current.clear() }
            else current.append(c)
            i++
        }
        values += current.toString()
        return values
    }

    private fun columnName(index: Int): String {
        var value = index + 1
        val result = StringBuilder()
        while (value > 0) { result.append(('A'.code + (value - 1) % 26).toChar()); value = (value - 1) / 26 }
        return result.reverse().toString()
    }

    private fun relationship(type: String, target: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$type\" Target=\"$target\"/></Relationships>"

    private fun emptyRelationships(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>"

    private fun coreProperties(title: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\"><dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\">${escapeXml(title)}</dc:title></cp:coreProperties>"

    private fun generateOdf(fileName: String, mime: String, kind: String, content: String): GeneratedDocument {
        val escaped = escapeXml(content)
        val xml = when (kind) {
            "spreadsheet" -> """<?xml version="1.0" encoding="UTF-8"?><office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:spreadsheet><table:table table:name="AIRI"><table:table-row><table:table-cell office:value-type="string"><text:p>$escaped</text:p></table:table-cell></table:table-row></table:table></office:spreadsheet></office:body></office:document-content>"""
            "presentation" -> """<?xml version="1.0" encoding="UTF-8"?><office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:presentation><draw:page draw:name="Slide 1"><draw:frame><draw:text-box><text:p>$escaped</text:p></draw:text-box></draw:frame></draw:page></office:presentation></office:body></office:document-content>"""
            else -> """<?xml version="1.0" encoding="UTF-8"?><office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:text>${escaped.lineSequence().joinToString("") { "<text:p>$it</text:p>" }}</office:text></office:body></office:document-content>"""
        }
        val bytes = zipBytes { zip ->
            putStoredEntry(zip, "mimetype", mime.toByteArray())
            entry(zip, "content.xml", xml)
            entry(zip, "META-INF/manifest.xml", """<?xml version="1.0" encoding="UTF-8"?><manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"><manifest:file-entry manifest:full-path="/" manifest:media-type="$mime"/><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/></manifest:manifest>""")
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
            entry(zip, "META-INF/container.xml", container)
            entry(zip, "OEBPS/content.opf", packageXml)
            entry(zip, "OEBPS/content.xhtml", html)
        }
        return GeneratedDocument(fileName, "application/epub+zip", bytes)
    }

    private fun zipBytes(write: (ZipOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use(write); output.toByteArray()
    }
    private fun entry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(StandardCharsets.UTF_8)); zip.closeEntry()
    }
    private fun putStoredEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }.value
        zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = bytes.size.toLong(); this.crc = crc })
        zip.write(bytes); zip.closeEntry()
    }
    private fun mimeFor(extension: String): String = when (extension) {
        "md" -> "text/markdown"; "csv" -> "text/csv"; "tsv" -> "text/tab-separated-values"; "json" -> "application/json"
        "xml" -> "application/xml"; "html", "htm" -> "text/html"; "yaml", "yml" -> "application/yaml"; else -> "text/plain"
    }
    private fun escapeXml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
