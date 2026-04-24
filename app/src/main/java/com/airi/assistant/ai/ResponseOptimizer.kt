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

data class SemanticCutResult(
    val text: String,
    val wasCut: Boolean,
    val reason: String
)

object ResponseOptimizer {

    private const val TAG = "AIRI_OPTIMIZE"

    // ── Fast response table ───────────────────────────────────────────────────

    private data class FastEntry(val keys: List<String>, val replies: List<() -> String>)

    private fun pick(vararg options: String): List<() -> String> = options.map { s -> { s } }

    private val fastTable: List<FastEntry> = listOf(
        FastEntry(
            listOf("what time is it", "what's the time", "current time", "الوقت الآن", "كم الساعة"),
            listOf({
                val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
                "The current time is ${fmt.format(Date())}."
            })
        ),
        FastEntry(
            listOf("what's today's date", "what is today's date", "what day is it",
                   "التاريخ اليوم", "ما هو التاريخ", "today's date"),
            listOf({
                val fmt = SimpleDateFormat("EEEE, MMMM d yyyy", Locale.getDefault())
                "Today is ${fmt.format(Date())}."
            })
        ),
        FastEntry(
            listOf("who are you", "what are you", "من أنت", "ما أنت", "ما اسمك", "introduce yourself"),
            pick(
                "I'm AIRI — your on-device AI assistant. I run 100% locally, no cloud, no tracking.",
                "AIRI here — an intelligent AI that runs entirely on your device. No cloud, total privacy.",
                "I'm AIRI, an on-device AI assistant. Everything I do stays on your phone."
            )
        ),
        FastEntry(
            listOf("hello", "hi there", "hey airi", "hey there", "hi", "hey",
                   "مرحبا", "أهلاً", "أهلا",
                   "هلا", "السلام عليكم", "good morning", "good evening", "good afternoon"),
            pick(
                "Hey! How can I help you today?",
                "Hi there! What can I do for you?",
                "Hello! What's on your mind?",
                "Hey! Ready to help — what do you need?",
                "Hi! Ask me anything.",
                "أهلاً! كيف أستطيع مساعدتك؟"
            )
        ),
        FastEntry(
            listOf("how are you", "كيف حالك", "كيف الحال", "عامل إيه"),
            pick(
                "Doing great and ready to help! What's on your mind?",
                "All good here! What can I help you with?",
                "Ready and sharp! What do you need?"
            )
        ),
        FastEntry(
            listOf("thank you", "thanks", "شكراً", "شكرا", "شكرًا", "merci", "tnx", "thx", "ty"),
            pick(
                "You're welcome! Let me know if there's anything else.",
                "Happy to help! Anything else?",
                "Anytime! What else can I do for you?",
                "Of course! Feel free to ask anything.",
                "عفواً! هل تحتاج أي مساعدة أخرى؟"
            )
        ),
        FastEntry(
            listOf("are you online", "do you need internet", "هل تحتاج إنترنت", "هل أنت أونلاين",
                   "offline", "no internet", "internet connection"),
            pick(
                "Nope — I run fully offline on your device. No internet required.",
                "I work 100% offline. No network, no cloud — everything stays on your device."
            )
        ),
        FastEntry(
            listOf("what can you do", "what are your capabilities", "help me", "show me what you can do"),
            pick(
                "I can answer questions, write code, analyze text, summarize, translate, brainstorm, and much more — all offline.",
                "I can help with coding, writing, Q&A, analysis, translations, and creative tasks. What would you like?",
                "Ask me anything: code, writing, summaries, explanations, math, and more. All on-device."
            )
        )
    )

