package com.airi.assistant.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseOptimizerTest {
    @Test
    fun knownGreetingMayUseTheExplicitFastPath() {
        assertNotNull(ResponseOptimizer.tryFastResponse("hi"))
    }

    @Test
    fun unknownPromptMustReachTheRealExecutionPipeline() {
        val response = ResponseOptimizer.tryFastResponse(
            "Describe the orbital mechanics of a fictional moon with a retrograde rotation."
        )
        assertNull(response)
    }

    @Test
    fun longerPromptContainingGreetingDoesNotUseCannedResponse() {
        val response = ResponseOptimizer.tryFastResponse(
            "Hi, explain how coroutine cancellation interacts with an HTTP stream."
        )
        assertNull(response)
    }

    @Test
    fun localizedUnknownArabicPromptDoesNotReceiveEnglishCannedText() {
        val response = ResponseOptimizer.tryFastResponse("اشرح لي سبب تعاقب الفصول على كوكب افتراضي")
        assertNull(response)
    }

    @Test
    fun smartRepliesFollowTheConfiguredAppLanguage() {
        val response = "```kotlin\nclass Example { fun run() {} }\n``` This implementation can be reviewed and improved with tests."
        assertEquals(
            listOf("اشرح هذا الكود", "أضف معالجة للأخطاء", "حسّنه"),
            ResponseOptimizer.generateSuggestions(response, "ar"),
        )
        assertEquals(
            listOf("Explain this code", "Add error handling", "Optimize it"),
            ResponseOptimizer.generateSuggestions(response, "en"),
        )
        assertEquals(
            listOf("Explica este código", "Añade manejo de errores", "Optimízalo"),
            ResponseOptimizer.generateSuggestions(response, "es"),
        )
        assertEquals(
            listOf("解释这段代码", "添加错误处理", "优化代码"),
            ResponseOptimizer.generateSuggestions(response, "zh"),
        )
    }
}
