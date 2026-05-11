package com.airi.assistant.domain.skill

import android.util.Log
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * SkillOrchestrator — production-grade skill routing and execution engine.
 *
 * Sits above [SkillService] and provides:
 *  1. **Category routing** — routes inputs to the most appropriate skill
 *     based on intent classification (keyword matching + pattern rules).
 *  2. **Priority system** — skills are ranked by confidence score;
 *     the highest-confidence match is tried first, with fallback chain.
 *  3. **Retry logic** — transient failures retry up to [MAX_RETRIES] times.
 *  4. **Timeout enforcement** — each skill call is bounded by [SKILL_TIMEOUT_MS].
 *  5. **Observability** — every route decision and outcome is logged via
 *     LoggingService so the agent trace captures the full skill path.
 *  6. **Graceful fallback** — on exhausted retries or no match, returns
 *     [OrchestratorResult.NoMatch] so the caller can route to LLM instead.
 *
 * ## Thread safety
 * All public methods are suspend functions that run on [Dispatchers.IO].
 * The orchestrator itself holds no mutable state — it is safe to share a
 * single instance across coroutine scopes.
 */
class SkillOrchestrator(private val skillService: SkillService) {

    // ── Result type ───────────────────────────────────────────────────────────

    sealed class OrchestratorResult {
        /** A skill handled the request and produced output. */
        data class Handled(
            val result:      SkillResult,
            val skillName:   String,
            val category:    SkillCategory,
            val confidence:  Float,
            val latencyMs:   Long
        ) : OrchestratorResult()

        /** No skill matched the input — caller should route to LLM. */
        data class NoMatch(val reason: String) : OrchestratorResult()

        /** A skill matched but execution failed after all retries. */
        data class Failed(
            val skillName: String,
            val error:     String,
            val attempts:  Int
        ) : OrchestratorResult()
    }

    // ── Skill categories ──────────────────────────────────────────────────────

    enum class SkillCategory {
        MESSAGING,      // Send/read messages, notifications
        CALENDAR,       // Events, reminders, scheduling
        PRODUCTIVITY,   // Documents, files, notes
        SYSTEM,         // Device control, settings, permissions
        WEB,            // Search, browse, fetch URLs
        CODING,         // Code generation, review, explanation
        CREATIVE,       // Writing, art, music prompts
        KNOWLEDGE,      // Factual Q&A, definitions, research
        MEMORY,         // Store/recall user facts and context
        TOOL,           // Direct tool calls (calculator, translator, etc.)
        UNKNOWN;        // Unclassified — routes to LLM

        companion object {
            fun fromInput(input: String): SkillCategory {
                val lower = input.lowercase()
                return when {
                    MESSAGING_PATTERNS.any { lower.contains(it) }    -> MESSAGING
                    CALENDAR_PATTERNS.any  { lower.contains(it) }    -> CALENDAR
                    SYSTEM_PATTERNS.any    { lower.contains(it) }    -> SYSTEM
                    WEB_PATTERNS.any       { lower.contains(it) }    -> WEB
                    CODING_PATTERNS.any    { lower.contains(it) }    -> CODING
                    CREATIVE_PATTERNS.any  { lower.contains(it) }    -> CREATIVE
                    MEMORY_PATTERNS.any    { lower.contains(it) }    -> MEMORY
                    PRODUCTIVITY_PATTERNS.any { lower.contains(it) } -> PRODUCTIVITY
                    TOOL_PATTERNS.any      { lower.contains(it) }    -> TOOL
                    KNOWLEDGE_PATTERNS.any { lower.contains(it) }    -> KNOWLEDGE
                    else                                              -> UNKNOWN
                }
            }

            private val MESSAGING_PATTERNS    = listOf("send message", "reply", "whatsapp", "telegram",
                "email", "text", "notification", "رسالة", "أرسل", "إيميل")
            private val CALENDAR_PATTERNS     = listOf("remind", "schedule", "calendar", "event",
                "meeting", "appointment", "تذكير", "جدول", "موعد", "اجتماع")
            private val SYSTEM_PATTERNS       = listOf("open", "close", "turn on", "turn off",
                "volume", "brightness", "wifi", "bluetooth", "افتح", "أغلق", "تشغيل", "إيقاف")
            private val WEB_PATTERNS          = listOf("search", "google", "browse", "website",
                "look up", "find online", "ابحث", "جوجل", "موقع")
            private val CODING_PATTERNS       = listOf("code", "function", "class", "debug",
                "compile", "script", "كود", "برمجة", "دالة", "خطأ برمجي")
            private val CREATIVE_PATTERNS     = listOf("write", "poem", "story", "creative",
                "lyrics", "اكتب", "قصيدة", "قصة", "مبدع")
            private val MEMORY_PATTERNS       = listOf("remember", "recall", "forget", "save this",
                "تذكر", "احفظ", "استرجع")
            private val PRODUCTIVITY_PATTERNS = listOf("document", "file", "note", "spreadsheet",
                "pdf", "ملف", "مستند", "ملاحظة")
            private val TOOL_PATTERNS         = listOf("calculate", "convert", "translate",
                "timer", "clock", "احسب", "حول", "ترجم", "مؤقت")
            private val KNOWLEDGE_PATTERNS    = listOf("what is", "who is", "explain", "define",
                "how does", "ما هو", "من هو", "اشرح", "عرف", "كيف")
        }
    }

    // ── Candidate scoring ─────────────────────────────────────────────────────

    private data class SkillCandidate(
        val name:       String,
        val category:   SkillCategory,
        val confidence: Float
    )

