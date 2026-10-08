package com.airi.assistant.domain.intent

import com.airi.assistant.ai.QueryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalIntentClassifierTest {
    @Test
    fun greetingsRemainSimpleConversation() {
        assertEquals(ChatIntent.Kind.CONVERSATION, CanonicalIntentClassifier.classify("hello").kind)
        assertEquals(QueryType.SIMPLE, CanonicalIntentClassifier.classify("مرحبا").queryType)
    }

    @Test
    fun shortActionsAreNotDemotedByLength() {
        val english = CanonicalIntentClassifier.classify("open settings")
        val arabic = CanonicalIntentClassifier.classify("افتح الإعدادات")
        assertEquals(ChatIntent.Kind.POSSIBLE_ACTION, english.kind)
        assertEquals(ChatIntent.Kind.POSSIBLE_ACTION, arabic.kind)
        assertEquals(QueryType.ACTION, english.queryType)
        assertEquals(QueryType.ACTION, arabic.queryType)
        assertEquals("open_settings", english.canonicalCapability)
    }

    @Test
    fun sendMessageProducesTypedTargetCandidateWithoutAuthorization() {
        val intent = CanonicalIntentClassifier.classify("send a message to Sarah")
        assertEquals(ChatIntent.Kind.POSSIBLE_ACTION, intent.kind)
        assertEquals(QueryType.ACTION, intent.queryType)
        assertEquals(ChatIntent.Risk.SIDE_EFFECT, intent.risk)
        assertTrue(intent.isExecutableCandidate)
        assertFalse(intent.typedSlots.containsKey("approval"))
    }

    @Test
    fun creativeRequestIsNotAnAction() {
        val intent = CanonicalIntentClassifier.classify("write a poem")
        assertEquals(ChatIntent.Kind.CREATIVE, intent.kind)
        assertEquals(QueryType.CREATIVE, intent.queryType)
        assertFalse(intent.isExecutableCandidate)
    }

    @Test
    fun lowConfidenceInputRequestsClarification() {
        val intent = CanonicalIntentClassifier.classify("something unusual please")
        assertEquals(ChatIntent.Kind.CLARIFICATION, intent.kind)
        assertEquals(ChatIntent.Confidence.LOW, intent.confidence)
        assertFalse(intent.isExecutableCandidate)
    }

    @Test
    fun normalizerPreservesOriginalAndNormalizesArabicForms() {
        val normalized = InputNormalizer.normalize("  أَهْلًا   بِك  ")
        assertEquals("  أَهْلًا   بِك  ", normalized.original)
        assertEquals("اهلا بك", normalized.normalized)
    }
}
