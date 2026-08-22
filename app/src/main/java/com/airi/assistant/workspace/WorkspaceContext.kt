package com.airi.assistant.workspace

import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskStatus

/**
 * Product-facing context for the currently selected AIRI workspace.
 *
 * The context is derived from the existing workspace session, artifact store,
 * and canonical durable task store. It intentionally owns no duplicate state.
 */
data class WorkspaceContext(
    val workspaceId: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val artifactCount: Int,
    val artifactNames: List<String>,
    val taskCount: Int,
    val activeTaskCount: Int,
    val failedTaskCount: Int
)

internal fun workspaceContextFrom(
    session: WorkspaceRuntime.WorkspaceSession,
    artifacts: List<ArtifactManager.Artifact>,
    tasks: List<DurableTask> = emptyList()
): WorkspaceContext {
    val projectTasks = tasks.filter { it.projectId == session.sessionId }
    return WorkspaceContext(
        workspaceId = session.sessionId,
        name = session.name,
        description = session.description,
        tags = session.tags,
        artifactCount = artifacts.size,
        artifactNames = artifacts.map { it.name },
        taskCount = projectTasks.size,
        activeTaskCount = projectTasks.count { !it.isTerminal },
        failedTaskCount = projectTasks.count { it.status == DurableTaskStatus.FAILED }
    )
}
