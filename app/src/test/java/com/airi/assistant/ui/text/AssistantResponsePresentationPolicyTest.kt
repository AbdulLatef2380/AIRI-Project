package com.airi.assistant.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantResponsePresentationPolicyTest {
    @Test
    fun plainTextUsesProseWithoutInteractiveSurface() {
        val plan = AssistantResponsePresentationPolicy.plan("مرحباً، هذا رد قصير.")
        assertEquals(AssistantResponseStyle.PLAIN_TEXT, plan.style)
        assertEquals(AssistantResponseSurface.PROSE, plan.surface)
        assertFalse(plan.supportsGenerativeUi)
    }

    @Test
    fun comparisonUsesTableAndEmphasis() {
        val plan = AssistantResponsePresentationPolicy.plan("قارن بين الهاتفين من حيث السعر والأداء")
        assertEquals(AssistantResponseStyle.COMPARISON, plan.style)
        assertEquals(AssistantResponseSurface.TABLE, plan.surface)
        assertTrue(plan.emphasizeKeyPoints)
        assertFalse(plan.supportsGenerativeUi)
    }

    @Test
    fun documentsAndCodeKeepAttachmentAwareSurfaces() {
        val document = AssistantResponsePresentationPolicy.plan("أنشئ تقريراً بصيغة PDF")
        val code = AssistantResponsePresentationPolicy.plan("```kotlin\nval answer = 42\n```")
        assertEquals(AssistantResponseStyle.DOCUMENT, document.style)
        assertTrue(document.supportsAttachments)
        assertEquals(AssistantResponseSurface.CODE_VIEWER, code.surface)
        assertTrue(code.supportsAttachments)
    }

    @Test
    fun arabicMixedContentPreservesDirectionAndDataSurface() {
        val plan = AssistantResponsePresentationPolicy.plan("مخطط المبيعات chart: يناير 10، فبراير 20")
        assertEquals(AssistantResponseStyle.DATA_VISUAL, plan.style)
        assertEquals(AssistantResponseSurface.CHART_OR_DIAGRAM, plan.surface)
        assertTrue(plan.direction == AssistantContentKind.RTL_TEXT || plan.direction == AssistantContentKind.MIXED_DIRECTION)
    }

    @Test
    fun generativeUiIsOnlyAcceptedThroughTheExplicitNativeSurface() {
        val plan = AssistantResponsePresentationPolicy.plan("```airi-ui\n{\"version\":1,\"blocks\":[{\"type\":\"stat\",\"id\":\"s\",\"value\":\"42\"}]}\n```")
        assertTrue(plan.supportsGenerativeUi)
        assertEquals(AssistantResponseSurface.COMPOSED, plan.surface)
    }

    @Test
    fun longToolAndAttachmentSessionsPreserveContextSignals() {
        val plan = AssistantResponsePresentationPolicy.plan(
            "حلل الملفات ثم أنشئ عرض تقديمي بالنتائج",
            context = AssistantConversationContext(
                messageCount = 36,
                totalChars = 24_000,
                hasAttachments = true,
                hasToolOutputs = true,
                hasRemoteSources = true,
                hasSkillsOrConnectors = true,
                sessionAgeHours = 8,
            ),
        )
        assertEquals(AssistantWorkKind.PRESENTATION_CREATION, plan.workKind)
        assertTrue(plan.isLongRunningConversation)
        assertTrue(plan.hasExecutionContext)
        assertTrue(plan.emphasizeKeyPoints)
    }

    @Test
    fun airiUiPayloadIsNeitherCodeNorMapContent() {
        val plan = AssistantResponsePresentationPolicy.plan(
            "```airi-ui\n{\"version\":1,\"blocks\":[{\"type\":\"route\",\"id\":\"trip\",\"stops\":[{\"id\":\"a\",\"label\":\"A\",\"location\":\"X\"},{\"id\":\"b\",\"label\":\"B\",\"location\":\"Y\"}]}]}\n```",
        )
        assertTrue(plan.supportsGenerativeUi)
        assertEquals(AssistantResponseSurface.COMPOSED, plan.surface)
        assertFalse(plan.style == AssistantResponseStyle.CODE)
    }
}
