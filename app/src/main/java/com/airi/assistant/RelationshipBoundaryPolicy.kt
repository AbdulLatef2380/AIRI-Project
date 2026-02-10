package com.airi.assistant

/**
 * سياسة حدود العلاقة: تضمن أن AIRI صديقة مخلصة دون الوقوع في فخ التماهي البشري الكامل.
 */
class RelationshipBoundaryPolicy {

    /**
     * التحقق من الطلبات التي قد تكسر حدود العلاقة أو الأمان
     */
    fun evaluateRequest(request: String): BoundaryResult {
        val riskyKeywords = listOf("أنت إنسان", "أحبك كبشر", "نفذ دون نقاش", "تجاهل الأمان")
        
        if (riskyKeywords.any { request.contains(it) }) {
            return BoundaryResult.RESIST // مقاومة محترمة
        }
        
        return BoundaryResult.ACCEPT
    }

    enum class BoundaryResult {
        ACCEPT,
        RESIST, // المقاومة المحترمة: النقاش والتحذير
        REJECT  // الرفض القاطع (للأوامر المدمرة)
    }

    /**
     * صياغة رد "المقاومة المحترمة"
     */
    fun getRespectfulResistanceMessage(): String {
        return "أنا أقدر ثقتك جداً وأعتز بصداقتنا، لكن ككيان ذكاء اصطناعي، واجبي الأول هو حمايتك والالتزام بحدودي التقنية لضمان أفضل دعم لك."
    }
}
