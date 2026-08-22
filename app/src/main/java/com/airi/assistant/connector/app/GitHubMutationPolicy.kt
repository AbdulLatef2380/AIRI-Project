package com.airi.assistant.connector.app

/**
 * Safety boundary for GitHub actions exposed through the generic connector
 * runtime. Read actions may execute after connector-health checks. Mutations
 * require a task-owned approval and a resumable execution contract, neither of
 * which is carried by [com.airi.assistant.connector.ConnectorInput] yet.
 */
object GitHubMutationPolicy {
    sealed class Decision {
        data object Allowed : Decision()
        data class RequiresTaskApproval(val reason: String) : Decision()
    }

    fun evaluate(action: String): Decision = when (action) {
        "create_issue" -> Decision.RequiresTaskApproval(
            "Creating a GitHub issue changes an external repository and must run through a task-owned approval flow"
        )
        else -> Decision.Allowed
    }
}
