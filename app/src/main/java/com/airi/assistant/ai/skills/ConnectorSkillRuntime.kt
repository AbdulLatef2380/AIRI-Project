package com.airi.assistant.ai.skills

import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.core.ServiceLocator

/**
 * Canonical adapter for official skills that operate external connectors.
 * Skills must not instantiate the legacy ToolExecutor: the connector runtime
 * owns authorization, provider grants, lifecycle checks, retries and tracing.
 */
object ConnectorSkillRuntime {
    suspend fun execute(
        connectorId: String,
        action: String,
        params: Map<String, String> = emptyMap(),
    ): SkillResult {
        val output = ServiceLocator.connectorRuntimeManager.execute(
            connectorId = connectorId,
            input = ConnectorInput(
                action = action,
                params = params,
                authorizationActionId = action,
            ),
        )
        return when (output) {
            is ConnectorOutput.Success -> SkillResult(
                success = true,
                data = output.text,
                error = null,
                metadata = output.data + mapOf("execution_path" to "connector_runtime"),
            )
            is ConnectorOutput.Failure -> SkillResult(
                success = false,
                data = "",
                error = output.message,
                metadata = mapOf(
                    "execution_path" to "connector_runtime",
                    "failure_code" to output.code,
                    "retryable" to output.retryable.toString(),
                ),
            )
            is ConnectorOutput.ApprovalRequired -> SkillResult(
                success = false,
                data = "",
                error = output.message,
                metadata = mapOf(
                    "execution_path" to "connector_runtime",
                    "failure_code" to "approval_required",
                    "approval_id" to output.approvalId,
                ),
            )
            is ConnectorOutput.Streaming -> SkillResult(
                success = false,
                data = "",
                error = "Streaming connector output is not supported by this skill.",
                metadata = mapOf(
                    "execution_path" to "connector_runtime",
                    "failure_code" to "streaming_unsupported",
                ),
            )
        }
    }
}
