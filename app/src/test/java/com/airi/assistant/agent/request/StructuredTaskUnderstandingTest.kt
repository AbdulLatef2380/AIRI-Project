package com.airi.assistant.agent.request

import com.airi.assistant.ai.CapabilityIntentDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredTaskUnderstandingTest {
    @Test
    fun calculatorRequestExtractsGoalLanguageAndInterface() {
        val result = StructuredTaskUnderstanding.understand(
            "قم بإنشاء برنامج آلة حاسبة كاملة بلغة Python بواجهة رسومية واحفظه في calculator.py ثم اختبره"
        )
        assertEquals("create_calculator", result.userGoal)
        assertEquals("Python", result.entities["programming_language"])
        assertEquals("Calculator", result.entities["application_type"])
        assertEquals("GUI", result.entities["interface"])
        assertEquals("calculator.py", result.entities["file_name"])
        assertEquals(StructuredTaskUnderstanding.RequestedOutcome.CREATE_FILE_AND_VERIFY, result.requestedOutcome)
        assertTrue(result.requiredCapabilities.contains(CapabilityIntentDetector.Capability.CODE_TASK))
        assertTrue(result.requiredCapabilities.contains(CapabilityIntentDetector.Capability.FILE_WRITE))
        assertTrue(result.requiredCapabilities.contains(CapabilityIntentDetector.Capability.TERMINAL_EXECUTE))
        assertTrue(result.confidence > 0.9)
    }

    @Test
    fun paraphrasedBuildRequestsAreNotDependentOnOneLiteralSentence() {
        val requests = listOf(
            "ساعدني في بناء حاسبة صغيرة باستخدام بايثون",
            "أحتاج تطبيقاً يحسب العمليات الحسابية بـKotlin",
            "create a calculator app in Python",
            "build a simple tool for arithmetic in Java",
        )
        requests.forEach { input ->
            val result = StructuredTaskUnderstanding.understand(input)
            assertEquals(input, StructuredTaskUnderstanding.TaskType.CODE_GENERATION, result.taskType)
            assertEquals(input, "create_calculator", result.userGoal)
        }
    }

    @Test
    fun analysisAndConnectorRequestsHaveDifferentOutcomes() {
        val analysis = StructuredTaskUnderstanding.understand("راجع الكود وابحث عن أخطاء في هذا البرنامج")
        assertEquals(StructuredTaskUnderstanding.TaskType.CODE_ANALYSIS, analysis.taskType)
        assertEquals(StructuredTaskUnderstanding.RequestedOutcome.ANALYZE_AND_REPORT, analysis.requestedOutcome)
        assertFalse(analysis.requiredCapabilities.contains(CapabilityIntentDetector.Capability.FILE_WRITE))

        val connector = StructuredTaskUnderstanding.understand("اعرض رسائل Gmail ومستودعاتي في GitHub")
        assertEquals(StructuredTaskUnderstanding.TaskType.CONNECTOR_OPERATION, connector.taskType)
        assertEquals(StructuredTaskUnderstanding.RequestedOutcome.EXECUTE_OPERATION, connector.requestedOutcome)
        assertTrue(connector.requiredCapabilities.contains(CapabilityIntentDetector.Capability.CONNECTOR_READ))
    }

    @Test
    fun ordinaryConversationDoesNotRequestExecution() {
        val result = StructuredTaskUnderstanding.understand("اكتب لي قصة قصيرة عن مدينة هادئة")
        assertFalse(result.requiredCapabilities.contains(CapabilityIntentDetector.Capability.CODE_TASK))
        assertEquals(StructuredTaskUnderstanding.RequestedOutcome.ANSWER, result.requestedOutcome)
        assertFalse(result.needsClarification)
    }

    @Test
    fun multiStepRequestPreservesSubtasks() {
        val result = StructuredTaskUnderstanding.understand(
            "أنشئ سكربت بايثون ثم احفظه في tool.py ثم اختبره"
        )
        assertEquals(StructuredTaskUnderstanding.TaskType.MULTI_STEP, result.taskType)
        assertTrue(result.subtasks.size >= 2)
        assertEquals(StructuredTaskUnderstanding.RequestedOutcome.CREATE_FILE_AND_VERIFY, result.requestedOutcome)
    }
}
