package com.airi.assistant.brain

import com.airi.assistant.LlamaManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class PlanGenerator(
    private val llamaManager: LlamaManager
) {

    private var strategyLevel = 0

    suspend fun createPlan(input: BrainInput): AgentGoal {

        val prompt = buildPrompt(input)

        val raw = suspendCancellableCoroutine<String> { cont ->
            llamaManager.generate(prompt) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }

        val cleaned = cleanRawJson(raw)

        val dto = PlanParser.parse(cleaned)
            ?: throw IllegalArgumentException("Invalid plan JSON")

        return GoalBuilder.build(dto)
    }

    fun adjustStrategy() {
        strategyLevel++
    }

    fun reduceComplexity() {
        strategyLevel = (strategyLevel + 1).coerceAtMost(3)
    }

    private fun buildPrompt(input: BrainInput): String {
        return """
            أعد الرد JSON فقط.
            مستوى الاستراتيجية: $strategyLevel
            الأمر: ${input.text}
        """.trimIndent()
    }

    private fun cleanRawJson(raw: String): String {
        val start = raw.indexOf("{")
        val end = raw.lastIndexOf("}")
        return if (start != -1 && end != -1)
            raw.substring(start, end + 1)
        else ""
    }
}
