package com.airi.assistant.ai.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeGenerationGuidancePolicyTest {
    @Test
    fun recognizesArabicAndEnglishCodeRequests() {
        assertTrue(CodeGenerationGuidancePolicy.appliesTo("اكتب كود آلة حاسبة بلغة python"))
        assertTrue(CodeGenerationGuidancePolicy.appliesTo("Write a Kotlin function that parses JSON"))
    }

    @Test
    fun recognizesSpanishAndChineseCodeRequests() {
        assertTrue(CodeGenerationGuidancePolicy.appliesTo("Escribe código Python para una calculadora"))
        assertTrue(CodeGenerationGuidancePolicy.appliesTo("请编写一个 Python 程序"))
    }

    @Test
    fun leavesOrdinaryRequestsUnchanged() {
        assertFalse(CodeGenerationGuidancePolicy.appliesTo("اشرح لي تاريخ الأندلس"))
        assertTrue(CodeGenerationGuidancePolicy.systemPromptSuffix("اشرح لي تاريخ الأندلس").isEmpty())
    }

    @Test
    fun reviewInstructionDoesNotClaimExecutionAndExplainsOutputLimits() {
        val instruction = CodeGenerationGuidancePolicy.systemPromptSuffix("Generate a Python script")
        assertTrue(instruction.contains("Never claim that code was run"))
        assertTrue(instruction.contains("clearly label the result as partial"))
        assertTrue(instruction.contains("isolated validation tool"))
    }
}
