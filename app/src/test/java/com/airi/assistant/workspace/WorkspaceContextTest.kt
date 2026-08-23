package com.airi.assistant.workspace

import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskStatus
import com.airi.assistant.agent.durable.TaskApproval
import com.airi.assistant.agent.durable.TaskApprovalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceContextTest {

    @Test
    fun derivesProjectContextFromSessionAndArtifacts() {
        val session = WorkspaceRuntime.WorkspaceSession(
            sessionId = "project-1",
            name = "AIRI Core",
            description = "Cross-platform agent workspace",
            tags = listOf("kmp", "release")
        )
        val artifacts = listOf(
            ArtifactManager.Artifact(
                sessionId = "project-1",
                name = "plan.md",
                type = ArtifactManager.ArtifactType.MARKDOWN,
                filePath = "/workspace/plan.md"
            ),
            ArtifactManager.Artifact(
                sessionId = "project-1",
                name = "report.json",
                type = ArtifactManager.ArtifactType.JSON,
                filePath = "/workspace/report.json"
            )
        )

        val tasks = listOf(
            DurableTask(
                id = "task-running",
                projectId = "project-1",
                title = "Running task",
                description = "",
                agentId = "research",
                input = "",
                updatedAtMs = 200L,
                approvals = listOf(
                    TaskApproval(
                        id = "approval-active",
                        action = "calendar_create",
                        description = "",
                        riskLevel = "HIGH",
                        expiresAtMs = 1_000L,
                        status = TaskApprovalStatus.PENDING
                    ),
                    TaskApproval(
                        id = "approval-expired",
                        action = "calendar_create",
                        description = "",
                        riskLevel = "HIGH",
                        expiresAtMs = 99L,
                        status = TaskApprovalStatus.PENDING
                    )
                )
            ),
            DurableTask(
                id = "task-failed",
                projectId = "project-1",
                title = "Failed task",
                description = "",
                agentId = "research",
                input = "",
                status = DurableTaskStatus.FAILED,
                updatedAtMs = 150L
            ),
            DurableTask(
                id = "other-project-task",
                projectId = "project-2",
                title = "Other project",
                description = "",
                agentId = "research",
                input = ""
            )
        )

        val projectFiles = listOf(
            ProjectFileManager.ProjectFile(
                id = "file-project-1",
                projectId = "project-1",
                name = "notes.md",
                mimeType = "text/markdown"
            ),
            ProjectFileManager.ProjectFile(
                id = "file-deleted",
                projectId = "project-1",
                name = "old.md",
                mimeType = "text/markdown",
                lifecycle = ProjectFileManager.LifecycleState.DELETED
            ),
            ProjectFileManager.ProjectFile(
                id = "file-project-2",
                projectId = "project-2",
                name = "other.md",
                mimeType = "text/markdown"
            )
        )

        val context = workspaceContextFrom(session, artifacts, tasks, projectFiles, nowMs = 100L)

        assertEquals("project-1", context.workspaceId)
        assertEquals("AIRI Core", context.name)
        assertEquals(listOf("kmp", "release"), context.tags)
        assertEquals(2, context.artifactCount)
        assertEquals(listOf("plan.md", "report.json"), context.artifactNames)
        assertEquals(1, context.fileCount)
        assertEquals(2, context.taskCount)
        assertEquals(1, context.activeTaskCount)
        assertEquals(1, context.failedTaskCount)
        assertEquals("task-running", context.latestTask?.taskId)
        assertEquals(1, context.pendingApprovalCount)
        assertTrue(context.nextAction is WorkspaceNextAction.ReviewApprovals)
        assertEquals(1, (context.nextAction as WorkspaceNextAction.ReviewApprovals).pendingCount)
    }

    @Test
    fun derivesFileOrChatActionOnlyFromOwnedResources() {
        val session = WorkspaceRuntime.WorkspaceSession(sessionId = "project-1", name = "AIRI Core")
        val contextWithoutFiles = workspaceContextFrom(session, emptyList(), nowMs = 100L)
        assertTrue(contextWithoutFiles.nextAction is WorkspaceNextAction.AddProjectFile)

        val contextWithOwnedFile = workspaceContextFrom(
            session = session,
            artifacts = emptyList(),
            projectFiles = listOf(
                ProjectFileManager.ProjectFile(
                    id = "owned-file",
                    projectId = "project-1",
                    name = "notes.md",
                    mimeType = "text/markdown"
                )
            ),
            nowMs = 100L
        )
        assertTrue(contextWithOwnedFile.nextAction is WorkspaceNextAction.StartProjectChat)
    }
}
