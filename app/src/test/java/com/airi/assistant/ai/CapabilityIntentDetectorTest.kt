package com.airi.assistant.ai

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
    fun genericArabicCapabilityQuestionRequestsAllConnectors() {
        val intent = CapabilityIntentDetector.detect("ما هي الأدوات والمهارات والموصلات المتاحة؟")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(intent.connectorIds.contains("*"))
        assertTrue(intent.requiresTools)
        assertEquals(QueryType.ANALYTICAL, QueryClassifier.classifyQuery("ما هي الأدوات والمهارات والموصلات المتاحة؟"))
    }

    @Test
    fun ArabicAllConnectorsTargetIsNotDroppedByWildcardHandling() {
        val intent = CapabilityIntentDetector.detect("اعرض الموصلات المتصلة")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ))
        assertTrue(intent.connectorIds.contains("*"))
    }

    @Test
    fun creativeRequestWithoutLiveDependencyNeedsNoTools() {
        assertTrue(CapabilityIntentDetector.detect("اكتب لي قصة قصيرة").capabilities.isEmpty())
    }

    @Test
    fun arabicDeviceCommandsRequireDeviceAction() {
        assertTrue(CapabilityIntentDetector.detect("قم بتشغيل فلاش الجهاز")
            .requires(CapabilityIntentDetector.Capability.DEVICE_ACTION))
        assertTrue(CapabilityIntentDetector.detect("شغّل الواي فاي")
            .requires(CapabilityIntentDetector.Capability.DEVICE_ACTION))
        assertTrue(CapabilityIntentDetector.detect("أطفئ البلوتوث")
            .requires(CapabilityIntentDetector.Capability.DEVICE_ACTION))
    }

    @Test
    fun deviceCommandsAreActionsEvenWhenShort() {
        assertEquals(QueryType.ACTION, QueryClassifier.classifyQuery("شغّل الواي فاي"))
    }

    @Test
    fun terminalCommandRequestsToolsAndActionProfile() {
        val intent = CapabilityIntentDetector.detect("اعرض ملفات sandbox")
        assertTrue(intent.requires(CapabilityIntentDetector.Capability.TERMINAL_EXECUTE))
        assertTrue(intent.requiresTools)
        assertEquals(QueryType.ACTION, QueryClassifier.classifyQuery("اعرض ملفات sandbox"))
    }

    @Test
    fun terminalMentionWithoutCommandDoesNotAuthorizeExecution() {
        val intent = CapabilityIntentDetector.detect("ماذا عن terminal؟")
        assertTrue(intent.capabilities.isEmpty())
        assertEquals(QueryType.SIMPLE, QueryClassifier.classifyQuery("ماذا عن terminal؟"))
    }
}
