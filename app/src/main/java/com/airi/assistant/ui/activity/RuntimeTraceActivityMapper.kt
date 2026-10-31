package com.airi.assistant.ui.activity

import com.airi.assistant.core.UniversalRuntimeTraceEvent
import com.airi.assistant.core.UniversalTraceEventType

/** Projects only user-meaningful, redacted runtime milestones into the live activity feed. */
internal object RuntimeTraceActivityMapper {
    fun map(event: UniversalRuntimeTraceEvent): ActivityEvent? {
        val presentation = when (event.eventType) {
            // Admission evidence is diagnostic-only. It must not become a row
            // in the user's activity feed or a fake task in the projection.
            UniversalTraceEventType.REQUEST_RECEIVED,
            UniversalTraceEventType.CAPABILITY_SNAPSHOT_RESOLVED,
            UniversalTraceEventType.CAPABILITY_FILTERED,
            UniversalTraceEventType.CAPABILITY_STAGE_RECORDED,
            UniversalTraceEventType.TOOLS_EXPOSED,
            UniversalTraceEventType.INTENT_STAGE_RECORDED,
            UniversalTraceEventType.EXECUTION_PATH_RESOLVED,
            UniversalTraceEventType.TOOL_PARSE_COMPLETED,
            UniversalTraceEventType.MODEL_REQUEST_BUILT,
            UniversalTraceEventType.UI_PROJECTION_COMPLETED -> null

            UniversalTraceEventType.MODEL_CONTEXT_RESOLVED -> Presentation(
                category = ActivityCategory.MODEL,
                tag = "runtime_model_context_resolved",
                detail = details(event, "provider", "model", "executionMode"),
                metadata = ActivityMetadata(
                    phase = ActivityPhase.MODEL,
                    status = ActivityStatus.COMPLETED,
                ),
            )

            UniversalTraceEventType.MODEL_RESPONSE_RECEIVED -> Presentation(
                category = ActivityCategory.MODEL,
                tag = "runtime_model_response_received",
                detail = details(event, "length", "containsToolCallCandidate", "parserInputClassification"),
                metadata = ActivityMetadata(
                    phase = ActivityPhase.MODEL,
                    status = ActivityStatus.COMPLETED,
                ),
            )

            UniversalTraceEventType.TOOL_SELECTED -> if (event.outcome == "rejected") {
                Presentation(
                    category = ActivityCategory.TOOL,
                    tag = "runtime_tool_rejected",
                    detail = details(event, "schemaValidation"),
                    metadata = actionMetadata(event, ActivityStatus.FAILED),
                )
            } else null

            UniversalTraceEventType.CONNECTOR_EXECUTION_COMPLETED -> if (event.outcome == "failure") {
                Presentation(
                    category = ActivityCategory.CONNECTOR,
                    tag = "runtime_connector_failed",
                    severity = ActivitySeverity.ERROR,
                    detail = details(event, "durationMs", "errorCategory"),
                    metadata = actionMetadata(event, ActivityStatus.FAILED),
                )
            } else null

            UniversalTraceEventType.RESULT_RETURNED -> Presentation(
                category = ActivityCategory.ORCHESTRATION,
                tag = "runtime_result_returned",
                detail = event.toolNames.take(3).takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = "tools=", separator = ", "),
                metadata = actionMetadata(event, ActivityStatus.COMPLETED),
            )

            // Continuation and parser milestones are internal implementation
            // details; the user sees the resulting action or final response.
            UniversalTraceEventType.MODEL_CONTINUATION_COMPLETED -> null
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
            metadata = presentation.metadata,
        )
    }

    private fun actionMetadata(
        event: UniversalRuntimeTraceEvent,
        status: ActivityStatus,
    ): ActivityMetadata = ActivityMetadata(
        phase = if (status == ActivityStatus.COMPLETED) ActivityPhase.RESULT else ActivityPhase.ACTION,
        status = status,
        parentId = event.attributes["parentId"],
        actionId = event.attributes["actionId"] ?: event.toolNames.firstOrNull(),
    )

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
        val metadata: ActivityMetadata = ActivityMetadata(),
    )
}
