package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.CapabilityIntentDetector

/**
 * Single admission point between registered capabilities and AgentLoop.
 * Product discovery is supplied separately by RuntimeCapabilityInventory; this
 * catalog contains only ready request schemas that the model may attempt.
 */
object RuntimeToolCatalog {
    data class Result(
        val candidates: List<RuntimeToolContract>,
        val exposed: List<RuntimeToolContract>,
        val filtered: List<FilteredTool>,
        /** Names selected by intent and therefore mandatory for a truthful turn. */
        val requiredToolNames: Set<String> = emptySet(),
    ) {
        val schemas: List<ToolSchema> get() = exposed.map { it.promptSchema() }
    }

    data class FilteredTool(
        val toolName: String,
        val reason: Reason,
    )

    data class StatusOverride(
        val readiness: RuntimeToolContract.Readiness,
        val reason: String,
        val nextAction: String? = null,
    )

    enum class Reason {
        NOT_READY,
        CONNECTOR_NOT_REQUESTED,
    }

    fun assemble(
        builtins: List<ToolSchema>,
        skills: List<ToolSchema>,
        connectors: List<RuntimeToolContract>,
        intent: CapabilityIntentDetector.Intent,
        statusOverrides: Map<String, StatusOverride> = emptyMap(),
        requestText: String = "",
    ): Result {
        val effectiveOverrides = AgentLoopSideEffectPolicy.blockedToolOverrides() + statusOverrides
        val builtinContracts = builtins.map { schema ->
            effectiveOverrides[schema.name]?.let { override ->
                RuntimeToolContract(
                    schema = schema,
                    readiness = override.readiness,
                    source = RuntimeToolContract.Source.BUILTIN,
                    reason = override.reason,
                    nextAction = override.nextAction,
                )
            } ?: RuntimeToolContract.builtin(schema)
        }
        val connectorContracts = connectors.map { contract ->
            effectiveOverrides[contract.schema.name]?.let { override ->
                contract.copy(readiness = override.readiness, reason = override.reason, nextAction = override.nextAction)
            } ?: contract
        }
        val skillContracts = skills.map { schema ->
            val contract = RuntimeToolContract.skill(schema)
            effectiveOverrides[schema.name]?.let { override ->
                contract.copy(readiness = override.readiness, reason = override.reason, nextAction = override.nextAction)
            } ?: contract
        }
        val candidates = builtinContracts + skillContracts + connectorContracts
        val requestedConnectorIds = intent.connectorIds
        val deviceTargetedBuiltins = targetedBuiltinNames(intent, requestText)
        val exposed = mutableListOf<RuntimeToolContract>()
        val filtered = mutableListOf<FilteredTool>()

        candidates.forEach { contract ->
            val connectorRequested = "*" in requestedConnectorIds ||
                contract.capabilityId?.let { it in requestedConnectorIds } == true
            when {
                contract.source == RuntimeToolContract.Source.CONNECTOR &&
                    contract.readiness == RuntimeToolContract.Readiness.AVAILABLE && connectorRequested ->
                    exposed += contract
                contract.source == RuntimeToolContract.Source.CONNECTOR -> filtered += FilteredTool(
                    contract.schema.name,
                    if (connectorRequested) Reason.NOT_READY else Reason.CONNECTOR_NOT_REQUESTED,
                )
                contract.readiness == RuntimeToolContract.Readiness.AVAILABLE &&
                    (contract.schema.actionClass == ToolSchema.ActionClass.READ ||
                        contract.schema.name in deviceTargetedBuiltins ||
                        contract.schema.name in setOf("terminal_execute", "write_file")) -> exposed += contract
                else -> filtered += FilteredTool(contract.schema.name, Reason.NOT_READY)
            }
        }

        val targetedBuiltins = buildSet {
            if (intent.requires(CapabilityIntentDetector.Capability.CURRENT_TIME)) add("current_time")
            if (intent.requires(CapabilityIntentDetector.Capability.MEMORY_READ)) add("memory_recall")
            if (intent.requires(CapabilityIntentDetector.Capability.WEB_SEARCH)) {
                add("web_search")
                add("fetch_url")
            }
            if (intent.requires(CapabilityIntentDetector.Capability.DEVICE_STATE) &&
                (requestText.contains("screen", ignoreCase = true) || requestText.contains("الشاشة"))) add("read_screen")
            if (intent.requires(CapabilityIntentDetector.Capability.TERMINAL_EXECUTE)) add("terminal_execute")
            if (intent.requires(CapabilityIntentDetector.Capability.FILE_WRITE) ||
                intent.requires(CapabilityIntentDetector.Capability.CODE_TASK)) add("write_file")
            if (intent.requires(CapabilityIntentDetector.Capability.CODE_TASK)) add("load_skill")
        }
        val requiredConnectorNames = exposed
            .filter { contract ->
                contract.source == RuntimeToolContract.Source.CONNECTOR &&
                    ("*" in requestedConnectorIds || contract.capabilityId?.let { it in requestedConnectorIds } == true)
            }
            .map { it.schema.name }
            .toSet()
        val requiredToolNames = targetedBuiltins + deviceTargetedBuiltins + requiredConnectorNames
        val normalizedRequest = CapabilityIntentDetector.normalize(requestText)
        val ordered = exposed.distinctBy { it.schema.name }.sortedWith(
            compareBy<RuntimeToolContract> { contract ->
                when {
                    contract.source == RuntimeToolContract.Source.CONNECTOR -> 0
                    contract.schema.name in targetedBuiltins -> 1
                    contract.source == RuntimeToolContract.Source.SKILL &&
                        schemaMatchesRequest(contract.schema, normalizedRequest) -> 2
                    contract.source == RuntimeToolContract.Source.SKILL -> 3
                    contract.source == RuntimeToolContract.Source.BUILTIN -> 4
                    else -> 5
                }
            }.thenByDescending { contract ->
                if (contract.source == RuntimeToolContract.Source.CONNECTOR) {
                    requestRelevanceScore(contract.schema, normalizedRequest)
                } else 0
            }.thenBy { it.schema.name }
        )

        return Result(
            candidates = candidates,
            exposed = ordered,
            filtered = filtered,
            requiredToolNames = requiredToolNames,
        )
    }

