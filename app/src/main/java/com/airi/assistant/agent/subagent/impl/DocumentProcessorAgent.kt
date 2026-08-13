package com.airi.assistant.agent.subagent.impl

import android.content.Context
import android.net.Uri
import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * DocumentProcessorAgent — on-device document and file reading & analysis.
 *
 * REAL EXECUTION:
 *   - Plain text files (.txt, .md, .csv, .json, .xml, .log, .kt, .py …):
 *     read via [ContentResolver] stream, UTF-8 decoded, first [MAX_CHARS]
 *     characters injected into an LLM synthesis prompt.
 *   - PDF files: basic byte extraction with text-layer scanning (no render).
 *     Works for text-based PDFs; image-only PDFs return a useful error.
 *   - Arbitrary URIs from the Android file picker (content:// or file://).
 *
 * PRIVACY:
 *   - All reading happens on-device.
 *   - Content is sent to the LLM backend only if [context.cloudAllowed].
 *   - In PRIVACY_MAXIMUM mode, the raw extracted text is returned directly
 *     without LLM synthesis.
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
        Log.i(TAG, "DocumentProcessorAgent.execute input='${input.take(80)}'")

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
        Log.i(TAG, "AIRI_RUNTIME DOC_PROCESSED chars=$chars operation=${operation.name}")

        emit(AgentEvent.Progress("Processing ${chars} characters…", 55, "process"))

        if (!context.cloudAllowed || context.privacyLevel == SubAgentContext.PRIVACY_MAXIMUM) {
            // LOCAL_ONLY: return raw text without LLM synthesis
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

    private fun readFromUri(uri: Uri): String? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val ext = uri.lastPathSegment?.substringAfterLast('.')?.lowercase() ?: ""
                when {
                    ext == "pdf"             -> extractPdfText(stream.readBytes())
                    ext in TEXT_EXTENSIONS   -> stream.bufferedReader(Charsets.UTF_8).readText()
                    else                     -> stream.bufferedReader(Charsets.UTF_8).readText()
                }
            }
        }.onFailure { Log.w(TAG, "URI read failed: ${it.message}") }.getOrNull()

    /**
     * Naive PDF text extraction — scans for BT/ET (Begin/End Text) blocks
     * and collects raw PDF string tokens. Works only for text-layer PDFs.
     */
    private fun extractPdfText(bytes: ByteArray): String {
        val raw    = String(bytes, Charsets.ISO_8859_1)
        val tokens = mutableListOf<String>()
        val regex  = Regex("\\(([^)]{1,400})\\)")
        regex.findAll(raw).forEach { tokens.add(it.groupValues[1]) }
        val text = tokens
            .filter { it.any { c -> c.isLetterOrDigit() || c.isWhitespace() } }
            .joinToString(" ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return text.ifBlank { "[PDF appears to be image-only — no text layer found]" }
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
