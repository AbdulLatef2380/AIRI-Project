package com.airi.assistant.ui.activity

import com.airi.assistant.core.UniversalRuntimeTraceEvent
import com.airi.assistant.core.UniversalTraceEventType

/** Projects only user-meaningful, redacted runtime milestones into the live activity feed. */
internal object RuntimeTraceActivityMapper {
    fun map(event: UniversalRuntimeTraceEvent): ActivityEvent? {
        val presentation = when (event.eventType) {
            UniversalTraceEventType.REQUEST_RECEIVED -> Presentation(
                category = ActivityCategory.ORCHESTRATION,
                tag = "runtime_request_received",
                detail = details(event, "inputLength", "intent"),
            )
            UniversalTraceEventType.MODEL_CONTEXT_RESOLVED -> Presentation(
                category = ActivityCategory.MODEL,
                tag = "runtime_model_context_resolved",
                detail = details(event, "provider", "model", "executionMode"),
            )
            UniversalTraceEventType.CAPABILITY_FILTERED -> Presentation(
                category = ActivityCategory.ROUTING,
                tag = "runtime_capability_filtered",
                detail = event.attributes.entries
                    .filter { it.key.startsWith("outcome.") }
                    .take(6)
                    .joinToString("; ") { (key, value) -> "$key=$value" }
                    .ifBlank { null },
            )
            UniversalTraceEventType.TOOLS_EXPOSED -> Presentation(
                category = ActivityCategory.ROUTING,
                tag = "runtime_tools_exposed",
                detail = buildString {
                    append("count=").append(event.toolNames.size)
                    event.toolNames.take(6).takeIf { it.isNotEmpty() }?.let {
                        append("; tools=").append(it.joinToString(", "))
                    }
                },
            )
            UniversalTraceEventType.MODEL_RESPONSE_RECEIVED -> Presentation(
                category = ActivityCategory.MODEL,
                tag = "runtime_model_response_received",
                detail = details(
                    event,
                    "length",
                    "containsToolCallCandidate",
                    "parserInputClassification",
                ),
            )
            UniversalTraceEventType.TOOL_SELECTED -> if (event.outcome == "rejected") {
                Presentation(
                    category = ActivityCategory.TOOL,
                    tag = "runtime_tool_rejected",
                    detail = details(event, "schemaValidation"),
                )
            } else null
            UniversalTraceEventType.EXECUTION_PATH_RESOLVED -> Presentation(
                category = ActivityCategory.ROUTING,
                tag = "runtime_execution_path_resolved",
                detail = details(event, "expectedPath", "actualPath")
                    ?.plus("; classification=${event.outcome}"),
            )
            UniversalTraceEventType.CONNECTOR_EXECUTION_COMPLETED -> if (event.outcome == "failure") {
                Presentation(
                    category = ActivityCategory.CONNECTOR,
                    tag = "runtime_connector_failed",
                    severity = ActivitySeverity.ERROR,
                    detail = details(event, "durationMs", "errorCategory"),
                )
            } else null
            UniversalTraceEventType.RESULT_RETURNED -> Presentation(
                category = ActivityCategory.ORCHESTRATION,
                tag = "runtime_result_returned",
                detail = event.toolNames.take(3).takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = "tools=", separator = ", "),
            )
            UniversalTraceEventType.MODEL_CONTINUATION_COMPLETED -> Presentation(
                category = ActivityCategory.MODEL,
                tag = "runtime_model_continuation_completed",
                detail = details(event, "terminalState")
                    ?.plus("; outcome=${event.outcome}"),
            )
            UniversalTraceEventType.INTENT_STAGE_RECORDED -> {
                val stage = event.attributes["stage"]
                if (stage in VISIBLE_INTENT_STAGES) {
                    Presentation(
                        category = ActivityCategory.ROUTING,
                        tag = "runtime_intent_stage",
                        detail = listOfNotNull(stage?.let { "stage=$it" }, "outcome=${event.outcome}")
                            .joinToString("; "),
                    )
                } else null
            }
            else -> null
        } ?: return null

        return ActivityEvent(
            id = "runtime:${event.traceId}:${event.sequence}",
            timestampMs = event.timestampMs,
            message = presentation.tag,
            detail = presentation.detail,
            executionId = event.executionId,
            category = presentation.category,
            severity = presentation.severity,
            machineTag = presentation.tag,
        )
    }

    private fun details(event: UniversalRuntimeTraceEvent, vararg keys: String): String? =
        keys.mapNotNull { key ->
            event.attributes[key]
                ?.takeIf(String::isNotBlank)
                ?.let { value -> "$key=$value" }
        }.joinToString("; ").ifBlank { null }

    private data class Presentation(
        val category: ActivityCategory,
        val tag: String,
        val severity: ActivitySeverity = ActivitySeverity.INFO,
        val detail: String? = null,
    )

    private val VISIBLE_INTENT_STAGES = setOf(
        "INTENT_RESOLVED",
        "CAPABILITY_REQUESTED",
        "CATALOG_ASSEMBLED",
    )
}
