package com.airi.core.memory

object MemoryAdmissionPolicy {

    data class MessageDecision(
        val shouldEmbed: Boolean,
        val shouldExtractFacts: Boolean,
        val reason: String
    )

    private val sensitivePatterns = listOf(
        Regex("""\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b"""),
        Regex("""\b(?:\+?\d[\d\s().-]{7,}\d)\b"""),
        Regex("""\b(?:\d[ -]*?){13,19}\b"""),
        Regex("""(?i)\b(password|passcode|api[_ -]?key|secret|token|otp|cvv)\b"""),
        Regex("""(?i)\b(رقم البطاقة|كلمة المرور|رمز التحقق|مفتاح API|مفتاح الوصول|سرّي)\b""")
    )

    private val explicitMemoryRequest = Regex(
        """(?i)\b(remember this|remember that|save this|save it to memory|keep this preference)\b|(?:تذكّر|تذكر|احفظ|تحفظ|خزّن|خزن|تخزّن|تخزن)\s"""
    )
    private val negatedMemoryRequest = Regex(
        """(?i)(?:\b(?:do not|don't|dont|never|not)\s+(?:remember|save|store|keep)\b|\b(?:don't|do not) want (?:you to )?(?:remember|save|store)\b|لا\s+(?:تتذكر|تذكّر|تحفظ|احفظ|تخزن|تخزّن))"""
    )
    private val quoteMarkers = Regex("[\"'“”‘’]([^\"'“”‘’]*)[\"'“”‘’]")

    private val transientMessage = Regex(
        """(?i)^\s*(hi|hello|hey|thanks|thank you|ok|okay|yes|no|مرحبا|السلام عليكم|شكرا|شكرًا|نعم|لا|تمام|حسنا|حسنًا)[!؟?.،\s]*$"""
    )

    private val attachmentMarker = Regex("""^\s*\[(?:ATTACHMENT|image|file):""", RegexOption.IGNORE_CASE)

    fun decide(role: String, content: String): MessageDecision {
        val normalized = content.trim()
        if (role !in setOf("user", "assistant")) {
            return MessageDecision(false, false, "unsupported_role")
        }
        if (normalized.length !in MIN_EMBED_CHARS..MAX_EMBED_CHARS) {
            return MessageDecision(false, false, "outside_size_budget")
        }
        if (transientMessage.matches(normalized) || attachmentMarker.containsMatchIn(normalized)) {
            return MessageDecision(false, false, "transient_or_attachment")
        }
        if (containsSensitiveData(normalized)) {
            return MessageDecision(false, false, "sensitive_content")
        }
        val wordCount = normalized.split(Regex("""\s+""")).count { it.isNotBlank() }
        val explicitRequest = hasExplicitMemoryRequest(normalized)
        if (wordCount < MIN_EMBED_WORDS && !explicitRequest) {
            return MessageDecision(false, false, "insufficient_context")
        }
        return MessageDecision(
            shouldEmbed = true,
            shouldExtractFacts = role == "user" && explicitRequest,
            reason = if (role == "user" && explicitRequest) {
                "explicit_memory_request"
            } else {
                "session_relevance"
            }
        )
    }

    fun allowExtractedFact(fact: String): Boolean {
        val key = fact.substringBefore('=').lowercase()
        val value = fact.substringAfter('=', "").trim()
        return key in setOf("preference", "dislike", "language", "project") &&
            value.length in 2..60 &&
            !containsSensitiveData(value)
    }

    fun containsSensitiveData(value: String): Boolean =
        sensitivePatterns.any { it.containsMatchIn(value) }

    private fun hasExplicitMemoryRequest(content: String): Boolean {
        val unquoted = quoteMarkers.replace(content, " ")
        val request = explicitMemoryRequest.find(unquoted) ?: return false
        // Include the matched request phrase itself: in "don't remember this"
        // the negation marker precedes the regex match, so looking only before
        // request.range.first can never observe the complete negated phrase.
        val requestClause = unquoted.substring(maxOf(0, request.range.first - 48), request.range.last + 1)
        return !negatedMemoryRequest.containsMatchIn(requestClause)
    }

    private const val MIN_EMBED_CHARS = 20
    private const val MAX_EMBED_CHARS = 1_500
    private const val MIN_EMBED_WORDS = 4
}
