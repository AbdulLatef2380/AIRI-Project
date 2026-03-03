package com.airi.assistant.brain

import com.airi.assistant.ai.IntentDetector
import com.airi.assistant.accessibility.ScreenContextHolder
import com.airi.assistant.LlamaManager
import com.airi.core.chain.AgentGoal
import kotlinx.coroutines.*

class AiriBrainController(
    private val llamaManager: LlamaManager
) {

    suspend fun handle(input: BrainInput): BrainOutput = coroutineScope {

        // 1️⃣ استخراج السياق إذا لزم
        val screenContext = if (input.includeScreenContext) {
            ScreenContextHolder.triggerExtraction()
        } else ""

        val enrichedPrompt = buildPrompt(input.text, screenContext)

        // 2️⃣ تحليل النية
        val intent = IntentDetector.detectIntent(enrichedPrompt)

        // 3️⃣ قرار مبدئي
        when {
            intent.contains("execute", ignoreCase = true) -> {
                // لاحقاً سنربط Planner هنا
                return@coroutineScope BrainOutput(
                    responseText = "⚙ تم التعرف على نية تنفيذ — قيد التطوير",
                    executedGoalId = null
                )
            }

            else -> {
                val response = suspendCancellableCoroutine<String> { cont ->
                    llamaManager.generate(enrichedPrompt) {
                        if (cont.isActive) cont.resume(it) {}
                    }
                }

                return@coroutineScope BrainOutput(responseText = response)
            }
        }
    }

    private fun buildPrompt(text: String, context: String): String {
        return if (context.isNotBlank()) {
            """
            سياق الشاشة:
            $context
            
            طلب المستخدم:
            $text
            """.trimIndent()
        } else {
            text
        }
    }
}
