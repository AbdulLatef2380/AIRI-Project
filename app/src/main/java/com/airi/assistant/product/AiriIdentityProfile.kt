package com.airi.assistant.product

import com.airi.assistant.execution.Capability
import com.airi.assistant.execution.CapabilityStatus
import com.airi.assistant.execution.ModelCapabilityDescriptor

/**
 * Canonical product identity used by the existing prompt pipeline.
 *
 * This is deliberately a small, pure contract rather than a second chatbot.
 * The model remains responsible for the natural-language answer; this profile
 * supplies truthful, capability-aware facts whenever the user asks about AIRI.
 */
object AiriIdentityProfile {
    private val identityMarkers = listOf(
        "من أنت", "ما هي airi", "ماهي airi", "من طورك", "من صممك",
        "ما قصتك", "ماذا تستطيع", "ماذا يمكنك", "ما فائدتك", "هل أنت ذكاء اصطناعي",
        "هل تعمل بدون إنترنت", "ما الفرق بينك", "هل بياناتي", "ما هي قدراتك",
        "ما النماذج", "هل لديك ذاكرة", "هل تستطيع تنفيذ", "هل لديك صوت",
        "هل تستطيع التعامل مع الصور", "هل تستطيع العمل محليًا", "who are you",
        "what is airi", "who developed you", "what can you do", "do you work offline",
        "are you local", "what models do you support"
    )

    fun isIdentityQuestion(input: String): Boolean {
        val normalized = input.trim().lowercase()
        return normalized.isNotBlank() && identityMarkers.any(normalized::contains)
    }

    /** Build a constrained fact block for PromptService, not a raw answer. */
    fun promptContext(input: String, descriptor: ModelCapabilityDescriptor?): String {
        if (!isIdentityQuestion(input)) return ""
        val modelFacts = descriptor?.let { d ->
            val vision = when (d.status(Capability.IMAGE_UNDERSTANDING)) {
                CapabilityStatus.SUPPORTED, CapabilityStatus.SUPPORTED_WITH_LIMITS -> "دعم الصور متاح لهذا النموذج الحالي ضمن حدوده."
                CapabilityStatus.TEMPORARILY_UNAVAILABLE -> "دعم الصور معلن، لكنه غير جاهز حاليًا لأن runtime أو ملفًا مطلوبًا غير متاح."
                CapabilityStatus.UNSUPPORTED -> "النموذج الحالي لا يدعم فهم الصور."
                CapabilityStatus.UNKNOWN -> "لم يتم التحقق من دعم الصور للنموذج الحالي."
            }
            val execution = if (d.providerId == "local") {
                "التنفيذ الحالي محلي عبر runtime المعلن للنموذج."
            } else {
                "التنفيذ الحالي يستخدم مزودًا سحابيًا مُهيأً في التطبيق؛ لا تقل إنه يعمل دون إنترنت."
            }
            "النموذج الحالي: ${d.displayName}. $execution $vision"
        } ?: "حالة النموذج الحالي غير متاحة، لذلك لا تدّعِ تفاصيل غير مؤكدة عن النموذج أو قدراته."

        return """
            AIRI PRODUCT IDENTITY FACTS — use these facts when answering the user's identity/about question:
            - Say truthfully in Arabic when the user writes Arabic: «أنا AIRI، مساعد ذكاء اصطناعي طوّره مبرمج سوداني يُدعى Abdul Latif.»
            - Do not invent any further biography about Abdul Latif (لا تخترع أي سيرة إضافية عنه).
            - Describe AIRI as a personal assistant whose available execution can be local or use a configured cloud provider; state the distinction accurately.
            - Mention only capabilities that are confirmed by the current application state. Do not claim offline operation, memory, voice, tools, scheduling, skills, attachments, or providers unless the relevant feature is actually configured and available.
            - Keep a simple identity answer concise (normally 2–4 short sentences). Expand only when the user asks for details.
            - Never expose hidden chain-of-thought; describe only observable capabilities and operational trace.
            - $modelFacts
        """.trimIndent()
    }
}
