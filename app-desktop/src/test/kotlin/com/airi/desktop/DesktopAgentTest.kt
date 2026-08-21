package com.airi.desktop

import com.airi.core.models.ModelSelectionResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopAgentTest {

    @Test
    fun submitPersistsConversationAndReportsMissingDesktopModel() {
        val directory = Files.createTempDirectory("airi-desktop-agent")
        val store = DesktopConversationStore(directory.resolve("conversation.log"))
        val agent = DesktopAgent(store, DesktopAttachmentStore(directory.resolve("attachments"))) { 1_000L }

        val reply = assertNotNull(agent.submit("رتب المهام المهمة"))

        assertEquals("انتظار إعداد نموذج سطح المكتب", reply.plan.intent)
        assertEquals(1, reply.goal.steps.size)
        assertEquals(DesktopExecutionStatus.AWAITING_MODEL_CONFIGURATION, reply.status)
        assertEquals(2, agent.history().size)
        assertTrue(reply.message.body.contains("لا يوجد نموذج جاهز"))
        assertFalse(reply.message.body.contains("تمت معالجة الطلب محلياً"))
    }

    @Test
    fun unavailableModelSelectionIsRejectedWithoutChangingSelection() {
        val directory = Files.createTempDirectory("airi-desktop-model")
        val agent = DesktopAgent(
            DesktopConversationStore(directory.resolve("conversation.log")),
            DesktopAttachmentStore(directory.resolve("attachments"))
        )

        val selection = agent.selectModel("android-local-llama")

        assertIs<ModelSelectionResult.Rejected>(selection)
        assertEquals("Requires the Android native model runtime.", selection.reason)
        assertEquals(null, agent.selectedModel())
    }

    @Test
    fun attachmentIsCopiedWithoutPersistingSourcePathAndRemovedOnClear() {
        val directory = Files.createTempDirectory("airi-desktop-attachment")
        val source = directory.resolve("source-note.txt")
        Files.writeString(source, "Attachment content")
        val attachmentDirectory = directory.resolve("attachments")
        val agent = DesktopAgent(
            DesktopConversationStore(directory.resolve("conversation.log")),
            DesktopAttachmentStore(attachmentDirectory)
        ) { 3_000L }

        val staged = agent.stageAttachment(source)
        val accepted = assertIs<DesktopAttachmentResult.Accepted>(staged).attachment
        val reply = assertNotNull(agent.submit("اقرأ هذا الملف"))
        val storedPath = attachmentDirectory.resolve(accepted.storedFileName)

        assertTrue(Files.exists(storedPath))
        assertEquals(listOf(accepted), reply.goal.let { agent.history().first().attachments })
        assertFalse(agent.history().first().attachments.first().storedFileName.contains("source-note"))

        agent.clearHistory()

        assertFalse(Files.exists(storedPath))
        assertTrue(agent.history().isEmpty())
    }

    @Test
    fun historySurvivesNewAgentInstance() {
        val directory = Files.createTempDirectory("airi-desktop-history")
        val file = directory.resolve("conversation.log")
        val store = DesktopConversationStore(file)
        DesktopAgent(store, DesktopAttachmentStore(directory.resolve("attachments"))) { 2_000L }
            .submit("حفظ الجلسة")

        val restored = DesktopAgent(
            DesktopConversationStore(file),
            DesktopAttachmentStore(directory.resolve("attachments"))
        ).history()

        assertEquals(2, restored.size)
        assertEquals(DesktopSpeaker.USER, restored.first().speaker)
        assertEquals("حفظ الجلسة", restored.first().body)
        assertEquals(DesktopSpeaker.AIRI, restored.last().speaker)
    }
}
