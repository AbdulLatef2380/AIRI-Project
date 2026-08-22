package com.airi.assistant.workspace

import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskStatus
import org.junit.Assert.assertEquals
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
                input = ""
            ),
            DurableTask(
                id = "task-failed",
                projectId = "project-1",
                title = "Failed task",
                description = "",
                agentId = "research",
                input = "",
                status = DurableTaskStatus.FAILED
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

        val context = workspaceContextFrom(session, artifacts, tasks)

        assertEquals("project-1", context.workspaceId)
        assertEquals("AIRI Core", context.name)
        assertEquals(listOf("kmp", "release"), context.tags)
        assertEquals(2, context.artifactCount)
        assertEquals(listOf("plan.md", "report.json"), context.artifactNames)
        assertEquals(2, context.taskCount)
        assertEquals(1, context.activeTaskCount)
        assertEquals(1, context.failedTaskCount)
    }
}
