package com.airi.assistant.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseOptimizerTest {
    @Test
    fun localOutputNeverExceedsRemainingContext() {
        assertEquals(872, ResponseOptimizer.localOutputTokenCap(2048, 1000, 1200))
        assertEquals(1, ResponseOptimizer.localOutputTokenCap(2048, 2000, 1200))
        assertEquals(384, ResponseOptimizer.localOutputTokenCap(4096, 1000, 384))
    }

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
            listOf("حلّل الكود وحدد الأخطاء المحتملة واقترح تحسينات عملية", "أضف اختبارات ومعالجة واضحة للأخطاء لهذا التنفيذ", "حسّن الكود من حيث الأداء والأمان وسهولة الصيانة"),
            ResponseOptimizer.generateSuggestions(response, "ar"),
        )
        assertEquals(
            listOf("Analyze the code, identify likely bugs, and suggest practical improvements", "Add tests and error handling for this implementation", "Optimize this code for performance, security, and maintainability"),
            ResponseOptimizer.generateSuggestions(response, "en"),
        )
        assertEquals(
            listOf("Analiza el código, detecta errores y propone mejoras prácticas", "Añade pruebas y manejo de errores a esta implementación", "Optimiza el código para rendimiento, seguridad y mantenimiento"),
            ResponseOptimizer.generateSuggestions(response, "es"),
        )
        assertEquals(
            listOf("分析代码并找出潜在错误，提出实用改进", "为这段实现添加测试和错误处理", "从性能、安全性和可维护性方面优化代码"),
            ResponseOptimizer.generateSuggestions(response, "zh"),
        )
    }

    @Test
    fun suggestionsUseTheUserTaskAndRemainComplete() {
        val suggestions = ResponseOptimizer.generateSuggestions(
            userInput = "قارن بين Gemini وClaude لمشروع Android",
            response = "كلاهما يدعم الاستدعاء الوظيفي، لكن الاختيار يعتمد على التكلفة والسياق والخصوصية.",
            languageCode = "ar",
        )
        assertEquals(3, suggestions.size)
        assertTrue(suggestions.all { it.length > 25 })
        assertTrue(suggestions.first().contains("الأداء"))
    }
}
