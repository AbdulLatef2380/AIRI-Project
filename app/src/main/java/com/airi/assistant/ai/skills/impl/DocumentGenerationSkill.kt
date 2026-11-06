package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillParamDef
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.airi.assistant.attachments.GeneratedArtifactService
import com.airi.assistant.core.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Generates real packaged documents and returns an internal AIRI artifact link. */
class DocumentGenerationSkill(private val context: Context) : AiriSkill {
    override val skillId = "document_generator"
    override val name = skillId
    override val displayName = "Document Generator"
    override val description = "Generate downloadable ODT, ODS, ODP, EPUB, Markdown, CSV, JSON, HTML, or text files"
    override val version = "1.0.0"
    override val author = "AIRI Official"
    override val category = "DOCUMENTS"
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.NONE
    override val modelAccess = SkillModelAccess.NONE
    override val supportsAttachments = false
    override val parameters = mapOf(
        "fileName" to "string — output file name including extension",
        "content" to "string — document content",
        "sessionId" to "string — active AIRI workspace session (optional)",
    )
    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name = "generate_document",
            description = "Create a real downloadable document artifact",
            parameters = mapOf(
                "fileName" to SkillParamDef("string", "Output name: report.odt, book.epub, data.ods, etc."),
                "content" to SkillParamDef("string", "Content to put in the document"),
                "sessionId" to SkillParamDef("string", "Workspace session", required = false),
            ),
        ),
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        val format = listOf(".odt", ".ods", ".odp", ".epub", "markdown file", "csv file", "generate a document")
        return (format.count(lower::contains) * 35).coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
        val fileName = params["fileName"]?.toString().orEmpty()
        val content = params["content"]?.toString().orEmpty()
        val sessionId = params["sessionId"]?.toString()?.ifBlank { null }
            ?: (params["context"] as? SkillContext)?.sessionId.orEmpty()
        if (fileName.isBlank() || content.isBlank() || sessionId.isBlank()) {
            return@withContext SkillResult(false, "", "fileName, content, and an active session are required", skillId)
        }
        runCatching {
            val result = GeneratedArtifactService(ServiceLocator.artifactManager).generateDocument(
                sessionId = sessionId,
                fileName = fileName,
                content = content,
            )
            SkillResult(
                success = true,
                data = "Generated ${result.markdown()}\nDownload: ${result.downloadUri ?: result.link}",
                skillName = skillId,
                metadata = mapOf("artifactId" to result.artifactId, "mimeType" to result.mimeType, "link" to result.link),
            )
        }.getOrElse { error ->
            SkillResult(false, "", "Document generation failed: ${error.message ?: "unknown error"}", skillId)
        }
    }
}
