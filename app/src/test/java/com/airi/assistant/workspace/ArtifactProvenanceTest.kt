package com.airi.assistant.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactProvenanceTest {

    @Test
    fun acceptsBoundedProjectOwnedTaskExecutionMetadata() {
        val provenance = ArtifactProvenance(
            projectId = "project-a",
            taskId = "task-a",
            runId = "run-a",
            stepId = "step-a",
            toolId = "research_agent",
            modelId = "local-model",
            summary = "Generated task result"
        )

        assertTrue(provenance.isWellFormed())
    }

    @Test
    fun rejectsPartialTaskCoordinatesAndSensitiveSummary() {
        val partial = ArtifactProvenance(
            projectId = "project-a",
            taskId = "task-a",
            runId = "run-a"
        )
        val sensitive = ArtifactProvenance(
            projectId = "project-a",
            summary = "Authorization: Bearer 12345678901234567890"
        )

        assertFalse(partial.isWellFormed())
        assertFalse(sensitive.isWellFormed())
    }
}
