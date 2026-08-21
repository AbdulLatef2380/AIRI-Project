package com.airi.desktop

import com.airi.core.models.ModelDescriptor
import com.airi.core.models.ModelRegistry
import com.airi.core.models.ModelSelectionResult
import com.airi.core.planning.ActionPlan
import com.airi.core.planning.AgentGoal
import com.airi.core.planning.PlanStep
import com.airi.core.skills.AiriPlatform
import com.airi.core.skills.SkillDescriptor
import com.airi.core.skills.SkillRegistry
import com.airi.core.skills.SkillSelectionResult
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.Base64

private const val RECORD_SEPARATOR = "|"
private const val ATTACHMENT_SEPARATOR = ","
private const val ATTACHMENT_FIELD_SEPARATOR = ";"

enum class DesktopSpeaker {
    USER,
    AIRI
}

data class DesktopMessage(
    val id: String,
    val speaker: DesktopSpeaker,
    val body: String,
    val timestampMillis: Long,
    val attachments: List<DesktopAttachment> = emptyList()
)

enum class DesktopExecutionStatus {
    AWAITING_MODEL_CONFIGURATION
}

data class DesktopReply(
    val message: DesktopMessage,
    val goal: AgentGoal,
    val plan: ActionPlan,
    val status: DesktopExecutionStatus
)

class DesktopConversationStore(
    private val file: Path = Path.of(System.getProperty("user.home"), ".airi-desktop", "foundation-session.log")
) {
    fun load(): List<DesktopMessage> {
        if (!Files.exists(file)) return emptyList()

        return Files.readAllLines(file, UTF_8).mapNotNull { line ->
            val parts = line.split(RECORD_SEPARATOR, limit = 5)
            if (parts.size < 4) return@mapNotNull null

            val speaker = runCatching { DesktopSpeaker.valueOf(parts[1]) }.getOrNull()
                ?: return@mapNotNull null
            val body = runCatching {
                String(Base64.getDecoder().decode(parts[3]), UTF_8)
            }.getOrNull() ?: return@mapNotNull null
            val timestamp = parts[2].toLongOrNull() ?: return@mapNotNull null

            DesktopMessage(
                id = parts[0],
                speaker = speaker,
                body = body,
                timestampMillis = timestamp,
                attachments = parts.getOrNull(4)?.let(::decodeAttachments).orEmpty()
            )
        }
    }

    fun append(message: DesktopMessage) {
        Files.createDirectories(file.parent)
        val encodedBody = Base64.getEncoder().encodeToString(message.body.toByteArray(UTF_8))
        val record = listOf(
            message.id,
            message.speaker.name,
            message.timestampMillis,
            encodedBody,
            encodeAttachments(message.attachments)
        ).joinToString(separator = RECORD_SEPARATOR)
        Files.writeString(file, "$record\n", UTF_8, CREATE, APPEND)
    }

    fun clear(): List<DesktopMessage> {
        val messages = load()
        Files.deleteIfExists(file)
        return messages
    }

    private fun encodeAttachments(attachments: List<DesktopAttachment>): String =
        attachments.joinToString(ATTACHMENT_SEPARATOR) { attachment ->
            listOf(
                attachment.id,
                Base64.getUrlEncoder().withoutPadding().encodeToString(attachment.displayName.toByteArray(UTF_8)),
                attachment.contentType.name,
                attachment.sizeBytes,
                attachment.storedFileName
            ).joinToString(ATTACHMENT_FIELD_SEPARATOR)
        }

    private fun decodeAttachments(value: String): List<DesktopAttachment> =
        value.split(ATTACHMENT_SEPARATOR).mapNotNull { record ->
            val parts = record.split(ATTACHMENT_FIELD_SEPARATOR, limit = 5)
            if (parts.size != 5) return@mapNotNull null
            val displayName = runCatching {
                String(Base64.getUrlDecoder().decode(parts[1]), UTF_8)
            }.getOrNull() ?: return@mapNotNull null
            val contentType = runCatching {
                com.airi.core.attachments.AttachmentPolicy.ContentType.valueOf(parts[2])
            }.getOrNull() ?: return@mapNotNull null
            val sizeBytes = parts[3].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            if (parts[0].isBlank() || parts[4].isBlank()) return@mapNotNull null
            DesktopAttachment(parts[0], displayName, contentType, sizeBytes, parts[4])
        }
}

