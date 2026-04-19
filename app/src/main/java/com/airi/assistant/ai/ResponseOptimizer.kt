package com.airi.assistant.ai

import android.util.Log
import com.airi.assistant.memory.entity.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GenerationConfig(
    val temperature: Float,
    val maxTokens: Int
)

object ResponseOptimizer {

    private const val TAG = "AIRI_OPT"

    // ── Fast response table ───────────────────────────────────────────────────

    private data class FastEntry(val keys: List<String>, val replies: List<() -> String>)

    private fun pick(vararg options: String): List<() -> String> = options.map { s -> { s } }

    private val fastTable: List<FastEntry> = listOf(
        FastEntry(
            listOf("what time is it", "what's the time", "current time", "الوقت الآن", "كم الساعة")
        ) {
            val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
            listOf({ "The current time is ${fmt.format(Date())}." })
        },
        FastEntry(
            listOf("what's today's date", "what is today's date", "what day is it",
                   "التاريخ اليوم", "ما هو التاريخ", "today's date")
        ) {
            val fmt = SimpleDateFormat("EEEE, MMMM d yyyy", Locale.getDefault())
            listOf({ "Today is ${fmt.format(Date())}." })
        },
        FastEntry(
            listOf("who are you", "what are you", "من أنت", "ما أنت", "ما اسمك", "introduce yourself")
        ) {
            pick(
                "I'm AIRI — your on-device AI assistant. I run 100% locally, no cloud, no tracking.",
                "AIRI here — an intelligent AI that runs entirely on your device. No cloud, total privacy.",
                "I'm AIRI, an on-device AI assistant. Everything I do stays on your phone."
            )
        },
        FastEntry(
            listOf("hello", "hi there", "hey airi", "hey there", "مرحبا", "أهلاً", "أهلا",
                   "هلا", "السلام عليكم", "good morning", "good evening", "good afternoon")
        ) {
            pick(
                "Hey! How can I help you today?",
                "Hi there! What can I do for you?",
                "Hello! What's on your mind?",
                "Hey! Ready to help — what do you need?",
                "Hi! Ask me anything.",
                "أهلاً! كيف أستطيع مساعدتك؟"
            )
        },
        FastEntry(
            listOf("how are you", "كيف حالك", "كيف الحال", "عامل إيه")
        ) {
            pick(
                "Doing great and ready to help! What's on your mind?",
                "All good here! What can I help you with?",
                "Ready and sharp! What do you need?"
            )
        },
        FastEntry(
            listOf("thank you", "thanks", "شكراً", "شكرا", "شكرًا", "merci", "tnx", "thx", "ty")
        ) {
            pick(
                "You're welcome! Let me know if there's anything else.",
                "Happy to help! Anything else?",
                "Anytime! What else can I do for you?",
                "Of course! Feel free to ask anything.",
                "عفواً! هل تحتاج أي مساعدة أخرى؟"
            )
        },
        FastEntry(
            listOf("are you online", "do you need internet", "هل تحتاج إنترنت", "هل أنت أونلاين",
                   "offline", "no internet", "internet connection")
        ) {
            pick(
                "Nope — I run fully offline on your device. No internet required.",
                "I work 100% offline. No network, no cloud — everything stays on your device."
            )
        },
        FastEntry(
            listOf("what can you do", "what are your capabilities", "help me", "show me what you can do")
        ) {
            pick(
                "I can answer questions, write code, analyze text, summarize, translate, brainstorm, and much more — all offline.",
                "I can help with coding, writing, Q&A, analysis, translations, and creative tasks. What would you like?",
                "Ask me anything: code, writing, summaries, explanations, math, and more. All on-device."
            )
        }
    )

    /**
     * Try to answer instantly without touching the model.
     * Returns null if no shortcut matches → caller must proceed to LLM.
     */
    fun tryFastResponse(input: String): String? {
        val lower = input.trim().lowercase()
        for (entry in fastTable) {
            if (entry.keys.any { lower.contains(it) }) {
                val reply = entry.replies.random()()
                Log.d(TAG, "fast_response matched for len=${input.length}")
                return reply
            }
        }
        return null
    }

    // ── Dynamic generation config ─────────────────────────────────────────────

