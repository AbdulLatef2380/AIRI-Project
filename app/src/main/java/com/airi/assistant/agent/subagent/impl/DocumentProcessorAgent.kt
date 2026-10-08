package com.airi.assistant.agent.subagent.impl

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * DocumentProcessorAgent — on-device document and file reading & analysis.
 *
 * REAL EXECUTION:
 *   - Plain text files (.txt, .md, .csv, .json, .xml, .log, .kt, .py …):
 *     read via [ContentResolver] stream, UTF-8 decoded, first [MAX_CHARS]
 *     characters injected into an LLM synthesis prompt.
 *   - PDF and Office files: bounded local extraction through the shared
 *     AttachmentContentExtractor. Unsupported binary formats fail closed.
 *   - Arbitrary URIs from the Android file picker (content:// or file://).
 *
 * PRIVACY:
 *   - All reading happens on-device.
 *   - Content is sent to the LLM backend only if cloud access AND private-data
 *     consent are both present in [context].
 *   - Otherwise, a bounded local excerpt is returned without LLM synthesis.
 *
 * SUPPORTED OPERATIONS (detected from user input):
 *   SUMMARIZE — condense the document.
 *   EXTRACT   — pull specific data (names, dates, numbers, emails …).
 *   TRANSLATE — request LLM translation of the content.
 *   ANALYSE   — general analysis / Q&A over the document.
 *   READ      — return the raw text with no synthesis.
 */
class DocumentProcessorAgent(
    private val context: Context
) : SubAgent {

    companion object {
        private const val TAG       = "DocumentProcessorAgent"
        private const val MAX_CHARS = 8_000
        private const val MAX_SOURCE_BYTES = 12L * 1024L * 1024L
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "csv", "json", "xml", "log", "yaml", "yml",
            "kt", "py", "java", "js", "ts", "html", "css", "ini", "toml"
        )
    }

    override val capability = SubAgentCapability(
        agentId        = "document_processor_agent",
        displayName    = "Document Processor",
        description    = "Read, summarize, extract data from, and analyse documents and files.",
        intentKeywords = listOf(
            "summarize this file", "read this document", "analyse this file",
            "extract from", "process document", "read pdf", "open file",
            "what does this file say", "translate this document",
            "analyze document", "review document", "parse file",
            "extract data from", "what is in this file", "read this text"
        ),
        domains             = listOf("document", "file", "pdf", "text", "analysis", "extract"),
        requiresCloud       = false,
        requiredTools       = listOf("file_reader"),
        costTier            = SubAgentCapability.CostTier.LOW,
        latencyProfile      = SubAgentCapability.LatencyProfile.MODERATE,
        supportsBackground  = true,
        maxParallelSubTasks = 1,
        supportsResume      = false
    )

    override suspend fun canHandle(input: String, context: SubAgentContext): Boolean {
        val lower = input.lowercase()
        return DOC_SIGNALS.any { lower.contains(it) }
    }

    override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
        val start = System.currentTimeMillis()
        Log.i(TAG, "DOCUMENT_PROCESSOR_EXECUTE inputChars=${input.length}")

        emit(AgentEvent.Progress("Detecting document operation…", 10, "classify"))

        val operation = detectOperation(input.lowercase())
        val uriString = extractUri(input)

        emit(AgentEvent.Progress("Reading document…", 25, "read"))
        emit(AgentEvent.ToolCall(
            toolName  = "file_reader",
            params    = mapOf(
                "uri"       to (uriString ?: "(from context)"),
                "operation" to operation.name
            ),
            reasoning = "Read document from URI for: ${operation.name}"
        ))

        // Attempt to read the file
        val extracted: String? = when {
            uriString != null -> readFromUri(Uri.parse(uriString))
            else              -> null
        }

        if (extracted.isNullOrBlank()) {
            emit(AgentEvent.PartialResult(
                "I need a file to process. Please attach a document (via the attachment button) " +
                "and ask me to summarize, extract, or analyse it.",
                isFinal = true
            ))
            emit(AgentEvent.Complete(
                result     = "[DocumentProcessor: no file provided]",
                durationMs = System.currentTimeMillis() - start,
                toolsUsed  = listOf("file_reader")
            ))
            return@flow
        }

        val chars  = extracted.length
        val excerpt = extracted.take(MAX_CHARS)
        Log.i(TAG, "AIRI DOC_PROCESSED chars=$chars operation=${operation.name}")

        emit(AgentEvent.Progress("Processing ${chars} characters…", 55, "process"))

        if (!context.cloudAllowed || !context.privateDataAllowed || context.privacyLevel == SubAgentContext.PRIVACY_MAXIMUM) {
            // Keep attachment contents local unless the context carries private-data consent.
            val preview = excerpt.take(2_000)
            emit(AgentEvent.PartialResult(
                "Document content (${chars} chars, local mode):\n\n$preview" +
                    if (chars > 2_000) "\n\n[… ${chars - 2_000} more characters]" else "",
                isFinal = true
            ))
        } else {
            emit(AgentEvent.Progress("Analysing with LLM…", 70, "synthesise"))
            emit(AgentEvent.Delegate(
                targetAgentId = "llm_backend",
                subInput      = buildSynthesisPrompt(input, operation, excerpt, chars),
                reason        = "LLM synthesis for document ${operation.name.lowercase()}"
            ))
        }

        emit(AgentEvent.Complete(
            result     = "[DocumentProcessor: ${operation.name} chars=$chars]",
            durationMs = System.currentTimeMillis() - start,
            toolsUsed  = listOf("file_reader")
        ))
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private enum class DocOperation { SUMMARIZE, EXTRACT, TRANSLATE, ANALYSE, READ }

    private fun detectOperation(lower: String): DocOperation = when {
        lower.contains("summarize") || lower.contains("summarise") -> DocOperation.SUMMARIZE
        lower.contains("extract")   || lower.contains("pull out") -> DocOperation.EXTRACT
        lower.contains("translate")                               -> DocOperation.TRANSLATE
        lower.contains("read")      || lower.contains("show me")  -> DocOperation.READ
        else                                                      -> DocOperation.ANALYSE
    }

    private fun extractUri(input: String): String? {
        val match = Regex("(content://[^\\s]+|file://[^\\s]+)").find(input)
        return match?.value
    }

    private fun readFromUri(uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty().substringBefore(';').trim().lowercase()
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "attachment"
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val staged = File.createTempFile("airi_document_", ".bounded", context.cacheDir)
        try {
            val source = resolver.openInputStream(uri) ?: return@runCatching null
            source.use { input -> staged.outputStream().buffered().use { output ->
                copyBounded(input, output, MAX_SOURCE_BYTES)
            } }
            when {
                mime.startsWith("text/") || extension in TEXT_EXTENSIONS ->
                    staged.inputStream().bufferedReader(Charsets.UTF_8).use { readBounded(it, MAX_CHARS) }
                com.airi.assistant.attachments.AttachmentContentExtractor.supports(mime, displayName) ->
                    com.airi.assistant.attachments.AttachmentContentExtractor.extract(
                        file = staged,
                        mimeType = mime,
                        fileName = displayName,
                        maxChars = MAX_CHARS,
                    )
                else -> throw IllegalArgumentException("This attachment format has no local text extractor.")
            }
        } finally {
            staged.delete()
        }
    }.onFailure { Log.w(TAG, "URI read failed: ${it.javaClass.simpleName}") }.getOrNull()

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            if (count == 0) continue
            if (total + count > maxBytes) throw IllegalArgumentException("Attachment exceeds local processing limit")
            output.write(buffer, 0, count)
            total += count
        }
    }

    private fun readBounded(reader: java.io.Reader, maxChars: Int): String {
        val out = StringBuilder(minOf(maxChars, 2_048))
        val buffer = CharArray(minOf(maxChars, 2_048))
        while (out.length < maxChars) {
            val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - out.length))
            if (count <= 0) break
            out.append(buffer, 0, count)
        }
        return out.toString()
    }


    private fun buildSynthesisPrompt(
        userQuery: String,
        operation: DocOperation,
        content:   String,
        totalChars: Int
    ): String {
        val opInstruction = when (operation) {
            DocOperation.SUMMARIZE  -> "Summarize this document concisely."
            DocOperation.EXTRACT    -> "Extract key information: names, dates, numbers, emails, and important facts."
            DocOperation.TRANSLATE  -> "Translate this document. Detect the source language automatically."
            DocOperation.READ       -> "Present this document content cleanly to the user."
            DocOperation.ANALYSE    -> "Analyse this document and answer: \"$userQuery\""
        }
        return """You are AIRI's document analysis specialist.

USER REQUEST: "$userQuery"
OPERATION: ${operation.name}

DOCUMENT CONTENT ($totalChars total chars, showing first ${content.length}):
$content
${if (totalChars > content.length) "\n[… ${totalChars - content.length} more characters truncated for context length]" else ""}

$opInstruction
Be accurate and thorough. Reference specific parts of the document in your answer."""
    }

    private val DOC_SIGNALS = listOf(
        "summarize this file", "read this document", "analyse this file",
        "extract from", "process document", "read pdf", "open file",
        "what does this file say", "translate this document",
        "analyze document", "review document", "parse file",
        "extract data from", "what is in this file", "read this text",
        "content://", "file://"
    )
}
