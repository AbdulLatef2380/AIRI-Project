package com.airi.assistant.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityIntentDetectorTest {
    @Test
    fun arabicDateVariantsRequireCurrentTime() {
        assertTrue(CapabilityIntentDetector.detect("كم اليوم بالتاريخ الميلادي؟")
            .requires(CapabilityIntentDetector.Capability.CURRENT_TIME))
        assertTrue(CapabilityIntentDetector.detect("ما تاريخ اليوم ميلادي؟")
            .requires(CapabilityIntentDetector.Capability.CURRENT_TIME))
    }

    @Test
    fun memoryQuestionRequiresMemoryRead() {
        val intent = CapabilityIntentDetector.detect("ماذا تتذكر عني؟")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.MEMORY_READ))
        assertTrue(intent.requiresTools)
    }

    @Test
    fun gmailSummaryRequiresGoogleConnectorRead() {
        val intent = CapabilityIntentDetector.detect("لخص لي آخر رسائل Gmail")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(intent.connectorIds.contains("google"))
    }

    @Test
    fun creativeRequestWithoutLiveDependencyNeedsNoTools() {
        assertTrue(CapabilityIntentDetector.detect("اكتب لي قصة قصيرة").capabilities.isEmpty())
    }
}