    /**
     * Returns adjusted temperature + maxTokens for each intent type.
     * The caller's adaptive RAM-based cap is used as an upper bound.
     */
    fun adjustGeneration(queryType: QueryType, ramCappedMaxTokens: Int): GenerationConfig {
        return when (queryType) {
            QueryType.SIMPLE     -> GenerationConfig(temperature = 0.3f, maxTokens = minOf(128,  ramCappedMaxTokens))
            QueryType.ANALYTICAL -> GenerationConfig(temperature = 0.7f, maxTokens = minOf(512,  ramCappedMaxTokens))
            QueryType.ACTION     -> GenerationConfig(temperature = 0.5f, maxTokens = minOf(256,  ramCappedMaxTokens))
            QueryType.CREATIVE   -> GenerationConfig(temperature = 0.9f, maxTokens = minOf(1024, ramCappedMaxTokens))
            QueryType.UNKNOWN    -> GenerationConfig(temperature = 0.7f, maxTokens = minOf(256,  ramCappedMaxTokens))
        }
    }

    /**
     * Returns the token cap driven by input size.
     * Short questions don't need long answers — this prevents runaway generation.
     */
    fun inputSizeTokenCap(inputLength: Int, queryTypeCap: Int): Int {
        return when {
            inputLength < 30  -> minOf(queryTypeCap, 80)
            inputLength < 80  -> minOf(queryTypeCap, 160)
            inputLength < 200 -> minOf(queryTypeCap, 320)
            else              -> queryTypeCap
        }
    }

    // ── Smart context trimmer ─────────────────────────────────────────────────

    /**
     * Intelligent context window trimmer:
     *  - Always keeps the system prompt (role == "system")
     *  - Keeps the last [keepLast] non-system messages
     *  - Removes exact-content duplicates
     *  - Truncates oversized non-recent messages (>600 chars)
     *  - Preserves the very last message intact (user's current input)
     */
    fun smartTrim(messages: List<ChatMessage>, keepLast: Int = 6): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()

        val systemMsg = messages.firstOrNull { it.role == "system" }
        val nonSystem = messages.filter { it.role != "system" }

        val recent = nonSystem.takeLast(keepLast)

        val seen    = mutableSetOf<String>()
        val deduped = recent.filter { msg -> seen.add(msg.content.take(60)) }

        val lastIdx = deduped.lastIndex
        val trimmed = deduped.mapIndexed { i, msg ->
            when {
                i == lastIdx -> msg
                msg.role == "assistant" && msg.content.length > 600 ->
                    msg.copy(content = msg.content.take(600) + " […]")
                msg.role == "user" && msg.content.length > 400 ->
                    msg.copy(content = msg.content.take(400) + " […]")
                else -> msg
            }
        }

        return if (systemMsg != null) listOf(systemMsg) + trimmed else trimmed
    }

    // ── Smart reply suggestions ───────────────────────────────────────────────

    /**
     * Generate contextual follow-up chips based on the AI response content.
     * Returns empty list for very short responses.
     */
    fun generateSuggestions(response: String): List<String> {
        if (response.length < 50) return emptyList()
        val lower = response.lowercase()
        return when {
            lower.contains("```") || lower.contains("fun ") ||
            lower.contains("class ") || lower.contains("function") ||
            lower.contains("كود") ->
                listOf("Explain this code", "Add error handling", "Optimize it")

            lower.contains("step") || lower.contains("how to") ||
            lower.contains("1.") || lower.contains("خطوات") ->
                listOf("Explain step 1", "What's next?", "Simplify this")

            lower.contains("because") || lower.contains("therefore") ||
            lower.contains("لأن") || lower.contains("لذلك") ->
                listOf("Explain more", "Give an example", "Summarize")

            lower.contains("compare") || lower.contains("difference") ||
            lower.contains("versus") || lower.contains("vs") ||
            lower.contains("قارن") ->
                listOf("Go deeper", "Which is better?", "Give pros & cons")

            lower.contains("analyze") || lower.contains("analyse") ||
            lower.contains("حلل") ->
                listOf("More detail", "Key takeaways?", "Summarize")

            response.length > 500 ->
                listOf("Summarize this", "Key points?", "Simplify")

            response.length > 200 ->
                listOf("Tell me more", "Give an example")

            else -> emptyList()
        }
    }
}
