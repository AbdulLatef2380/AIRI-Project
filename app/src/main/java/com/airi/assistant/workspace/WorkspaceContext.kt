package com.airi.assistant.workspace

/**
 * Product-facing context for the currently selected AIRI workspace.
 * It is derived from the existing WorkspaceRuntime session and artifact store,
 * so it does not create a second source of truth.
 */
data class WorkspaceContext(
    val workspaceId: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val artifactCount: Int,
    val artifactNames: List<String>
)

internal fun workspaceContextFrom(
    session: WorkspaceRuntime.WorkspaceSession,
    artifacts: List<ArtifactManager.Artifact>
): WorkspaceContext = WorkspaceContext(
    workspaceId = session.sessionId,
    name = session.name,
    description = session.description,
    tags = session.tags,
    artifactCount = artifacts.size,
    artifactNames = artifacts.map { it.name }
)
