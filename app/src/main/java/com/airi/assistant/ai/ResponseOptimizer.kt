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

    /**
     * Diagnostics only: called from DiagnosticsRunner and never from the live
     * generation path, so it cannot shorten a real answer (report 1, item 16).
     */
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
     *  - Removes exact (role + full content) duplicates only
     *  - Truncates oversized non-recent prose messages (>600 chars assistant, >400 user);
     *    the latest assistant reply and any message containing a code fence keep up to 16,000 chars
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

        // Report item 22: dedupe on the FULL (role, content) pair. The previous key was the first
        // 60 characters of content, so distinct code replies that share an opening line
        // ("Here is the code:" ...) were silently dropped from the context.
        val seen    = mutableSetOf<Pair<String, String>>()
        val deduped = recent.filter { msg -> seen.add(msg.role to msg.content) }

        val lastIdx = deduped.lastIndex
        val lastAssistantIdx = deduped.indexOfLast { it.role == "assistant" }
        // Relax truncation limits for agent mode
        val assistantMaxLen = if (isAgentMode) 2000 else 600
        val userMaxLen      = if (isAgentMode) 800 else 400
        val trimmed = deduped.mapIndexed { i, msg ->
            when {
                i == lastIdx -> msg
                // Report item 23: a follow-up such as "edit the previous code" must reach the model
                // with the code intact. The latest assistant reply and any reply that contains code
                // get a much larger ceiling instead of the 600/2000-char prose limit.
                msg.role == "assistant" && (i == lastAssistantIdx || containsCode(msg.content)) ->
                    if (msg.content.length > CODE_REPLY_MAX_LEN)
                        msg.copy(content = msg.content.take(CODE_REPLY_MAX_LEN) + " […]")
                    else msg
                msg.role == "assistant" && msg.content.length > assistantMaxLen ->
                    msg.copy(content = msg.content.take(assistantMaxLen) + " […]")
                msg.role == "user" && containsCode(msg.content) && msg.content.length > CODE_REPLY_MAX_LEN ->
                    msg.copy(content = msg.content.take(CODE_REPLY_MAX_LEN) + " […]")
                msg.role == "user" && !containsCode(msg.content) && msg.content.length > userMaxLen ->
                    msg.copy(content = msg.content.take(userMaxLen) + " […]")
                else -> msg
            }
        }

        return if (systemMsg != null) listOf(systemMsg) + trimmed else trimmed
    }

    /** Ceiling for assistant/user messages that carry code, and for the latest assistant reply. */
    private const val CODE_REPLY_MAX_LEN = 16_000

    private fun containsCode(content: String): Boolean = content.contains("```")

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
     * suggestions for unsupported/sensitive contexts. AIRI presents these as
     * contextual follow-up actions: casual turns get conversational replies,
     * while completed agent turns get useful next-step suggestions instead of
     * being silently suppressed just because their query type is ACTION or
     * ANALYTICAL.
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
            "كود", "الكود", "برمج", "دالة", "صنف", "خطأ برمجي", "تصحيح",
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

        val normalizedLanguage = languageCode.trim().substringBefore('-').lowercase(Locale.ROOT)
        fun localized(en: List<String>, ar: List<String>, es: List<String>, zh: List<String>): List<String> =
            when (normalizedLanguage) {
                "ar" -> ar
                "es" -> es
                "zh" -> zh
                else -> en
            }
                .map(String::trim)
                .filter { suggestion -> suggestion.length >= 30 }
                .distinctBy { suggestion -> suggestion.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ") }
                .take(3)

        return when {
            codeContext ->
                localized(
                    en = listOf("Analyze the code line by line, identify the most likely bugs, and explain which improvements matter first for this specific implementation.", "Add focused tests and clear error handling for the important success and failure paths, then explain what each test protects.", "Improve this code for performance, security, and maintainability while preserving its current behavior, and call out any trade-offs.",),
                    ar = listOf("حلّل الكود سطرًا بسطر، وحدد الأخطاء الأكثر احتمالًا، واشرح أي التحسينات يجب تنفيذها أولًا في هذا التنفيذ تحديدًا.", "أضف اختبارات مركزة ومعالجة واضحة لمسارات النجاح والفشل المهمة، ثم وضّح ما الذي يحميه كل اختبار.", "حسّن هذا الكود من حيث الأداء والأمان وسهولة الصيانة مع الحفاظ على سلوكه الحالي، واذكر أي مفاضلات محتملة.",),
                    es = listOf("Analiza el código línea por línea, identifica los errores más probables y explica qué mejoras deben hacerse primero en esta implementación concreta.", "Añade pruebas específicas y un manejo claro de errores para los caminos importantes de éxito y fallo, explicando qué protege cada prueba.", "Mejora el rendimiento, la seguridad y el mantenimiento sin cambiar el comportamiento actual, e indica las posibles decisiones y compromisos.",),
                    zh = listOf("逐行分析这段代码，找出最可能的错误，并说明在这个具体实现中哪些改进应该优先完成。", "为重要的成功和失败路径添加针对性测试与清晰的错误处理，并解释每个测试保护的行为。", "在保持现有行为的前提下改进性能、安全性和可维护性，同时说明可能的取舍。",),
                )

            stepContext ->
                localized(
                    en = listOf("Explain the first step with a concrete example, including the expected input, output, and the point at which I should verify the result.", "Turn the proposed plan into the next executable tasks, ordered by priority with clear completion criteria and any dependencies.", "Rewrite the steps in a simpler, easier-to-follow way while keeping the important warnings, decisions, and checks needed to avoid mistakes.",),
                    ar = listOf("اشرح الخطوة الأولى بمثال عملي واضح، مع توضيح المدخلات والنتيجة المتوقعة ونقطة التحقق من نجاحها.", "حوّل الخطة المقترحة إلى مهام تنفيذية مرتبة حسب الأولوية، مع معيار واضح لاكتمال كل مهمة والاعتماديات بينها.", "أعد كتابة الخطوات بطريقة أبسط وأسهل للتنفيذ، مع الحفاظ على التحذيرات والقرارات ونقاط التحقق المهمة لتجنب الأخطاء.",),
                    es = listOf("Explica el primer paso con un ejemplo práctico, indicando la entrada, el resultado esperado y cuándo debo verificar que funciona.", "Convierte el plan propuesto en tareas ejecutables ordenadas por prioridad, con criterios claros de finalización y dependencias.", "Reescribe los pasos de forma más sencilla y fácil de seguir, manteniendo las advertencias, decisiones y comprobaciones importantes.",),
                    zh = listOf("用具体例子解释第一步，说明输入、预期输出，以及应在什么时候验证结果是否正确。", "把计划转换成按优先级排列的可执行任务，并为每项任务给出完成标准和必要依赖。", "用更简单、更容易执行的方式重写步骤，同时保留重要的警告、决策和检查点。",),
                )

            asksForReasoning && containsAny(answerLower, listOf("because", "therefore", "reason", "لأن", "لذلك", "السبب")) ->
                localized(
                    en = listOf("Explain the reasoning in more detail with a practical example, and distinguish the facts from the assumptions behind the conclusion.", "Show how to apply this idea to a realistic case, including the decisions to make and the result I should expect.", "Summarize the answer into the key decisions, evidence, caveats, and one clear recommendation for what to do next.",),
                    ar = listOf("اشرح منطق الإجابة بتفصيل أكبر مع مثال عملي، وميّز بين الحقائق والافتراضات التي بُني عليها الاستنتاج.", "وضّح كيفية تطبيق هذه الفكرة على حالة واقعية، مع القرارات المطلوبة والنتيجة التي ينبغي توقعها.", "لخّص الإجابة في القرارات والأدلة والنقاط المهمة والقيود، ثم اقترح خطوة تالية واضحة.",),
                    es = listOf("Explica el razonamiento con más detalle y un ejemplo práctico, diferenciando los hechos de las suposiciones de la conclusión.", "Muestra cómo aplicar esta idea a un caso realista, incluyendo las decisiones necesarias y el resultado que debería esperar.", "Resume la respuesta con las decisiones, evidencias, limitaciones y una recomendación clara para el siguiente paso.",),
                    zh = listOf("更详细地解释推理过程并给出实际例子，同时区分结论所依据的事实和假设。", "展示如何把这个想法应用到真实场景中，包括需要作出的决定和应当期待的结果。", "把回答总结为关键决定、证据、限制，并给出一个明确的下一步建议。",),
                )

            asksForComparison ->
                localized(
                    en = listOf("Compare the alternatives by performance, cost, reliability, privacy, and ease of use, then summarize which differences matter for my situation.", "Recommend the best option for my stated constraints, explain the reasoning, and mention what new information could change the recommendation.", "List the trade-offs, risks, and situations where each option fits best, with a balanced conclusion rather than a generic winner.",),
                    ar = listOf("قارن بين البدائل من حيث الأداء والتكلفة والاعتمادية والخصوصية وسهولة الاستخدام، ثم لخّص الفروق التي تهم حالتي.", "رشّح الخيار الأنسب لقيودي المذكورة، واشرح سبب التوصية، واذكر المعلومات التي قد تغيّر هذا القرار.", "اذكر المفاضلات والمخاطر والحالات التي يناسب فيها كل خيار، مع خلاصة متوازنة بدل اختيار عام بلا سياق.",),
                    es = listOf("Compara las alternativas por rendimiento, coste, fiabilidad, privacidad y facilidad de uso, y resume qué diferencias importan en mi caso.", "Recomienda la mejor opción según mis limitaciones, explica el motivo y señala qué información podría cambiar la recomendación.", "Enumera las ventajas, riesgos y situaciones en las que encaja mejor cada alternativa, con una conclusión equilibrada y contextualizada.",),
                    zh = listOf("从性能、成本、可靠性、隐私和易用性比较各个方案，并总结哪些差异对我的情况最重要。", "根据我的限制条件推荐最合适的方案，解释原因，并说明哪些新信息可能改变这个推荐。", "列出每个方案的取舍、风险和适用场景，给出有上下文的平衡结论，而不是笼统地选择一个赢家。",),
                )

            asksForAnalysis ->
                localized(
                    en = listOf("Expand the analysis with the strongest evidence, explicit assumptions, uncertainty, and limitations so I can judge how reliable the conclusion is.", "Extract the key findings and the next practical step, separating confirmed observations from ideas that still need verification.", "Turn the analysis into a concise decision-ready summary with the recommended choice, reasons, risks, and conditions for revisiting it.",),
                    ar = listOf("وسّع التحليل بالأدلة الأقوى والافتراضات والشكوك والقيود بوضوح حتى أستطيع تقييم موثوقية النتيجة.", "استخرج أهم النتائج والخطوة العملية التالية، وافصل بين الملاحظات المؤكدة والأفكار التي تحتاج إلى تحقق.", "حوّل التحليل إلى ملخص واضح يساعد على اتخاذ القرار، يتضمن التوصية والأسباب والمخاطر ومتى ينبغي مراجعتها.",),
                    es = listOf("Amplía el análisis con las pruebas más sólidas, las suposiciones, la incertidumbre y las limitaciones para poder valorar la fiabilidad de la conclusión.", "Extrae los hallazgos principales y el siguiente paso práctico, separando las observaciones confirmadas de lo que aún debe verificarse.", "Convierte el análisis en un resumen útil para decidir, con recomendación, razones, riesgos y condiciones para revisarla.",),
                    zh = listOf("用最有力的证据、明确的假设、不确定性和限制补充分析，帮助我判断结论的可靠程度。", "提取关键发现和下一步行动，并区分已经确认的观察与仍需验证的想法。", "把分析整理成可用于决策的简明总结，包括推荐、理由、风险以及何时需要重新评估。",),
                )

            queryType == QueryType.ACTION ->
                localized(
                    en = listOf("Verify the completed result against the original request, run the relevant checks, and report any remaining issue with evidence.", "Apply the next safe improvement to this task, verify its effect, explain exactly what changed, and keep unrelated behavior untouched.", "Summarize what was completed, what was verified, what could not be tested, and the precise next step if anything remains.",),
                    ar = listOf("تحقق من النتيجة المنفذة مقابل الطلب الأصلي، وشغّل الفحوصات المناسبة، وأبلغني بأي مشكلة متبقية مع الدليل.", "طبّق التحسين الآمن التالي على هذه المهمة، وتحقق من أثره، واشرح ما الذي تغير تحديدًا دون لمس السلوك غير المرتبط.", "لخّص ما تم إنجازه وما تم التحقق منه وما تعذر اختباره، وحدد الخطوة التالية بدقة إن بقي شيء.",),
                    es = listOf("Verifica el resultado frente a la solicitud original, ejecuta las comprobaciones relevantes e informa de cualquier problema restante con pruebas.", "Aplica la siguiente mejora segura, verifica su efecto, explica exactamente qué cambió y no alteres el comportamiento relacionado.", "Resume lo completado, lo verificado, lo que no pudo probarse y el siguiente paso exacto si queda algo pendiente.",),
                    zh = listOf("根据原始请求验证已完成的结果，运行相关检查，并用证据说明是否还有问题。", "应用下一项安全改进，验证它的效果，准确解释发生了什么变化，同时不要影响无关功能。", "总结已完成、已验证和无法测试的内容；如果仍有剩余任务，明确指出下一步。",),
                )

            queryType == QueryType.ANALYTICAL ->
                localized(
                    en = listOf("Extract the key findings and supporting evidence, and distinguish the conclusion from the observations that led to it.", "List the assumptions, risks, uncertainties, and limitations that could change how this result should be interpreted.", "Turn this analysis into an actionable plan with priorities, owners or inputs needed, verification points, and a clear first step.",),
                    ar = listOf("استخرج أهم النتائج والأدلة الداعمة، وميّز بين الخلاصة والملاحظات التي قادت إليها.", "اذكر الافتراضات والمخاطر والشكوك والقيود التي قد تغيّر طريقة تفسير هذه النتيجة.", "حوّل هذا التحليل إلى خطة قابلة للتنفيذ تتضمن الأولويات والمدخلات المطلوبة ونقاط التحقق وأول خطوة واضحة.",),
                    es = listOf("Extrae los hallazgos principales y las pruebas que los respaldan, diferenciando la conclusión de las observaciones que la sustentan.", "Enumera las suposiciones, riesgos, incertidumbres y limitaciones que podrían cambiar la interpretación del resultado.", "Convierte el análisis en un plan accionable con prioridades, información necesaria, puntos de verificación y un primer paso claro.",),
                    zh = listOf("提取关键结论和支持证据，并区分最终判断与形成判断的观察结果。", "列出可能改变结果解释的假设、风险、不确定性和限制。", "把分析转成可执行计划，包含优先级、所需输入、验证节点和明确的第一步。",),
                )

            queryType == QueryType.CREATIVE ->
                localized(
                    en = listOf("Develop the strongest idea further with a clear structure, concrete details, and an explanation of why it fits the goal and audience.", "Give me three practical variations with different tones or trade-offs, and explain when each version would be the best choice.", "Turn this into a polished final version, keeping the original intent while improving clarity, rhythm, and the details that make it distinctive.",),
                    ar = listOf("طوّر أقوى فكرة بمزيد من التفاصيل وبنية واضحة، واشرح لماذا تناسب الهدف والجمهور المقصود.", "قدّم لي ثلاث صيغ عملية مختلفة في النبرة أو المفاضلات، واشرح متى تكون كل صيغة هي الاختيار الأفضل.", "حوّل هذه الفكرة إلى نسخة نهائية متقنة، مع الحفاظ على المقصد الأصلي وتحسين الوضوح والإيقاع والتفاصيل المميزة.",),
                    es = listOf("Desarrolla la idea más sólida con una estructura clara, detalles concretos y una explicación de por qué encaja con el objetivo y el público.", "Dame tres variaciones prácticas con tonos o compromisos diferentes y explica cuándo conviene elegir cada una.", "Convierte esta idea en una versión final pulida, conservando la intención original y mejorando claridad, ritmo y rasgos distintivos.",),
                    zh = listOf("进一步发展最有潜力的创意，提供清晰结构和具体细节，并说明它为何适合目标与受众。", "给出三个语气或取舍不同的实用变体，并说明每个版本在什么场景下最合适。", "在保留原意的同时整理成精炼的最终版本，改善表达清晰度、节奏和独特细节。",),
                )

            // Arabic answers can be concise while still containing enough
            // context for useful, topic-specific follow-up actions. Keep the
            // gate high enough to reject one-line replies without discarding
            // a complete answer of roughly one paragraph.
            asksOpenEnded && answer.length >= 120 ->
                localized(
                    en = listOf("Give me a practical example that applies this idea to a realistic situation, with enough detail to see how the result would work.", "What are the main limitations, risks, or situations where this explanation may not apply, and how can I recognize them early?", "How does this affect everyday life or a real project, and what is one useful action I can take based on the explanation?",),
                    ar = listOf("أعطني مثالًا عمليًا يطبّق هذه الفكرة على حالة واقعية، مع تفاصيل كافية لفهم النتيجة وكيفية عملها.", "ما أبرز القيود أو المخاطر أو الحالات التي قد لا تنطبق فيها هذه الإجابة، وكيف أكتشفها مبكرًا؟", "كيف يؤثر ذلك في الحياة اليومية أو في مشروع حقيقي، وما الإجراء المفيد الذي يمكنني اتخاذه بناءً على الشرح؟",),
                    es = listOf("Dame un ejemplo práctico aplicado a una situación realista, con suficientes detalles para entender cómo funcionaría el resultado.", "¿Cuáles son las principales limitaciones, riesgos o casos en los que esta explicación no se aplica, y cómo puedo detectarlos pronto?", "¿Cómo afecta esto a la vida diaria o a un proyecto real, y qué acción útil puedo tomar a partir de la explicación?",),
                    zh = listOf("给出一个把这个想法应用到真实场景的实际例子，并提供足够细节说明结果如何运作。", "主要限制、风险或不适用的情况是什么？我该如何尽早识别这些情况？", "这会如何影响日常生活或真实项目？根据这个解释，我可以采取哪一个有用的行动？",),
                )

            else -> emptyList()
        }
    }
}