    /**
     * Try to answer instantly without touching the model.
     * Returns null if no shortcut matches → caller must proceed to LLM.
     *
     * Matching rules (anti-overmatch — see Bug A):
     *   - Multi-word keys (≥ 2 tokens or contains a space)  → substring match
     *   - Single-word keys                                  → exact whole-input
     *                                                         OR exact phrase as a
     *                                                         standalone token
     * This prevents "this", "history", "high", "child", etc. from triggering the
     * "hi" greeting bucket and returning "Hi! Ask me anything." for real prompts.
     */
    fun tryFastResponse(input: String): String? {
        val raw   = input.trim()
        val lower = raw.lowercase()
        if (lower.isEmpty()) return null

        // Tokenize on whitespace + Arabic-aware punctuation so we can do
        // word-boundary matching for short keys.
        val tokens = lower.split(Regex("[\\s\\p{Punct}،؛؟]+")).filter { it.isNotBlank() }

        for (entry in fastTable) {
            val matched = entry.keys.any { key ->
                val k = key.trim().lowercase()
                if (k.isEmpty()) return@any false
                val isMultiWord = k.contains(' ')
                if (isMultiWord) {
                    // Multi-word phrase — keep substring semantics.
                    lower.contains(k)
                } else {
                    // Single-word key — exact whole input OR a standalone token.
                    lower == k || tokens.contains(k)
                }
            }
            if (matched) {
                val reply = entry.replies.random()()
                Log.d(TAG, "fast_response matched len=${input.length} reply_len=${reply.length}")
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
        return adaptiveGeneration(queryType, ramCappedMaxTokens, recentP90Ms = -1L, isPremium = true)
    }

    fun adaptiveGeneration(
        queryType: QueryType,
        ramCappedMaxTokens: Int,
        recentP90Ms: Long,
        isPremium: Boolean
    ): GenerationConfig {
        val base = when (queryType) {
            QueryType.SIMPLE     -> GenerationConfig(temperature = 0.3f, maxTokens = minOf(128,  ramCappedMaxTokens))
            QueryType.ANALYTICAL -> GenerationConfig(temperature = 0.7f, maxTokens = minOf(512,  ramCappedMaxTokens))
            QueryType.ACTION     -> GenerationConfig(temperature = 0.5f, maxTokens = minOf(256,  ramCappedMaxTokens))
            QueryType.CREATIVE   -> GenerationConfig(temperature = 0.9f, maxTokens = minOf(1024, ramCappedMaxTokens))
            QueryType.UNKNOWN    -> GenerationConfig(temperature = 0.7f, maxTokens = minOf(256,  ramCappedMaxTokens))
        }
        val latencyFactor = when {
            recentP90Ms >= 9000L -> 0.55f
            recentP90Ms >= 6000L -> 0.7f
            recentP90Ms >= 4000L -> 0.85f
            else                 -> 1.0f
        }
        val tierFactor = if (isPremium) 1.0f else 0.9f
        val tunedTokens = (base.maxTokens * latencyFactor * tierFactor).toInt()
            .coerceAtLeast(if (queryType == QueryType.SIMPLE) 48 else 96)
            .coerceAtMost(base.maxTokens)
        val tunedTemperature = when {
            recentP90Ms >= 6000L && queryType != QueryType.CREATIVE -> (base.temperature - 0.1f).coerceAtLeast(0.2f)
            else -> base.temperature
        }
        Log.d(TAG, "adaptive_tuning queryType=${queryType.name} p90=${recentP90Ms}ms premium=$isPremium baseTokens=${base.maxTokens} tunedTokens=$tunedTokens temp=$tunedTemperature")
        return GenerationConfig(temperature = tunedTemperature, maxTokens = tunedTokens)
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

    fun shouldSemanticCut(
        partialText: String,
        elapsedMs: Long,
        tokensStreamed: Int,
        queryType: QueryType,
        isPremium: Boolean
    ): Boolean {
        val minTokens = when (queryType) {
            QueryType.SIMPLE -> 36
            QueryType.ACTION -> 52
            QueryType.ANALYTICAL -> 68
            QueryType.CREATIVE -> 96
            QueryType.UNKNOWN -> 60
        }
        val maxElapsed = if (isPremium) 6500L else 4200L
        val hasSemanticStop = lastBoundaryIndex(partialText) >= 80
        val shouldCut = elapsedMs >= maxElapsed && tokensStreamed >= minTokens && hasSemanticStop
        Log.d(TAG, "semantic_cut_check queryType=${queryType.name} elapsed=${elapsedMs}ms tokens=$tokensStreamed boundary=$hasSemanticStop premium=$isPremium cut=$shouldCut")
        return shouldCut
    }

    fun semanticCut(partialText: String): SemanticCutResult {
        val cleaned = partialText.trim()
        if (cleaned.isEmpty()) return SemanticCutResult("", false, "empty")
        val boundary = lastBoundaryIndex(cleaned)
        if (boundary < 80 || boundary >= cleaned.lastIndex - 12) {
            return SemanticCutResult(cleaned, false, "no_better_boundary")
        }
        val cut = cleaned.take(boundary + 1).trim()
        Log.d(TAG, "semantic_cut_applied original_len=${cleaned.length} cut_len=${cut.length}")
        return SemanticCutResult("$cut\n\nResponse shortened to keep AIRI fast.", true, "semantic_boundary")
    }

    private fun lastBoundaryIndex(text: String): Int {
        val sentence = listOf('.', '!', '?', '؟', '\n').map { text.lastIndexOf(it) }.maxOrNull() ?: -1
        if (sentence >= 0) return sentence
        return text.lastIndexOf(";")
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
