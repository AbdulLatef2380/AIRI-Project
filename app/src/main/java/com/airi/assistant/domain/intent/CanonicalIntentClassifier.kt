package com.airi.assistant.domain.intent

import com.airi.assistant.ai.CapabilityIntentDetector
import com.airi.assistant.ai.QueryType

/** Pure classifier adapter: it describes intent and never dispatches a tool. */
object CanonicalIntentClassifier {
    // InputNormalizer folds hamza forms (أ/إ/آ -> ا), ى -> ي and strips diacritics from the
    // INPUT. Every phrase list below is passed through the same normalizer so that entries such
    // as "أرسل" / "أنشئ" can match the normalized text ("ارسل" / "انشئ"). Without this, any verb
    // spelled with a hamza in the list could never match (report item 1).
    private fun normalizedList(vararg phrases: String): List<String> =
        phrases.map { InputNormalizer.normalize(it).normalized }
            .distinct()

    private val creative = normalizedList(
        "write a poem", "write me a poem", "write a story", "create a poem",
        "اكتب قصيدة", "اكتب قصة", "قصة قصيرة", "ابتكر", "تخيل",
    )

    /**
     * Imperative verbs. Latin verbs keep a trailing space (word boundary); Arabic verbs are
     * matched as prefixes. [InputNormalizer.normalize] trims trailing spaces, so the Latin
     * entries are re-suffixed after normalization.
     */
    private val actionVerbs: List<String> = run {
        val latin = listOf("send", "open", "write", "create", "run", "execute", "build", "configure", "install")
            .map { "$it " }
        val arabic = normalizedList(
            "أرسل", "ارسل", "افتح", "اكتب", "أنشئ", "انشئ", "انشي", "أنشىء", "انشىء",
            "نفذ", "شغل", "شغّل",
        )
        latin + arabic
    }

    private val questionMarkers: List<String> = run {
        val latin = listOf("what", "who", "why", "how").map { "$it " }
        val arabic = normalizedList("ماذا", "ما", "لماذا", "كيف").map { "$it " }
        latin + arabic
    }

    fun classify(input: String): ChatIntent {
        val normalized = InputNormalizer.normalize(input)
        val text = normalized.normalized
        require(text.isNotBlank()) { "input must not be blank" }

        if (ConversationIntentDetector.isGreeting(text)) {
            return ChatIntent(ChatIntent.Kind.CONVERSATION, QueryType.SIMPLE, confidence = ChatIntent.Confidence.HIGH,
                risk = ChatIntent.Risk.NONE, rationale = "exact_greeting")
        }
        if (creative.any(text::contains)) {
            return ChatIntent(ChatIntent.Kind.CREATIVE, QueryType.CREATIVE, confidence = ChatIntent.Confidence.HIGH,
                risk = ChatIntent.Risk.NONE, rationale = "creative_pattern")
        }

        val capabilityIntent = CapabilityIntentDetector.detect(text)
        if (CapabilityIntentDetector.isCapabilityDiscovery(text)) {
            return ChatIntent(
                kind = ChatIntent.Kind.QUESTION,
                queryType = QueryType.ANALYTICAL,
                canonicalCapability = "capability_discovery",
                confidence = ChatIntent.Confidence.HIGH,
                risk = ChatIntent.Risk.READ,
                rationale = "capability_inventory_question",
            )
        }
        val deviceAction = capabilityIntent.requires(CapabilityIntentDetector.Capability.DEVICE_ACTION)
        val terminalExecute = capabilityIntent.requires(CapabilityIntentDetector.Capability.TERMINAL_EXECUTE)
        val connectorRead = capabilityIntent.requires(CapabilityIntentDetector.Capability.CONNECTOR_READ)
        val connectorWrite = capabilityIntent.requires(CapabilityIntentDetector.Capability.CONNECTOR_WRITE)
        val fileWrite = capabilityIntent.requires(CapabilityIntentDetector.Capability.FILE_WRITE)
        val liveRead = capabilityIntent.requiresTools && !deviceAction && !terminalExecute && !fileWrite
        val startsAction = actionVerbs.any(text::startsWith)
        val possibleAction = deviceAction || terminalExecute || fileWrite || startsAction || connectorRead || connectorWrite
        if (possibleAction) {
            val capability = when {
                deviceAction -> "device_action"
                terminalExecute -> "terminal_execute"
                fileWrite -> "file_write"
                connectorWrite -> "connector_write"
                connectorRead -> "connector_read"
                startsAction && text.contains("settings") -> "open_settings"
                startsAction -> "user_action"
                else -> null
            }
            val slots = buildMap {
                if (connectorRead || connectorWrite) put("connector", ChatIntent.TypedSlot("detected", ChatIntent.TypedSlot.SlotType.TARGET))
                if (text.contains("settings")) put("target", ChatIntent.TypedSlot("settings", ChatIntent.TypedSlot.SlotType.TARGET))
            }
            return ChatIntent(
                kind = ChatIntent.Kind.POSSIBLE_ACTION,
                queryType = QueryType.ACTION,
                canonicalCapability = capability,
                typedSlots = slots,
                confidence = if (capability == "user_action") ChatIntent.Confidence.MEDIUM else ChatIntent.Confidence.HIGH,
                risk = if (deviceAction || terminalExecute || fileWrite || startsAction || connectorWrite) ChatIntent.Risk.SIDE_EFFECT else ChatIntent.Risk.READ,
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
