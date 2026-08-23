package com.airi.assistant.workspace

import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskStatus
import com.airi.assistant.agent.durable.TaskApprovalStatus

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
    val fileCount: Int,
    val taskCount: Int,
    val activeTaskCount: Int,
    val failedTaskCount: Int,
    val latestTask: WorkspaceTaskSummary? = null,
    val pendingApprovalCount: Int = 0,
    val nextAction: WorkspaceNextAction = WorkspaceNextAction.StartProjectChat
)

/** A bounded, non-sensitive task projection suitable for a project overview. */
data class WorkspaceTaskSummary(
    val taskId: String,
    val title: String,
    val status: DurableTaskStatus,
    val currentStepTitle: String?,
    val updatedAtMs: Long
)

/**
 * A deterministic next action derived only from project-owned runtime state.
 * It never executes an action and never asks a model to infer user intent.
 */
sealed interface WorkspaceNextAction {
    data class ReviewApprovals(val pendingCount: Int) : WorkspaceNextAction
    data class OpenActiveTask(val taskId: String) : WorkspaceNextAction
    data class ReviewFailedTask(val taskId: String) : WorkspaceNextAction
    data object AddProjectFile : WorkspaceNextAction
    data object StartProjectChat : WorkspaceNextAction
}

internal fun workspaceContextFrom(
    session: WorkspaceRuntime.WorkspaceSession,
    artifacts: List<ArtifactManager.Artifact>,
    tasks: List<DurableTask> = emptyList(),
    projectFiles: List<ProjectFileManager.ProjectFile> = emptyList(),
    nowMs: Long = System.currentTimeMillis()
): WorkspaceContext {
    val projectTasks = tasks
        .asSequence()
        .filter { it.projectId == session.sessionId }
        .sortedByDescending { it.updatedAtMs }
        .toList()
    val activeFiles = projectFiles.count {
        it.projectId == session.sessionId &&
            it.lifecycle != ProjectFileManager.LifecycleState.DELETED
    }
    val pendingApprovalCount = projectTasks.sumOf { task ->
        task.approvals.count { approval ->
            approval.status == TaskApprovalStatus.PENDING && approval.expiresAtMs > nowMs
        }
    }
    val latestTask = projectTasks.firstOrNull()?.let { task ->
        WorkspaceTaskSummary(
            taskId = task.id,
            title = task.title,
            status = task.status,
            currentStepTitle = task.currentStepId
                ?.let { stepId -> task.plan.firstOrNull { it.id == stepId }?.title },
            updatedAtMs = task.updatedAtMs
        )
    }
    val nextAction = when {
        pendingApprovalCount > 0 -> WorkspaceNextAction.ReviewApprovals(pendingApprovalCount)
        projectTasks.firstOrNull { !it.isTerminal } != null ->
            WorkspaceNextAction.OpenActiveTask(projectTasks.first { !it.isTerminal }.id)
        projectTasks.firstOrNull { it.status == DurableTaskStatus.FAILED } != null ->
            WorkspaceNextAction.ReviewFailedTask(projectTasks.first { it.status == DurableTaskStatus.FAILED }.id)
        activeFiles == 0 -> WorkspaceNextAction.AddProjectFile
        else -> WorkspaceNextAction.StartProjectChat
    }
    return WorkspaceContext(
        workspaceId = session.sessionId,
        name = session.name,
        description = session.description,
        tags = session.tags,
        artifactCount = artifacts.size,
        artifactNames = artifacts.map { it.name },
        fileCount = activeFiles,
        taskCount = projectTasks.size,
        activeTaskCount = projectTasks.count { !it.isTerminal },
        failedTaskCount = projectTasks.count { it.status == DurableTaskStatus.FAILED },
        latestTask = latestTask,
        pendingApprovalCount = pendingApprovalCount,
        nextAction = nextAction
    )
}
