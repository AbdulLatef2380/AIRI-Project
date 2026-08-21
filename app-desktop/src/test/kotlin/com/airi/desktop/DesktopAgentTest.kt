package com.airi.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopAgentTest {
    @Test
    fun submitCreatesSharedPlanAndStoresConversation() {
        val directory = Files.createTempDirectory("airi-desktop-agent")
        val store = DesktopConversationStore(directory.resolve("conversation.log"))
        val agent = DesktopAgent(store) { 1_000L }

        val reply = assertNotNull(agent.submit("رتب المهام المهمة"))

        assertEquals("معالجة طلب سطح المكتب محلياً", reply.plan.intent)
        assertEquals(1, reply.goal.steps.size)
        assertEquals(2, agent.history().size)
        assertTrue(reply.message.body.contains("AIRI Core"))
    }

    @Test
    fun historySurvivesNewAgentInstance() {
        val directory = Files.createTempDirectory("airi-desktop-history")
        val file = directory.resolve("conversation.log")
        val store = DesktopConversationStore(file)
        DesktopAgent(store) { 2_000L }.submit("حفظ الجلسة")

        val restored = DesktopAgent(DesktopConversationStore(file)).history()

        assertEquals(2, restored.size)
        assertEquals(DesktopSpeaker.USER, restored.first().speaker)
        assertEquals("حفظ الجلسة", restored.first().body)
        assertEquals(DesktopSpeaker.AIRI, restored.last().speaker)
    }
}
