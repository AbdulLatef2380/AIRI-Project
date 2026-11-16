package com.airi.assistant.agent.request

/**
 * Request-scoped admission contract for the file artifact path.
 *
 * This is a truthful status projection, not a permission grant. The final
 * dispatcher and governance checks remain authoritative at execution time.
 */
object FileWriteAdmission {
    enum class Status {
        EXECUTABLE,
        MODEL_HAS_NO_TOOLS,
        TOOL_NOT_EXPOSED,
        CONTEXT_BUDGET,
        DURABLE_CONTEXT_REQUIRED,
        GOVERNANCE_REQUIRED,
    }

    enum class NextAction {
        INVOKE_WRITE_FILE,
        SELECT_TOOL_CAPABLE_MODEL,
        RETRY_WITH_LARGER_CONTEXT,
        RETRY_AS_DURABLE_RUN,
        REQUEST_APPROVAL,
    }

    data class Decision(
        val requested: Boolean,
        val registered: Boolean,
        val exposed: Boolean,
        val admitted: Boolean,
        val executable: Boolean,
        val status: Status,
        val nextAction: NextAction,
    ) {
        val isReady: Boolean get() = status == Status.EXECUTABLE
    }

    fun evaluate(
        requested: Boolean,
        modelSupport: ModelToolSupport,
        registered: Boolean,
        exposed: Boolean,
        admitted: Boolean,
        hasDurableContext: Boolean,
        governanceAllowed: Boolean,
        requiredToolOmittedByBudget: Boolean = false,
    ): Decision {
        val status = when {
            !requested -> Status.TOOL_NOT_EXPOSED
            modelSupport == ModelToolSupport.NONE -> Status.MODEL_HAS_NO_TOOLS
            !registered || !exposed -> Status.TOOL_NOT_EXPOSED
            requiredToolOmittedByBudget -> Status.CONTEXT_BUDGET
            !hasDurableContext || !admitted -> Status.DURABLE_CONTEXT_REQUIRED
            !governanceAllowed -> Status.GOVERNANCE_REQUIRED
            else -> Status.EXECUTABLE
        }
        return Decision(
            requested = requested,
            registered = registered,
            exposed = exposed,
            admitted = admitted,
            executable = status == Status.EXECUTABLE,
            status = status,
            nextAction = when (status) {
                Status.EXECUTABLE -> NextAction.INVOKE_WRITE_FILE
                Status.MODEL_HAS_NO_TOOLS -> NextAction.SELECT_TOOL_CAPABLE_MODEL
                Status.CONTEXT_BUDGET -> NextAction.RETRY_WITH_LARGER_CONTEXT
                Status.DURABLE_CONTEXT_REQUIRED -> NextAction.RETRY_AS_DURABLE_RUN
                Status.GOVERNANCE_REQUIRED -> NextAction.REQUEST_APPROVAL
                Status.TOOL_NOT_EXPOSED -> NextAction.RETRY_AS_DURABLE_RUN
            },
        )
    }
}
