package com.airi.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryClassifierDeviceActionTest {
    @Test
    fun arabicDeviceCommandsDoNotUseSimpleChatFastPath() {
        listOf(
            "قم بتشغيل فلاش الجهاز",
            "شغّل الواي فاي",
            "فعّل البلوتوث",
            "أطفئ نقطة الاتصال",
        ).forEach { input ->
            assertEquals(input, QueryType.ACTION, QueryClassifier.classifyQuery(input))
            assertTrue(CapabilityIntentDetector.detect(input).requiresTools)
        }
    }

    @Test
    fun englishDeviceCommandIsAnAction() {
        assertEquals(QueryType.ACTION, QueryClassifier.classifyQuery("turn on the flashlight"))
    }
}
