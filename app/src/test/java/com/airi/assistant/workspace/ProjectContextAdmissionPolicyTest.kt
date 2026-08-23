package com.airi.assistant.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectContextAdmissionPolicyTest {

    @Test
    fun admit_rejectsCandidatesOwnedByAnotherProject() {
        val resolution = ProjectContextAdmissionPolicy.admit(
            requestedProjectId = "project-b",
            candidates = listOf(
                candidate(projectId = "project-a", label = "secret-a.txt", content = "must never enter B"),
                candidate(projectId = "project-b", label = "build.gradle", content = "Project B configuration")
            ),
            charBudget = 500
        )

        val admitted = resolution as ProjectContextResolution.Admitted
        assertEquals(listOf("build.gradle"), admitted.candidates.map { it.label })
        assertEquals(1, admitted.omittedCount)
        assertTrue(admitted.formatPromptBlock().contains("Project B configuration"))
        assertTrue(!admitted.formatPromptBlock().contains("secret-a.txt"))
    }

    @Test
    fun admit_enforcesBudgetWithoutIncludingPartialReference() {
        val resolution = ProjectContextAdmissionPolicy.admit(
            requestedProjectId = "project-a",
            candidates = listOf(
                candidate("project-a", "metadata", "small"),
                candidate("project-a", "large", "x".repeat(420))
            ),
            charBudget = 120
        )

        val admitted = resolution as ProjectContextResolution.Admitted
        assertEquals(listOf("metadata"), admitted.candidates.map { it.label })
        assertEquals(1, admitted.omittedCount)
    }

    @Test
    fun admit_returnsUnscopedForBlankProject() {
        val resolution = ProjectContextAdmissionPolicy.admit(
            requestedProjectId = "",
            candidates = listOf(candidate("project-a", "file", "content")),
            charBudget = 500
        )

        assertEquals(ProjectContextResolution.Unscoped, resolution)
    }

    private fun candidate(projectId: String, label: String, content: String) = ProjectContextCandidate(
        projectId = projectId,
        kind = ProjectContextKind.FILE,
        label = label,
        content = content
    )
}