    private fun schemaMatchesRequest(schema: ToolSchema, normalizedRequest: String): Boolean {
        if (normalizedRequest.isBlank()) return false
        val tokens = (schema.name + " " + schema.description)
            .lowercase()
            .split(Regex("[^a-z0-9\\u0600-\\u06ff]+"))
            .filter { it.length >= 4 }
        return tokens.any { token -> normalizedRequest.contains(token) }
    }

    /**
     * Rank the requested connector action before sibling actions. Without this
     * score, alphabetical ordering can fill a small prompt with `list_*`
     * actions and omit the requested `get_file`/`gmail_read` action entirely.
     */
    private fun requestRelevanceScore(schema: ToolSchema, normalizedRequest: String): Int {
        if (normalizedRequest.isBlank()) return 0
        val request = normalizedRequest
            .replace("مستودعات", "repos")
            .replace("مستودع", "repo")
            .replace("ملفات", "files")
            .replace("ملف", "file")
            .replace("رسائل", "email")
            .replace("بريد", "email")
            .replace("آخر", "latest")
            .replace("اقرأ", "read")
            .replace("لخص", "summarize")
            .replace("تلخيص", "summarize")
            .replace("غير مقروء", "unread")
            .replace("مشكلة", "issue")
            .replace("مشكلات", "issues")
            .replace("طلب سحب", "pull request")
        val tokens = (schema.name + " " + schema.description)
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 }
            .distinct()
        val lexicalScore = tokens.sumOf { token ->
            when {
                request.contains(token) -> token.length
                else -> 0
            }
        }
        val action = schema.name.substringAfterLast('_')
        val focusedScore = when {
            schema.name.contains("gmail_read") &&
                listOf("read", "latest", "summarize", "unread", "inbox", "sent").any { request.contains(it) } -> 40
            schema.name.contains("gmail_list") &&
                listOf("email", "latest", "unread", "inbox", "sent", "list").any { request.contains(it) } -> 30
            schema.name.contains("github_get_file") &&
                listOf("file", "read", "content").any { request.contains(it) } -> 40
            action == "read" && request.contains("read") -> 10
            else -> 0
        }
        return lexicalScore + focusedScore
    }

    private fun targetedBuiltinNames(
        intent: CapabilityIntentDetector.Intent,
        requestText: String,
    ): Set<String> = buildSet {
        val normalized = CapabilityIntentDetector.normalize(requestText)
        if (intent.requires(CapabilityIntentDetector.Capability.DEVICE_ACTION)) {
            fun requested(vararg phrases: String): Boolean = phrases.any(normalized::contains)
            if (requested("افتح تطبيق", "افتح برنامج", "open app", "launch app")) add("open_app")
            if (requested("اضغط", "انقر", "tap", "click")) add("tap")
            if (requested("اكتب داخل", "اكتب في الحقل", "type text", "type into")) add("type_text")
            if (requested("مرر للاسفل", "مرر لأسفل", "scroll down")) add("scroll_down")
            if (requested("ارجع للخلف", "رجوع", "go back", "back button")) add("go_back")
            if (requested("فلاش", "المصباح", "الكشاف", "flashlight", "torch")) add("flashlight")
            if (requested("اضبط منبه", "اضبط المنبه", "set alarm", "set an alarm")) add("set_alarm")
            if (requested("انشئ ملاحظة", "انشاء ملاحظة", "أضف ملاحظة", "اضف ملاحظة", "create a note", "add a note")) add("create_note")
        }
        if ((intent.requires(CapabilityIntentDetector.Capability.CONNECTOR_WRITE) &&
            requestText.contains("calendar", ignoreCase = true)) ||
            (intent.requires(CapabilityIntentDetector.Capability.DEVICE_ACTION) &&
                (normalized.contains("موعد") || normalized.contains("حدث")))) {
            add("calendar_create")
        }
    }

    private fun RuntimeToolContract.promptSchema(): ToolSchema {
        if (readiness == RuntimeToolContract.Readiness.AVAILABLE) return schema
        val status = readiness.name.lowercase()
        val detail = reason?.takeIf { it.isNotBlank() } ?: "Capability is not ready"
        return schema.copy(description = "${schema.description} [READINESS=$status: $detail]")
    }
}
