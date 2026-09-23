package com.airi.assistant.ai.skills.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillParamDef
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * On-device document analysis for PDF pages and image OCR.
 *
 * PDF analysis uses Android PdfRenderer for page counting/rendering and a
 * bounded literal-text extractor for text-based PDFs. OCR uses ML Kit's
 * on-device Latin recognizer over rendered PDF pages or image attachments.
 * Arabic OCR is reported as unsupported by this recognizer instead of being
 * silently mislabeled as successful.
 */
class DocumentAnalysisSkill(
    private val context: Context,
    private val mode: Mode
) : AiriSkill {
    enum class Mode { PDF, OCR }

    override val skillId = if (mode == Mode.PDF) "pdf_analysis" else "ocr_analysis"
    override val name = skillId
    override val displayName = if (mode == Mode.PDF) "PDF Analysis" else "OCR Analysis"
    override val description = if (mode == Mode.PDF)
        "Extract bounded text and page metadata from PDF documents on-device"
    else
        "Recognize Latin text from image attachments or rendered PDF pages on-device"
    override val version = "1.0.0"
    override val author = "AIRI Official"
    override val category = "DOCUMENTS"
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.NONE
    override val modelAccess = SkillModelAccess.NONE
    override val supportsAttachments = true
    override val parameters = mapOf(
        "uri" to "string — content URI",
        "maxPages" to "int — default 8",
        "maxChars" to "int — default 12000"
    )
    override val inputSchema = parameters
    override val outputSchema = mapOf(
        "text" to "string",
        "pages" to "integer",
        "method" to "string"
    )
    override val instructions = if (mode == Mode.PDF)
        "Analyze only the supplied PDF URI. Bound pages and output size; preserve unknowns."
    else
        "Run bounded on-device Latin OCR over the supplied image or PDF URI."
    override val examples = listOf(
        if (mode == Mode.PDF) "Analyze this PDF and extract its readable text" else "OCR this attached image"
    )
    override val limitations = listOf(
        "Requires a readable content URI and bounded input size.",
        "PDF text extraction is conservative; scanned pages require OCR.",
        "The bundled ML Kit recognizer is Latin-focused and does not claim Arabic OCR support."
    )
    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name = skillId,
            description = description,
            parameters = mapOf(
                "uri" to SkillParamDef("string", "Content URI", true),
                "maxPages" to SkillParamDef("integer", "Maximum pages", false),
                "maxChars" to SkillParamDef("integer", "Maximum output characters", false)
            )
        )
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        val hint = if (mode == Mode.PDF) {
            listOf("pdf", "portable document", "صفحة pdf", "تحليل pdf")
        } else {
            listOf("ocr", "scan", "scanned", "image text", "extract text from image", "مسح ضوئي", "التعرف الضوئي")
        }
        return (hint.count(lower::contains) * 30 + if (context.lastUsedSkill == skillId) 10 else 0)
            .coerceIn(0, 100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val uriString = params["uri"] as? String
            ?: return@withContext failure("A content URI is required.", started)
        val uri = Uri.parse(uriString)
        val maxPages = ((params["maxPages"] as? String)?.toIntOrNull() ?: 8).coerceIn(1, 20)
        val maxChars = ((params["maxChars"] as? String)?.toIntOrNull() ?: 12_000).coerceIn(500, 50_000)
        return@withContext runCatching {
            if (mode == Mode.PDF) analyzePdf(uri, maxPages, maxChars, started)
            else analyzeImageOrPdf(uri, maxPages, maxChars, started)
        }.getOrElse { failure("Document analysis failed: ${it.message ?: "unreadable input"}", started) }
    }

    private fun analyzePdf(uri: Uri, maxPages: Int, maxChars: Int, started: Long): SkillResult {
        val temp = copyToCache(uri)
        val literalText = extractLiteralPdfText(temp, maxChars)
        val descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        val pages = renderer.pageCount
        renderer.close()
        descriptor.close()
        temp.delete()
        val boundedPages = minOf(pages, maxPages)
        return SkillResult(
            success = literalText.isNotBlank(),
            data = literalText.ifBlank { "PDF contains no conservatively extractable text; use ocr_analysis for scanned pages." },
            error = if (literalText.isBlank()) "No text stream was extracted from the PDF." else null,
            skillName = skillId,
            executionMs = System.currentTimeMillis() - started,
            metadata = mapOf(
                "method" to "pdf_literal_text",
                "pages" to pages.toString(),
                "pages_processed" to boundedPages.toString(),
                "chars" to literalText.length.toString()
            )
        )
    }

    private suspend fun analyzeImageOrPdf(uri: Uri, maxPages: Int, maxChars: Int, started: Long): SkillResult {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val text = if (mime == "application/pdf" || uri.toString().lowercase().contains(".pdf")) {
            val temp = copyToCache(uri)
            val descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(descriptor)
            val count = minOf(renderer.pageCount, maxPages)
            val pages = (0 until count).mapNotNull { index ->
                val page = renderer.openPage(index)
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmap
            }
            val result = pages.joinToString("\n\n") { recognize(it) }
            pages.forEach { it.recycle() }
            renderer.close(); descriptor.close(); temp.delete()
            result
        } else {
            val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?: return failure("Image could not be decoded.", started)
            val result = recognize(bitmap)
            bitmap.recycle()
            result
        }
        val bounded = text.take(maxChars)
        return SkillResult(
            success = bounded.isNotBlank(),
            data = bounded,
            error = if (bounded.isBlank()) "No Latin text was recognized." else null,
            skillName = skillId,
            executionMs = System.currentTimeMillis() - started,
            metadata = mapOf("method" to "mlkit_on_device_latin", "chars" to bounded.length.toString())
        )
    }

    private suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                recognizer.close()
                if (continuation.isActive) continuation.resume(result.text)
            }
            .addOnFailureListener { error ->
                recognizer.close()
                if (continuation.isActive) continuation.resume("")
            }
    }

    private fun copyToCache(uri: Uri): File {
        val file = File.createTempFile("airi_doc_", ".bin", context.cacheDir)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open document URI" }
            file.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        }
        require(file.length() <= 25L * 1024L * 1024L) { "Document exceeds the 25MB safety limit" }
        return file
    }

    private fun extractLiteralPdfText(file: File, maxChars: Int): String {
        val bytes = file.readBytes().take(8 * 1024 * 1024).toByteArray()
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val matches = Regex("\\(([^()\\\\]{1,2000})\\)").findAll(raw)
        return matches.map { it.groupValues[1] }
            .filter { it.any(Char::isLetterOrDigit) }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .take(maxChars)
    }

    private fun failure(message: String, started: Long) = SkillResult(
        success = false, data = "", error = message, skillName = skillId,
        executionMs = System.currentTimeMillis() - started
    )
}