class DesktopAgent(
    private val conversationStore: DesktopConversationStore = DesktopConversationStore(),
    private val attachmentStore: DesktopAttachmentStore = DesktopAttachmentStore(),
    private val models: List<ModelDescriptor> = DesktopCapabilities.models,
    private val skills: List<SkillDescriptor> = DesktopCapabilities.skills,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val stagedAttachments = mutableListOf<DesktopAttachment>()
    private var selectedModelId: String? = ModelRegistry.defaultReady(models)?.id
    private var selectedSkillId: String? = null

    fun history(): List<DesktopMessage> = conversationStore.load()

    fun availableModels(): List<ModelDescriptor> = ModelRegistry.ordered(models)

    fun availableSkills(): List<SkillDescriptor> = SkillRegistry.ordered(skills)

    fun selectedModel(): ModelDescriptor? = selectedModelId?.let { selectedId ->
        models.firstOrNull { it.id == selectedId }
    }

    fun selectedSkill(): SkillDescriptor? = selectedSkillId?.let { selectedId ->
        skills.firstOrNull { it.id == selectedId }
    }

    fun selectModel(modelId: String): ModelSelectionResult {
        val result = ModelRegistry.select(models, modelId)
        if (result is ModelSelectionResult.Selected) selectedModelId = result.model.id
        return result
    }

    fun selectSkill(skillId: String): SkillSelectionResult {
        val result = SkillRegistry.select(skills, skillId, AiriPlatform.DESKTOP)
        if (result is SkillSelectionResult.Selected) selectedSkillId = result.skill.id
        return result
    }

    fun stagedAttachments(): List<DesktopAttachment> = stagedAttachments.toList()

    fun stageAttachment(source: Path): DesktopAttachmentResult {
        val result = attachmentStore.stage(source, stagedAttachments.size)
        if (result is DesktopAttachmentResult.Accepted) stagedAttachments += result.attachment
        return result
    }

    fun discardStagedAttachment(attachmentId: String) {
        val attachment = stagedAttachments.firstOrNull { it.id == attachmentId } ?: return
        stagedAttachments.remove(attachment)
        attachmentStore.delete(attachment)
    }

    fun submit(input: String): DesktopReply? {
        val request = input.trim()
        if (request.isEmpty()) return null

        val timestamp = clock()
        val pendingAttachments = stagedAttachments.toList()
        stagedAttachments.clear()
        val userMessage = DesktopMessage(
            id = "user-$timestamp",
            speaker = DesktopSpeaker.USER,
            body = request,
            timestampMillis = timestamp,
            attachments = pendingAttachments
        )
        conversationStore.append(userMessage)

        val planStep = PlanStep.Custom(
            id = "desktop-model-configuration",
            action = "configure_desktop_model",
            parameters = mapOf("platform" to "desktop"),
            expectedOutcome = "تهيئة محرك نموذج متوافق مع سطح المكتب"
        )
        val goal = AgentGoal(
            id = "desktop-goal-$timestamp",
            description = request,
            steps = listOf(planStep)
        )
        val plan = ActionPlan(
            intent = "انتظار إعداد نموذج سطح المكتب",
            confidence = 1.0,
            steps = goal.steps
        )
        val assistantMessage = DesktopMessage(
            id = "airi-${timestamp + 1}",
            speaker = DesktopSpeaker.AIRI,
            body = noModelConfiguredMessage(),
            timestampMillis = timestamp + 1
        )
        conversationStore.append(assistantMessage)

        return DesktopReply(
            message = assistantMessage,
            goal = goal,
            plan = plan,
            status = DesktopExecutionStatus.AWAITING_MODEL_CONFIGURATION
        )
    }

    fun clearHistory() {
        val messages = conversationStore.clear()
        messages.flatMap { it.attachments }.forEach(attachmentStore::delete)
        stagedAttachments.forEach(attachmentStore::delete)
        stagedAttachments.clear()
    }

    private fun noModelConfiguredMessage(): String =
        "لا يوجد نموذج جاهز للتنفيذ على AIRI Desktop. اختر مزوداً متوافقاً أو أتم إعداد محول نموذج Desktop قبل إرسال الطلب."
}
