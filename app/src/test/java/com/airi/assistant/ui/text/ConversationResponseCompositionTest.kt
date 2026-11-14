package com.airi.assistant.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationResponseCompositionTest {
    @Test
    fun plainShortQuestionUsesCompactConversationLane() {
        val plan = ConversationResponseCompositionPlanner.plan(
            ResponseRequestProfile(userText = "ما الوقت الآن؟", answerText = "الساعة 10:30")
        )
        assertEquals(ResponseLane.CONVERSATION, plan.lane)
        assertEquals(ResponseDensity.COMPACT, plan.density)
        assertFalse(plan.discloseExecution)
        assertTrue(ResponseSectionKind.ANSWER in plan.sections)
    }

    @Test
    fun comparisonWithRemoteEvidenceGetsTableSourcesAndTrace() {
        val plan = ConversationResponseCompositionPlanner.plan(
            ResponseRequestProfile(
                userText = "قارن بين الخيارين من مصادر بعيدة",
                answerText = "| الخيار | السعر |\n|---|---|\n| A | 10 |",
                evidence = ResponseEvidence(hasRemoteSources = true, hasToolResults = true),
            )
        )
        assertEquals(ResponseLane.COMPARISON, plan.lane)
        assertTrue(ResponseSectionKind.TABLE in plan.sections)
        assertTrue(ResponseSectionKind.SOURCES in plan.sections)
        assertTrue(ResponseSectionKind.TOOL_RESULTS in plan.sections)
        assertTrue(ResponseSectionKind.TRACE in plan.sections)
        assertTrue(plan.discloseUncertainty)
    }

    @Test
    fun fileAndAttachmentWorkKeepsArtifactAndAttachmentSections() {
        val plan = ConversationResponseCompositionPlanner.plan(
            ResponseRequestProfile(
                userText = "أنشئ تقريرًا من الملف المرفق",
                answerText = "تم إنشاء التقرير.",
                evidence = ResponseEvidence(hasAttachments = true, hasFiles = true),
            )
        )
        assertEquals(ResponseLane.ARTIFACT, plan.lane)
        assertTrue(ResponseSectionKind.ARTIFACTS in plan.sections)
        assertTrue(ResponseSectionKind.ATTACHMENTS in plan.sections)
        assertTrue(plan.preserveInteractiveState)
    }

    @Test
    fun streamingNeverEmitsGenerativeUiButPreservesStateForProgressiveRendering() {
        val plan = ConversationResponseCompositionPlanner.plan(
            ResponseRequestProfile(
                userText = "اعرض مخططًا تفاعليًا",
                answerText = "```airi-ui\n{partial",
                isStreaming = true,
            )
        )
        assertTrue(plan.progressive)
        assertFalse(plan.allowGenerativeUi)
        assertTrue(plan.preserveInteractiveState)
    }

    @Test
    fun mediaAndWorkspaceOutputsUseDistinctDeliveryLanes() {
        val media = ConversationResponseCompositionPlanner.plan(
            ResponseRequestProfile(
                userText = "أنشئ صورة توضيحية",
                answerText = "تم إنشاء الصورة.",
                evidence = ResponseEvidence(hasImages = true),
            )
        )
        val workspace = ConversationResponseCompositionPlanner.plan(
            ResponseRequestProfile(
                userText = "أنشئ لعبة",
                answerText = "تم تجهيز مساحة اللعبة.",
                evidence = ResponseEvidence(hasGame = true),
            )
        )
        assertEquals(ResponseLane.MEDIA, media.lane)
        assertTrue(ResponseSectionKind.MEDIA in media.sections)
        assertEquals(ResponseLane.WORKSPACE, workspace.lane)
        assertTrue(ResponseSectionKind.ACTIONS in workspace.sections)
    }
}