    private fun buildCandidates(input: String): List<SkillCandidate> {
        val category   = SkillCategory.fromInput(input)
        val lower      = input.lowercase()

        // Build a ranked list of candidate skill names for this category.
        // Confidence is heuristic (keyword hit density).
        val candidates = mutableListOf<SkillCandidate>()

        fun score(keywords: List<String>): Float {
            val hits = keywords.count { lower.contains(it) }
            return hits.toFloat() / keywords.size.coerceAtLeast(1)
        }

        when (category) {
            SkillCategory.MESSAGING -> {
                candidates += SkillCandidate("telegram_messenger", category,
                    score(listOf("telegram", "send", "message")) + 0.1f)
                candidates += SkillCandidate("gmail_assistant", category,
                    score(listOf("email", "gmail", "send", "mail")))
            }
            SkillCategory.CALENDAR -> {
                candidates += SkillCandidate("calendar_events", category,
                    score(listOf("calendar", "event", "schedule", "remind", "appointment")))
            }
            SkillCategory.PRODUCTIVITY -> {
                candidates += SkillCandidate("drive_search", category,
                    score(listOf("drive", "file", "document", "google")))
            }
            SkillCategory.WEB -> {
                candidates += SkillCandidate("github_guardian", category,
                    score(listOf("github", "repo", "code", "commit", "pr")))
            }
            else -> {}
        }

        // Add a generic catch-all with low confidence
        candidates += SkillCandidate("generic_${category.name.lowercase()}", category, 0.1f)

        return candidates.sortedByDescending { it.confidence }
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    suspend fun orchestrate(
        input:        String,
        skillContext: SkillContext
    ): OrchestratorResult = withContext(Dispatchers.IO) {
        val startMs  = System.currentTimeMillis()
        val category = SkillCategory.fromInput(input)

        Log.i(TAG, "ORCHESTRATE category=${category.name} input_len=${input.length}")
        LoggingService.debug(TAG, "Routing: category=${category.name}")

        if (category == SkillCategory.UNKNOWN) {
            Log.d(TAG, "ORCHESTRATE no_match reason=UNKNOWN_category")
            return@withContext OrchestratorResult.NoMatch("Input did not match any skill category")
        }

        val candidates = buildCandidates(input)
        if (candidates.isEmpty()) {
            return@withContext OrchestratorResult.NoMatch("No skill candidates for category=${category.name}")
        }

        Log.i(TAG, "ORCHESTRATE candidates=${candidates.size} top=${candidates.first().name} " +
            "confidence=${candidates.first().confidence}")

        // Try each candidate in priority order
        for (candidate in candidates) {
            if (candidate.confidence < MIN_CONFIDENCE) {
                Log.d(TAG, "ORCHESTRATE skip candidate=${candidate.name} low_confidence=${candidate.confidence}")
                continue
            }

            val result = tryExecuteWithRetry(input, skillContext, candidate)
            if (result != null) {
                val latency = System.currentTimeMillis() - startMs
                Log.i(TAG, "ORCHESTRATE_SUCCESS skill=${candidate.name} latency=${latency}ms")
                return@withContext OrchestratorResult.Handled(
                    result     = result,
                    skillName  = candidate.name,
                    category   = candidate.category,
                    confidence = candidate.confidence,
                    latencyMs  = latency
                )
            }
        }

        Log.w(TAG, "ORCHESTRATE_NO_MATCH exhausted_candidates=${candidates.size}")
        OrchestratorResult.NoMatch("No skill handled the input after trying ${candidates.size} candidates")
    }

    private suspend fun tryExecuteWithRetry(
        input:        String,
        skillContext: SkillContext,
        candidate:    SkillCandidate
    ): SkillResult? {
        var lastError: String? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val result = withTimeout(SKILL_TIMEOUT_MS) {
                    skillService.tryHandle(input, skillContext)
                }
                if (result != null) {
                    Log.i(TAG, "SKILL_SUCCESS skill=${candidate.name} attempt=$attempt")
                    return result
                }
                // null → skill did not handle this input, try next candidate
                Log.d(TAG, "SKILL_PASS skill=${candidate.name} attempt=$attempt")
                return null
            } catch (e: TimeoutCancellationException) {
                lastError = "Skill timeout after ${SKILL_TIMEOUT_MS}ms"
                Log.w(TAG, "SKILL_TIMEOUT skill=${candidate.name} attempt=$attempt")
                if (attempt < MAX_RETRIES) kotlinx.coroutines.delay(RETRY_DELAY_MS)
            } catch (e: CancellationException) {
                throw e  // Never swallow coroutine cancellation
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                Log.w(TAG, "SKILL_ERROR skill=${candidate.name} attempt=$attempt error=${lastError}")
                if (attempt < MAX_RETRIES) kotlinx.coroutines.delay(RETRY_DELAY_MS)
            }
        }
        Log.e(TAG, "SKILL_FAILED skill=${candidate.name} after=$MAX_RETRIES error=$lastError")
        return null
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    fun classifyOnly(input: String): SkillCategory = SkillCategory.fromInput(input)

    fun rankCandidates(input: String): List<String> =
        buildCandidates(input).map { "${it.name} (conf=${it.confidence})" }

    companion object {
        private const val TAG               = "AIRI_SkillOrchestrator"
        private const val MAX_RETRIES       = 2
        private const val RETRY_DELAY_MS    = 500L
        private const val SKILL_TIMEOUT_MS  = 12_000L
        private const val MIN_CONFIDENCE    = 0.15f
    }
}
