package com.airi.assistant.workspace

import com.airi.assistant.agent.durable.ResumableProjectFileWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFileEditPolicyTest {

    @Test
    fun diffSummaryReportsChangedAddedAndRemovedLinesWithinPreview() {
        val summary = ProjectFileEditPolicy.summarizeDiff(
            before = "title\nold\nremoved",
            after = "title\nnew\nadded"
        )

        assertEquals(2, summary.addedLineCount)
        assertEquals(2, summary.removedLineCount)
        assertTrue(summary.preview.contains("-old"))
        assertTrue(summary.preview.contains("+new"))
    }

    @Test
    fun persistedProposalRequiresBoundedIdsAndSha256IntegrityFields() {
        val valid = proposal()
        val unsafeId = valid.copy(id = "proposal with space")
        val invalidHash = valid.copy(candidateContentHash = "not-a-hash")

        assertTrue(ProjectFileEditPolicy.validPersistedProposal(valid))
        assertFalse(ProjectFileEditPolicy.validPersistedProposal(unsafeId))
        assertFalse(ProjectFileEditPolicy.validPersistedProposal(invalidHash))
    }

    @Test
    fun proposalDescriptorContainsNoCandidateTextField() {
        val properties = ResumableProjectFileWrite::class.java.declaredFields.map { it.name }

        assertFalse(properties.any { it.contains("text", ignoreCase = true) || it.contains("content", ignoreCase = true) && !it.contains("hash", ignoreCase = true) })
        assertTrue(properties.containsAll(listOf("proposalId", "targetFileId", "baseContentHash", "candidateContentHash", "idempotencyKey")))
    }

    private fun proposal() = ProjectFileEditRuntime.Proposal(
        id = "proposal-1",
        projectId = "project-a",
        taskId = "task-1",
        missionId = "task-1",
        runId = "run-1",
        stepId = "step-1",
        targetFileId = "file-1",
        targetFileName = "notes.txt",
        baseContentHash = "a".repeat(64),
        candidateContentHash = "b".repeat(64),
        diff = ProjectFileEditRuntime.DiffSummary(1, 1, "+new\n-old")
    )
}
