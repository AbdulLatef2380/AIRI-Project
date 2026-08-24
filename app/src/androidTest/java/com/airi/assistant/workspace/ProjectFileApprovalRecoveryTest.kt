package com.airi.assistant.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airi.assistant.agent.durable.ApprovalGrantScope
import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.agent.durable.TaskApprovalStatus
import com.airi.assistant.agent.durable.TaskPlanStep
import com.airi.assistant.agent.sandbox.SandboxManager
import com.airi.assistant.media.MediaLibrary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Release-journey fixture for the local project-file path. It recreates the
 * durable task, workspace, file, artifact, and proposal runtimes after approval
 * and proves that one owned continuation resumes once without a provider.
 */
@RunWith(AndroidJUnit4::class)
class ProjectFileApprovalRecoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun approvedProjectFileEditRecoversOnceWithOwnedArtifactEvidence() = runBlocking {
        val suffix = UUID.randomUUID().toString().take(8)
        val taskId = "journey-task-$suffix"
        val missionId = "journey-mission-$suffix"
        val runId = "journey-run-$suffix"
        val stepId = "apply-edit-$suffix"

        val initialTasks = DurableTaskManager(context)
        val initialArtifacts = ArtifactManager(context, durableTaskManager = initialTasks)
        val initialFiles = ProjectFileManager(context, MediaLibrary(context))
        val initialWorkspace = WorkspaceRuntime(
            context = context,
            sandboxManager = SandboxManager(context),
            artifactManager = initialArtifacts,
            durableTaskManager = initialTasks,
            projectFileManager = initialFiles
        )
        val project = initialWorkspace.createSession("journey-$suffix")
        val imported = initialFiles.importFromBytes(
            projectId = project.sessionId,
            name = "release-journey.txt",
            mimeType = "text/plain",
            bytes = "before-$suffix".toByteArray()
        ) as ProjectFileManager.ImportResult.Imported

        initialTasks.registerInProcess(
            DurableTask(
                id = taskId,
                projectId = project.sessionId,
                missionId = missionId,
                ownerId = "fixture-owner-$suffix",
                title = "Apply local project-file edit",
                description = "Release journey fixture",
                agentId = "fixture-agent",
                input = "Apply one local edit",
                plan = listOf(TaskPlanStep(id = stepId, title = "Apply local edit"))
            )
        )
        initialTasks.beginRun(taskId, runId, stepId)

        val initialRuntime = ProjectFileEditRuntime(
            context = context,
            workspaceRuntime = initialWorkspace,
            projectFileManager = initialFiles,
            durableTaskManager = initialTasks,
            artifactManager = initialArtifacts
        )
        val created = initialRuntime.createProposal(
            projectId = project.sessionId,
            taskId = taskId,
            missionId = missionId,
            runId = runId,
            stepId = stepId,
            targetFileId = imported.file.id,
            candidateText = "after-$suffix"
        ) as ProjectFileEditRuntime.ProposalResult.Created
        val pending = initialRuntime.requestApproval(created.proposal.id)
            as ProjectFileEditRuntime.ProposalResult.ApprovalPending
        val approvalId = requireNotNull(pending.proposal.approvalId)
        assertTrue(
            initialTasks.decideApproval(
                approvalId = approvalId,
                status = TaskApprovalStatus.APPROVED,
                scope = ApprovalGrantScope.ONCE,
                reason = "Fixture approval before runtime recreation"
            )
        )

        // Recreate runtime owners from app-private storage as startup recovery does.
        val recoveredTasks = DurableTaskManager(context)
        val recoveredArtifacts = ArtifactManager(context, durableTaskManager = recoveredTasks)
        val recoveredFiles = ProjectFileManager(context, MediaLibrary(context))
        val recoveredWorkspace = WorkspaceRuntime(
            context = context,
            sandboxManager = SandboxManager(context),
            artifactManager = recoveredArtifacts,
            durableTaskManager = recoveredTasks,
            projectFileManager = recoveredFiles
        )
        assertEquals(project.sessionId, recoveredWorkspace.activeSession.value?.sessionId)
        val recoveredRuntime = ProjectFileEditRuntime(
            context = context,
            workspaceRuntime = recoveredWorkspace,
            projectFileManager = recoveredFiles,
            durableTaskManager = recoveredTasks,
            artifactManager = recoveredArtifacts
        )

        val applied = recoveredRuntime.resumeApprovedAfterRecovery().single()
            as ProjectFileEditRuntime.ProposalResult.Applied
        assertEquals("after-$suffix", recoveredFiles.readTextForEdit(project.sessionId, imported.file.id))
        val artifact = recoveredArtifacts.getArtifactForProject(applied.artifactId, project.sessionId)
        assertNotNull(artifact)
        assertEquals(project.sessionId, artifact?.projectId)
        assertEquals(taskId, artifact?.taskId)
        assertEquals(runId, artifact?.runId)
        assertEquals(stepId, artifact?.stepId)
        assertTrue(recoveredTasks.getTask(taskId)?.artifactIds?.contains(applied.artifactId) == true)
        assertTrue(recoveredRuntime.resumeApprovedAfterRecovery().isEmpty())

        recoveredTasks.markCompleted(taskId, "Fixture complete")
        recoveredArtifacts.deleteArtifact(applied.artifactId)
        assertTrue(recoveredFiles.delete(imported.file.id))
        assertTrue(recoveredFiles.purge(imported.file.id))
        recoveredWorkspace.closeSession(project.sessionId)
    }
}
