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
        """(?i)\b(remember this|remember that|save this|save it to memory|keep this preference)\b|(?:تذكّر|تذكر|احفظ|خزّن|خزن)\s"""
    )

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
        if (wordCount < MIN_EMBED_WORDS && !explicitMemoryRequest.containsMatchIn(normalized)) {
            return MessageDecision(false, false, "insufficient_context")
        }
        return MessageDecision(
            shouldEmbed = true,
            shouldExtractFacts = role == "user" && explicitMemoryRequest.containsMatchIn(normalized),
            reason = if (role == "user" && explicitMemoryRequest.containsMatchIn(normalized)) {
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

    private const val MIN_EMBED_CHARS = 20
    private const val MAX_EMBED_CHARS = 1_500
    private const val MIN_EMBED_WORDS = 4
}
