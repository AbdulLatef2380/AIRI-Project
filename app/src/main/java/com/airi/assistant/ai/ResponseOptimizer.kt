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

    /** Android's framework Log methods are unavailable in plain JVM unit tests. */
    private fun diagnosticLog(message: String) {
        if (com.airi.assistant.BuildConfig.DEBUG) {
            runCatching { Log.d(TAG, message) }
        }
    }

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
                "I'm AIRI — your on-device AI assistant. I can work locally, and some features may use cloud services when you enable them.",
                "AIRI here — an intelligent AI assistant that runs on your device, with optional cloud features when configured.",
                "I'm AIRI, an on-device AI assistant. Core actions stay local, and optional online features are available too."
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
                "أهلاً! كيف أستطيع مساعدتك اليوم؟",
                "مرحباً! كيف يمكنني مساعدتك؟"
            )
        ),
        FastEntry(
            listOf("how are you", "كيف حالك", "كيف الحال", "عامل إيه"),
            pick(
                "I'm doing great, thanks for asking! What's on your mind?",
                "All good here! What can I help you with?",
                "Ready and sharp! What do you need?",
                "أنا بخير، شكراً لسؤالك. كيف يمكنني مساعدتك؟"
            )
        ),
        FastEntry(
            listOf("thank you", "thanks", "شكراً", "شكرا", "شكرًا", "merci", "tnx", "thx", "ty"),
            pick(
                "You're welcome! Let me know if there's anything else.",
                "Happy to help! Anything else?",
                "Anytime! What else can I do for you?",
                "Of course! Feel free to ask anything.",
                "عفواً! هل تحتاج أي مساعدة أخرى؟",
                "على الرحب والسعة! هل تحتاج إلى شيء آخر؟"
            )
        ),
        FastEntry(
            listOf("are you online", "do you need internet", "هل تحتاج إنترنت", "هل أنت أونلاين",
                   "offline", "no internet", "internet connection"),
            pick(
                "I can work offline for many tasks, but some features may need internet or cloud services.",
                "Many core features work on-device, and optional online features may use the internet.",
                "يمكنني العمل دون اتصال في مهام كثيرة، لكن بعض الميزات تحتاج إلى الإنترنت أو الخدمات السحابية."
            )
        ),
        FastEntry(
            listOf("what can you do", "what are your capabilities", "help me", "show me what you can do"),
            pick(
                "I can answer questions, write code, analyze text, summarize, translate, brainstorm, and more, with a mix of local and optional cloud features.",
                "I can help with coding, writing, Q&A, analysis, translations, and creative tasks. What would you like?",
                "Ask me anything: code, writing, summaries, explanations, math, and more.",
                "أستطيع الإجابة عن الأسئلة وكتابة الكود وتحليل النصوص والتلخيص والترجمة والمساعدة في المهام الإبداعية."
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

        // ── Bug A fix (was: greeting fallback for long prompts) ───────────
        // The previous logic used `tokens.contains("hi")` for single-word
        // keys, which meant ANY long prompt that happened to contain "hi"
        // or "hey" as a standalone token (e.g. "Hi, can you explain
        // recursion with example?") returned "Hi! Ask me anything." instead
        // of going to the LLM. The fast-path is meant only for genuine
        // SHORT casual replies — apply hard length gates BEFORE matching.
        //
        // Empirically: greetings/thanks/identity questions are at most
        // ~30 chars / ~5 tokens. Anything longer is a real prompt and must
        // hit the model. We special-case multi-word phrases so something
        // like "what are your capabilities" still works.
        val tokens = lower.split(Regex("[\\s\\p{Punct}،؛؟]+")).filter { it.isNotBlank() }
        val isShort = lower.length <= 32 && tokens.size <= 5
        if (!isShort) {
            diagnosticLog("fast_response BYPASS reason=long_input len=${lower.length} tokens=${tokens.size}")
            return null
        }

        for (entry in fastTable) {
            val matched = entry.keys.any { key ->
                val k = key.trim().lowercase()
                if (k.isEmpty()) return@any false
                val isMultiWord = k.contains(' ')
                if (isMultiWord) {
                    // Multi-word phrase — keep substring semantics. Safe now
                    // because we already gated by total length above.
                    lower.contains(k)
                } else {
                    // Single-word key — only exact whole-input match. We
                    // dropped the `tokens.contains(k)` clause because at
                    // this point the input is already <=5 tokens AND we
                    // want "hi" to ONLY trigger on literally "hi" or
                    // "hi!" / "hi.", not on "hi mom what time is it".
                    lower == k || lower == "$k!" || lower == "$k." || lower == "$k?"
                }
            }
            if (matched) {
                // Never answer an Arabic or CJK prompt with a random English
                // canned reply. If no localized shortcut exists, return null so
                // the real model can answer in the user's language.
                val isArabic = raw.any { it in '\u0600'..'\u06FF' }
                val isCjk = raw.any { it in '\u4E00'..'\u9FFF' }
                val replyFactory = when {
                    isArabic -> entry.replies.firstOrNull { it().any { c -> c in '\u0600'..'\u06FF' } }
                    isCjk -> null
                    else -> entry.replies.firstOrNull { !it().any { c -> c in '\u0600'..'\u06FF' } }
                } ?: return null
                val reply = replyFactory()
                diagnosticLog("fast_response matched len=${input.length} reply_len=${reply.length}")
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
            // SIMPLE is a routing category, not a response-length category.
            // Genuine greetings use tryFastResponse; explanatory questions
            // routed as SIMPLE still need enough room to finish.
            QueryType.SIMPLE     -> GenerationConfig(temperature = 0.3f, maxTokens = minOf(1024, ramCappedMaxTokens))
            QueryType.ANALYTICAL -> GenerationConfig(temperature = 0.7f, maxTokens = minOf(2048, ramCappedMaxTokens))
            QueryType.ACTION     -> GenerationConfig(temperature = 0.5f, maxTokens = minOf(256,  ramCappedMaxTokens))
            QueryType.CREATIVE   -> GenerationConfig(temperature = 0.9f, maxTokens = minOf(2048, ramCappedMaxTokens))
            QueryType.UNKNOWN    -> GenerationConfig(temperature = 0.7f, maxTokens = minOf(768, ramCappedMaxTokens))
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
        diagnosticLog("adaptive_tuning queryType=${queryType.name} p90=${recentP90Ms}ms premium=$isPremium baseTokens=${base.maxTokens} tunedTokens=$tunedTokens temp=$tunedTemperature")
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

    /**
     * Maximum new tokens that fit after the prompt and a small runtime reserve.
     * Returning 1 instead of an arbitrary floor is intentional: when the prompt
     * already fills the local context, a larger floor would exceed n_ctx.
     */
    fun localOutputTokenCap(
        nCtx: Int,
        estimatedPromptTokens: Int,
        requestedMaxTokens: Int,
        reserveTokens: Int = 128,
    ): Int {
        val remaining = nCtx - estimatedPromptTokens - reserveTokens
        return requestedMaxTokens.coerceAtLeast(1).coerceAtMost(remaining.coerceAtLeast(1))
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
        diagnosticLog("semantic_cut_check queryType=${queryType.name} elapsed=${elapsedMs}ms tokens=$tokensStreamed boundary=$hasSemanticStop premium=$isPremium cut=$shouldCut")
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
        diagnosticLog("semantic_cut_applied original_len=${cleaned.length} cut_len=${cut.length}")
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
     *  - Truncates oversized non-recent messages (>600 chars assistant, >400 user)
     *  - Preserves the very last message intact (user's current input)
     *
     * In agent mode (isAgentMode=true), preserves more context for multi-step
     * reasoning: keeps at least 12 turns and relaxes truncation limits to
     * 2000 chars for assistant and 800 for user messages.
     */
    fun smartTrim(messages: List<ChatMessage>, keepLast: Int = 6, isAgentMode: Boolean = false): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()

        val systemMsg = messages.firstOrNull { it.role == "system" }
        val nonSystem = messages.filter { it.role != "system" }

        // Agent mode needs more context for multi-step reasoning.
        val effectiveKeep = if (isAgentMode) keepLast.coerceAtLeast(12) else keepLast
        val recent = nonSystem.takeLast(effectiveKeep)

        val seen    = mutableSetOf<String>()
        val deduped = recent.filter { msg -> seen.add(msg.content.take(60)) }

        val lastIdx = deduped.lastIndex
        // Relax truncation limits for agent mode
        val assistantMaxLen = if (isAgentMode) 2000 else 600
        val userMaxLen      = if (isAgentMode) 800 else 400
        val trimmed = deduped.mapIndexed { i, msg ->
            when {
                i == lastIdx -> msg
                msg.role == "assistant" && msg.content.length > assistantMaxLen ->
                    msg.copy(content = msg.content.take(assistantMaxLen) + " […]")
                msg.role == "user" && msg.content.length > userMaxLen ->
                    msg.copy(content = msg.content.take(userMaxLen) + " […]")
                else -> msg
            }
        }

        return if (systemMsg != null) listOf(systemMsg) + trimmed else trimmed
    }

    // ── Smart reply suggestions ───────────────────────────────────────────────

    /**
     * A response-only call has no conversation context and is therefore not
     * eligible for smart replies. This prevents stale/generic chips from being
     * fabricated from the assistant message alone.
     */
    fun generateSuggestions(
        response: String,
        languageCode: String = Locale.getDefault().language,
    ): List<String> = emptyList()

    /**
     * Generate contextual follow-up chips from the user's request and answer.
     *
     * Smart replies are deliberately conservative. The official Smart Reply
     * pattern uses recent conversation context, confidence filtering, and no
     * suggestions for unsupported/sensitive contexts. AIRI mirrors that
     * contract locally: no user message means no chips, action/analysis turns
     * are not casual reply conversations, and there is no length-only fallback.
     */
    fun generateSuggestions(
        userInput: String,
        response: String,
        languageCode: String = Locale.getDefault().language,
        queryType: QueryType = QueryType.SIMPLE,
        recentContext: List<String> = emptyList(),
    ): List<String> {
        val input = userInput.trim()
        val answer = response.trim()
        if (input.length < 3 || answer.length < 50) return emptyList()
        // Smart replies are for casual conversational turns, not tool/action
        // execution or analytical/creative deliverables.
        if (queryType != QueryType.SIMPLE) return emptyList()

        val inputLower = input.lowercase(Locale.ROOT)
        val answerLower = answer.lowercase(Locale.ROOT)
        val contextLower = (recentContext.takeLast(10) + input + answer)
            .joinToString("\n")
            .lowercase(Locale.ROOT)

        fun containsAny(text: String, terms: List<String>): Boolean = terms.any(text::contains)

        // Do not turn sensitive or credential-bearing conversations into
        // one-tap continuations. This follows the official Smart Reply
        // sensitive-topic filtering principle and avoids leaking a chip that
        // could encourage an unsafe next action.
        if (containsAny(contextLower, listOf(
                "suicide", "self-harm", "قتل النفس", "انتحار", "abuse", "اعتداء",
                "password", "كلمة المرور", "api key", "access token", "رمز الدخول",
            ))) return emptyList()

        val asksForCode = containsAny(inputLower, listOf(
            "code", "bug", "debug", "program", "function", "class", "kotlin", "java", "python",
            "كود", "برمج", "دالة", "صنف", "خطأ برمجي", "تصحيح",
        ))
        val asksForSteps = containsAny(inputLower, listOf(
            "step", "steps", "how to", "walk me through", "instructions", "plan",
            "خطوة", "خطوات", "كيف أ", "كيف يمكن", "طريقة", "خطة",
        ))
        val asksForReasoning = containsAny(inputLower, listOf(
            "why", "because", "reason", "explain why", "لماذا", "السبب", "بسبب", "لأن",
        ))
        val asksForComparison = containsAny(inputLower, listOf(
            "compare", "comparison", "difference", "versus", " vs ", "قارن", "مقارنة", "الفرق",
        ))
        val asksForAnalysis = containsAny(inputLower, listOf(
            "analyze", "analyse", "analysis", "evaluate", "حلل", "حلّل", "تحليل", "قيّم",
        ))
        val asksOpenEnded = containsAny(inputLower, listOf(
            "tell me about", "give me an overview", "حدثني عن", "أعطني نظرة عامة",
        ))

        val answerHasCode = answerLower.contains("```") || containsAny(answerLower, listOf("fun ", "class ", "function "))
        val answerHasSteps = answerLower.contains("1.") || containsAny(answerLower, listOf("step", "خطوة", "خطوات"))
        // A marker in the answer is not enough: the user's turn must establish
        // the intent, otherwise incidental words create unrelated chips.
        val codeContext = asksForCode && answerHasCode
        val stepContext = asksForSteps && answerHasSteps

        // Keep the context variable meaningful: only recent turns plus the
        // current turn may influence eligibility, never unrelated app state.
        if (contextLower.isBlank()) return emptyList()

        fun localized(en: List<String>, ar: List<String>, es: List<String>, zh: List<String>): List<String> =
            when (languageCode.substringBefore('-').lowercase(Locale.ROOT)) {
                "ar" -> ar
                "es" -> es
                "zh" -> zh
                else -> en
            }

        return when {
            codeContext ->
                localized(
                    en = listOf("Analyze the code, identify likely bugs, and suggest practical improvements", "Add tests and error handling for this implementation", "Optimize this code for performance, security, and maintainability"),
                    ar = listOf("حلّل الكود وحدد الأخطاء المحتملة واقترح تحسينات عملية", "أضف اختبارات ومعالجة واضحة للأخطاء لهذا التنفيذ", "حسّن الكود من حيث الأداء والأمان وسهولة الصيانة"),
                    es = listOf("Analiza el código, detecta errores y propone mejoras prácticas", "Añade pruebas y manejo de errores a esta implementación", "Optimiza el código para rendimiento, seguridad y mantenimiento"),
                    zh = listOf("分析代码并找出潜在错误，提出实用改进", "为这段实现添加测试和错误处理", "从性能、安全性和可维护性方面优化代码"),
                )

            stepContext ->
                localized(
                    en = listOf("Explain the first step with a concrete example", "Turn the proposed plan into the next executable tasks", "Rewrite the steps in a simpler, easier-to-follow way"),
                    ar = listOf("اشرح الخطوة الأولى بمثال عملي واضح", "حوّل الخطة المقترحة إلى مهام تنفيذية تالية", "أعد كتابة الخطوات بطريقة أبسط وأسهل للتنفيذ"),
                    es = listOf("Explica el paso 1", "¿Qué sigue?", "Simplifícalo"),
                    zh = listOf("解释第 1 步", "接下来做什么？", "简化说明"),
                )

            asksForReasoning && containsAny(answerLower, listOf("because", "therefore", "reason", "لأن", "لذلك", "السبب")) ->
                localized(
                    en = listOf("Explain the reasoning in more detail with a practical example", "Show how to apply this idea to a realistic case", "Summarize the answer into the key decisions and caveats"),
                    ar = listOf("اشرح الفكرة بتفصيل أكبر مع مثال عملي", "وضّح كيفية تطبيق هذه الفكرة على حالة واقعية", "لخّص الإجابة في القرارات والنقاط المهمة والقيود"),
                    es = listOf("Amplía la explicación", "Dame un ejemplo", "Resume la respuesta"),
                    zh = listOf("详细说明", "举个例子", "总结回答"),
                )

            asksForComparison ->
                localized(
                    en = listOf("Compare the alternatives by performance, cost, and ease of use", "Recommend the best option for my constraints and explain why", "List the trade-offs, risks, and situations where each option fits"),
                    ar = listOf("قارن بين البدائل من حيث الأداء والتكلفة وسهولة الاستخدام", "رشّح الخيار الأنسب لقيودي واشرح سبب التوصية", "اذكر المفاضلات والمخاطر ومتى يناسب كل خيار"),
                    es = listOf("Profundiza en la comparación", "¿Cuál es mejor?", "Ventajas y desventajas"),
                    zh = listOf("深入比较", "哪个更好？", "列出优缺点"),
                )

            asksForAnalysis ->
                localized(
                    en = listOf("Expand the analysis with evidence, assumptions, and limitations", "Extract the key findings and the next practical step", "Turn the analysis into a concise decision-ready summary"),
                    ar = listOf("وسّع التحليل بالأدلة والافتراضات والقيود المهمة", "استخرج أهم النتائج والخطوة العملية التالية", "حوّل التحليل إلى ملخص واضح يساعد على اتخاذ القرار"),
                    es = listOf("Más detalles", "¿Puntos clave?", "Resume la respuesta"),
                    zh = listOf("补充细节", "关键要点？", "总结回答"),
                )

            // Arabic answers can be concise while still containing enough
            // context for useful, topic-specific follow-up actions. Keep the
            // gate high enough to reject one-line replies without discarding
            // a complete answer of roughly one paragraph.
            asksOpenEnded && answer.length >= 120 ->
                localized(
                    en = listOf("Give me a practical example", "What are the main limitations or risks?", "How does this affect everyday life?"),
                    ar = listOf("أعطني مثالًا عمليًا على ذلك", "ما أبرز القيود أو المخاطر؟", "كيف يؤثر ذلك في الحياة اليومية؟"),
                    es = listOf("Dame un ejemplo práctico", "¿Cuáles son sus límites o riesgos?", "¿Cómo afecta a la vida diaria?"),
                    zh = listOf("给我一个实际例子", "主要限制或风险是什么？", "这如何影响日常生活？"),
                )

            else -> emptyList()
        }
    }
}
