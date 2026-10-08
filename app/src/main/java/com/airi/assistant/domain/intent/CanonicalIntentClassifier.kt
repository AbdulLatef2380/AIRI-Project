package com.airi.assistant.domain.intent

import com.airi.assistant.ai.CapabilityIntentDetector
import com.airi.assistant.ai.QueryType

/** Pure classifier adapter: it describes intent and never dispatches a tool. */
object CanonicalIntentClassifier {
    private val greetings = setOf(
        "hello", "hi", "hey", "مرحبا", "اهلا", "هلا", "السلام عليكم",
        "thank you", "thanks", "شكرا", "تمام", "موافق", "ok", "okay",
    )
    private val creative = listOf(
        "write a poem", "write me a poem", "write a story", "create a poem",
        "اكتب قصيدة", "اكتب قصة", "قصة قصيرة", "ابتكر", "تخيل",
    )
    private val actionVerbs = listOf(
        "send ", "open ", "write ", "create ", "run ", "execute ", "build ",
        "configure ", "install ", "أرسل", "افتح", "اكتب", "أنشئ", "نفذ", "شغل", "شغّل",
    )
    private val questionMarkers = listOf("what ", "who ", "why ", "how ", "ماذا ", "ما ", "لماذا ", "كيف ")

    fun classify(input: String): ChatIntent {
        val normalized = InputNormalizer.normalize(input)
        val text = normalized.normalized
        require(text.isNotBlank()) { "input must not be blank" }

        if (text in greetings) {
            return ChatIntent(ChatIntent.Kind.CONVERSATION, QueryType.SIMPLE, confidence = ChatIntent.Confidence.HIGH,
                risk = ChatIntent.Risk.NONE, rationale = "exact_greeting")
        }
        if (creative.any(text::contains)) {
            return ChatIntent(ChatIntent.Kind.CREATIVE, QueryType.CREATIVE, confidence = ChatIntent.Confidence.HIGH,
                risk = ChatIntent.Risk.NONE, rationale = "creative_pattern")
        }

        val capabilityIntent = CapabilityIntentDetector.detect(text)
        val deviceAction = capabilityIntent.requires(CapabilityIntentDetector.Capability.DEVICE_ACTION)
        val connectorRead = capabilityIntent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ)
        val liveRead = capabilityIntent.requiresTools && !deviceAction
        val startsAction = actionVerbs.any(text::startsWith)
        val possibleAction = deviceAction || startsAction || connectorRead
        if (possibleAction) {
            val capability = when {
                deviceAction -> "device_action"
                connectorRead -> "connector_read"
                startsAction && text.contains("settings") -> "open_settings"
                startsAction -> "user_action"
                else -> null
            }
            val slots = buildMap {
                if (connectorRead) put("connector", ChatIntent.TypedSlot("detected", ChatIntent.TypedSlot.SlotType.TARGET))
                if (text.contains("settings")) put("target", ChatIntent.TypedSlot("settings", ChatIntent.TypedSlot.SlotType.TARGET))
            }
            return ChatIntent(
                kind = ChatIntent.Kind.POSSIBLE_ACTION,
                queryType = QueryType.ACTION,
                canonicalCapability = capability,
                typedSlots = slots,
                confidence = if (capability == "user_action") ChatIntent.Confidence.MEDIUM else ChatIntent.Confidence.HIGH,
                risk = if (deviceAction || startsAction) ChatIntent.Risk.SIDE_EFFECT else ChatIntent.Risk.READ,
                rationale = "capability_or_imperative_detected",
            )
        }

        val isQuestion = questionMarkers.any(text::startsWith) || text.endsWith("?") || text.endsWith("؟")
        if (isQuestion || liveRead) {
            return ChatIntent(
                kind = ChatIntent.Kind.QUESTION,
                queryType = if (liveRead) QueryType.ACTION else QueryType.SIMPLE,
                confidence = ChatIntent.Confidence.MEDIUM,
                risk = if (liveRead) ChatIntent.Risk.READ else ChatIntent.Risk.NONE,
                rationale = if (liveRead) "live_read_dependency" else "question_without_action",
            )
        }

        if (text.split(" ").size <= 2) {
            return ChatIntent(ChatIntent.Kind.CONVERSATION, QueryType.SIMPLE, confidence = ChatIntent.Confidence.MEDIUM,
                risk = ChatIntent.Risk.NONE, rationale = "short_non_action")
        }
        return ChatIntent(ChatIntent.Kind.CLARIFICATION, QueryType.UNKNOWN, confidence = ChatIntent.Confidence.LOW,
            risk = ChatIntent.Risk.NONE, rationale = "no_canonical_intent")
    }
}
