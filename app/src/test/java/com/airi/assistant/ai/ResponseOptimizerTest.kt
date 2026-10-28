package com.airi.assistant.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseOptimizerTest {
    @Test
    fun localOutputNeverExceedsRemainingContext() {
        assertEquals(920, ResponseOptimizer.localOutputTokenCap(2048, 1000, 1200))
        assertEquals(1, ResponseOptimizer.localOutputTokenCap(2048, 2000, 1200))
        assertEquals(384, ResponseOptimizer.localOutputTokenCap(4096, 1000, 384))
    }

    @Test
    fun simpleRoutingStillAllowsACompleteGenerationBudget() {
        val config = ResponseOptimizer.adaptiveGeneration(
            queryType = QueryType.SIMPLE,
            ramCappedMaxTokens = 4096,
            recentP90Ms = -1L,
            isPremium = true,
        )
        assertEquals(1024, config.maxTokens)
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
        val userInput = "حلّل هذا الكود واقترح تحسينات"
        val response = "```kotlin\nclass Example { fun run() {} }\n``` This implementation can be reviewed and improved with tests."
        assertEquals(
            listOf("حلّل الكود وحدد الأخطاء المحتملة واقترح تحسينات عملية", "أضف اختبارات ومعالجة واضحة للأخطاء لهذا التنفيذ", "حسّن الكود من حيث الأداء والأمان وسهولة الصيانة"),
            ResponseOptimizer.generateSuggestions(userInput, response, "ar"),
        )
        assertEquals(
            listOf("Analyze the code, identify likely bugs, and suggest practical improvements", "Add tests and error handling for this implementation", "Optimize this code for performance, security, and maintainability"),
            ResponseOptimizer.generateSuggestions(userInput, response, "en"),
        )
        assertEquals(
            listOf("Analiza el código, detecta errores y propone mejoras prácticas", "Añade pruebas y manejo de errores a esta implementación", "Optimiza el código para rendimiento, seguridad y mantenimiento"),
            ResponseOptimizer.generateSuggestions(userInput, response, "es"),
        )
        assertEquals(
            listOf("分析代码并找出潜在错误，提出实用改进", "为这段实现添加测试和错误处理", "从性能、安全性和可维护性方面优化"),
            ResponseOptimizer.generateSuggestions(userInput, response, "zh"),
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

    @Test
    fun longUnrelatedAnswerDoesNotProduceLengthOnlySuggestions() {
        val suggestions = ResponseOptimizer.generateSuggestions(
            userInput = "ما هو الذكاء الاصطناعي؟",
            response = "الذكاء الاصطناعي مجال واسع يضم نماذج تتعلم من البيانات وتستخدم في مجالات متعددة. " +
                "تختلف التطبيقات حسب الهدف والبيانات والقيود، وقد تحتاج إلى تقييم مستمر للنتائج.",
            languageCode = "ar",
        )
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun analyticalAndActionTurnsDoNotShowCasualSmartReplies() {
        val response = "تتضمن المقارنة عدة محاور مهمة، مثل الأداء والتكلفة والخصوصية، ولكل خيار نقاط قوة وضعف مختلفة."
        assertTrue(
            ResponseOptimizer.generateSuggestions(
                userInput = "قارن بين الخيارين بالتفصيل",
                response = response,
                queryType = QueryType.ANALYTICAL,
            ).isEmpty()
        )
        assertTrue(
            ResponseOptimizer.generateSuggestions(
                userInput = "أرسل هذه الرسالة إلى البريد",
                response = response,
                queryType = QueryType.ACTION,
            ).isEmpty()
        )
    }

    @Test
    fun sensitiveOrCredentialContextDoesNotShowSmartReplies() {
        val response = "يمكن التعامل مع هذه الحالة بحذر، لكن من المهم حماية البيانات وطلب المساعدة المناسبة عند الحاجة."
        assertTrue(
            ResponseOptimizer.generateSuggestions(
                userInput = "أين أضع access token وكلمة المرور؟",
                response = response,
            ).isEmpty()
        )
    }

    @Test
    fun openEndedTopicGetsOnlyTopicNeutralContinuationOptions() {
        val suggestions = ResponseOptimizer.generateSuggestions(
            userInput = "حدثني عن الذكاء الاصطناعي في عصرنا الحالي",
            response = "يؤثر الذكاء الاصطناعي في العمل والتعليم والصحة والإبداع، كما يفرض أسئلة حول الخصوصية والمهارات وسوق العمل. " +
                "وتختلف آثاره بحسب طريقة استخدامه والضوابط التي تحيط به.",
            languageCode = "ar",
        )
        assertEquals(3, suggestions.size)
        assertTrue(suggestions.all { it.contains("ذلك") || it.contains("المخاطر") || it.contains("الحياة") })
    }
}
