package com.airi.assistant.ai.skills.impl

import android.content.Context
import android.net.Uri
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillParamDef
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.airi.assistant.attachments.MediaArtifactService
import com.airi.assistant.core.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded, local image/audio transforms backed by ArtifactManager. */
class MediaProcessingSkill(private val context: Context) : AiriSkill {
    override val skillId = "media_processor"
    override val name = skillId
    override val displayName = "Media Processor"
    override val description = "Resize/compress images and trim audio locally, then return a downloadable AIRI artifact"
    override val version = "1.0.0"
    override val author = "AIRI Official"
    override val category = "MEDIA"
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.NONE
    override val modelAccess = SkillModelAccess.NONE
    override val supportsAttachments = true
    override val parameters = mapOf(
        "uri" to "string — content URI of an image or audio file",
        "sessionId" to "string — active AIRI workspace session",
        "operation" to "string — image or audio",
        "outputName" to "string — optional output name",
    )
    override val toolDefinitions = listOf(
        SkillToolDefinition("process_image", "Resize and compress an image locally", mapOf(
            "uri" to SkillParamDef("string", "content:// URI of the image"),
            "sessionId" to SkillParamDef("string", "Workspace session"),
            "outputName" to SkillParamDef("string", "Output filename", required = false),
            "maxDimension" to SkillParamDef("int", "Longest side, 64..4096", required = false),
            "quality" to SkillParamDef("int", "JPEG quality, 1..100", required = false),
        )),
        SkillToolDefinition("process_audio", "Trim an audio file locally", mapOf(
            "uri" to SkillParamDef("string", "content:// URI of the audio"),
            "sessionId" to SkillParamDef("string", "Workspace session"),
            "outputName" to SkillParamDef("string", "Output filename", required = false),
            "startMs" to SkillParamDef("int", "Start time in milliseconds", required = false),
            "endMs" to SkillParamDef("int", "End time in milliseconds", required = false),
        )),
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        val media = listOf("image", "photo", "picture", "audio", "sound", "mp3", "m4a", "resize", "compress", "trim")
        return (media.count(lower::contains) * 20).coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
        val uriText = params["uri"]?.toString().orEmpty()
        val sessionId = params["sessionId"]?.toString()?.ifBlank { null }
            ?: (params["context"] as? SkillContext)?.sessionId.orEmpty()
        if (uriText.isBlank() || sessionId.isBlank()) return@withContext SkillResult(false, "", "A media URI and active session are required", skillId)
        val service = MediaArtifactService(context, ServiceLocator.artifactManager)
        runCatching {
            val uri = Uri.parse(uriText)
            val operation = params["operation"]?.toString()?.lowercase()
                ?: if (params.containsKey("maxDimension") || params.containsKey("quality")) "image" else "audio"
            val result = if (operation == "image" || operation == "process_image") {
                service.processImage(sessionId, uri, params["outputName"]?.toString() ?: "airi-image.jpg", intParam(params, "maxDimension", 2048), intParam(params, "quality", 88))
            } else if (operation == "audio" || operation == "process_audio") {
                service.processAudio(sessionId, uri, params["outputName"]?.toString() ?: "airi-audio.m4a", longParam(params, "startMs", 0L), params["endMs"]?.toString()?.toLongOrNull())
            } else throw IllegalArgumentException("operation must be image or audio")
            SkillResult(success = true, data = "Generated ${result.markdown()}\nDownload: ${result.downloadUri ?: result.link}", skillName = skillId, metadata = mapOf("artifactId" to result.artifactId, "mimeType" to result.mimeType, "link" to result.link))
        }.getOrElse { error -> SkillResult(false, "", "Media processing failed: ${error.message ?: "unknown error"}", skillId) }
    }

    private fun intParam(params: Map<String, Any>, key: String, default: Int): Int = params[key]?.toString()?.toIntOrNull() ?: default
    private fun longParam(params: Map<String, Any>, key: String, default: Long): Long = params[key]?.toString()?.toLongOrNull() ?: default
}
