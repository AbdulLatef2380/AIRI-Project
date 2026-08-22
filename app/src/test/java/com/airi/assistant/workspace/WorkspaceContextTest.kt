package com.airi.assistant.workspace

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

        val context = workspaceContextFrom(session, artifacts)

        assertEquals("project-1", context.workspaceId)
        assertEquals("AIRI Core", context.name)
        assertEquals(listOf("kmp", "release"), context.tags)
        assertEquals(2, context.artifactCount)
        assertEquals(listOf("plan.md", "report.json"), context.artifactNames)
    }
}
