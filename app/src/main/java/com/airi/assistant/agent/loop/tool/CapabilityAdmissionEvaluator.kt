package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.CapabilityIntentDetector

/**
 * Single policy boundary for request-scoped capability admission.
 *
 * The catalog describes what was registered and exposed. This evaluator adds
 * the request intent and source-build failures so callers cannot silently
 * downgrade a required capability to an empty catalog.
 */
object CapabilityAdmissionEvaluator {
    data class Evaluation(
        val result: CapabilityAssemblyResult,
        val decisions: List<CapabilityDecision>,
    )

    fun evaluate(
        catalog: RuntimeToolCatalog.Result,
        intent: CapabilityIntentDetector.Intent,
        failures: List<CapabilitySourceFailure> = emptyList(),
        skillsRequested: Boolean = false,
    ): Evaluation {
        val requestedConnectors = intent.connectorIds
        val requiredNames = catalog.requiredToolNames
        val decisions = catalog.candidates
            .distinctBy { it.schema.name }
            .map { contract ->
                val name = contract.schema.name
                val requested = name in requiredNames || when (contract.source) {
                    RuntimeToolContract.Source.CONNECTOR ->
                        "*" in requestedConnectors || contract.capabilityId?.let { it in requestedConnectors } == true
                    else -> false
                }
                val exposed = catalog.exposed.any { it.schema.name == name }
                val executable = exposed && contract.readiness == RuntimeToolContract.Readiness.AVAILABLE
                decision(
                    capabilityId = contract.capabilityId ?: name,
                    requested = requested,
                    registered = true,
                    exposed = exposed,
                    executable = executable,
                    readiness = contract.readiness,
                    reason = contract.reason,
                )
            }

        val relevantFailures = failures.filter { failure ->
            when (failure.sourceId) {
                "connectors" -> requestedConnectors.isNotEmpty()
                "skills" -> skillsRequested
                else -> intent.requiresTools
            }
        }
        val assembly = when {
            relevantFailures.isNotEmpty() -> CapabilityAssemblyResult.Failed(
                failures = relevantFailures,
                decisions = decisions,
            )
            failures.isNotEmpty() -> CapabilityAssemblyResult.Degraded(
                catalog = catalog,
                failedSources = failures,
                decisions = decisions,
            )
            else -> CapabilityAssemblyResult.Ready(catalog = catalog, decisions = decisions)
        }
        return Evaluation(assembly, decisions)
    }

    private fun decision(
        capabilityId: String,
        requested: Boolean,
        registered: Boolean,
        exposed: Boolean,
        executable: Boolean,
        readiness: RuntimeToolContract.Readiness,
        reason: String?,
    ): CapabilityDecision {
        val status = when {
            !requested -> Availability.NOT_REQUESTED
            !registered -> Availability.NOT_REGISTERED
            readiness == RuntimeToolContract.Readiness.REQUIRES_AUTH -> Availability.AUTH_REQUIRED
            readiness == RuntimeToolContract.Readiness.PERMISSION_REQUIRED -> Availability.PERMISSION_REQUIRED
            readiness == RuntimeToolContract.Readiness.DISCONNECTED ||
                readiness == RuntimeToolContract.Readiness.NOT_READY -> Availability.DISCONNECTED
            readiness == RuntimeToolContract.Readiness.BLOCKED -> Availability.BLOCKED_BY_POLICY
            readiness == RuntimeToolContract.Readiness.CONFIGURATION_REQUIRED -> Availability.CONFIGURATION_REQUIRED
            readiness == RuntimeToolContract.Readiness.DEPENDENCY_MISSING -> Availability.DEPENDENCY_MISSING
            readiness == RuntimeToolContract.Readiness.UNSUPPORTED -> Availability.UNSUPPORTED
            !exposed -> Availability.CONTEXT_OMITTED
            executable -> Availability.READY
            else -> Availability.BUILD_FAILED
        }
        return CapabilityDecision(
            capabilityId = capabilityId,
            requested = requested,
            registered = registered,
            exposed = exposed,
            executable = executable,
            status = status,
            reason = reason,
            nextAction = when (status) {
                Availability.AUTH_REQUIRED -> NextAction.AUTHORIZE
                Availability.PERMISSION_REQUIRED -> NextAction.GRANT_PERMISSION
                Availability.DISCONNECTED -> NextAction.CONNECT
                Availability.CONTEXT_OMITTED -> NextAction.SHORTEN_REQUEST
                Availability.CONFIGURATION_REQUIRED,
                Availability.DEPENDENCY_MISSING -> NextAction.RECONFIGURE
                Availability.BUILD_FAILED -> NextAction.RETRY
                Availability.BLOCKED_BY_POLICY -> NextAction.RECONFIGURE
                else -> NextAction.NONE
            },
        )
    }
}
