package com.airi.assistant.workspace

import android.content.Context
import android.util.Log
import com.airi.assistant.agent.durable.ApprovalContinuation
import com.airi.assistant.agent.durable.ApprovalContinuationStatus
import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.agent.durable.DurableTaskStatus
import com.airi.assistant.agent.durable.ResumableProjectFileWrite
import com.airi.assistant.agent.durable.TaskApprovalStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local, project-owned proposal -> approval -> apply runtime for managed text
 * files. It deliberately does not expose arbitrary paths or place candidate
 * text in durable task JSON, timeline details, artifact provenance, or logs.
 */
class ProjectFileEditRuntime(
    private val context: Context,
    private val workspaceRuntime: WorkspaceRuntime,
    private val projectFileManager: ProjectFileManager,
    private val durableTaskManager: DurableTaskManager,
    private val artifactManager: ArtifactManager
) {
    enum class ProposalStatus {
        DRAFT,
        PENDING_APPROVAL,
        CLAIMED,
        APPLIED,
        FAILED,
        REJECTED,
        EXPIRED,
        CANCELLED
    }

    data class DiffSummary(
        val addedLineCount: Int,
        val removedLineCount: Int,
        /** Private, bounded review text; it is not durable task evidence. */
        val preview: String
    )

    data class Proposal(
        val id: String = UUID.randomUUID().toString(),
        val projectId: String,
        val taskId: String,
        val missionId: String,
        val runId: String,
        val stepId: String,
        val targetFileId: String,
        val targetFileName: String,
        val baseContentHash: String,
        val candidateContentHash: String,
        val diff: DiffSummary,
        val createdAtMs: Long = System.currentTimeMillis(),
        val updatedAtMs: Long = createdAtMs,
        val approvalId: String? = null,
        val continuationId: String? = null,
        val status: ProposalStatus = ProposalStatus.DRAFT,
        val outcome: String = ""
    )

    sealed class ProposalResult {
        data class Created(val proposal: Proposal) : ProposalResult()
        data class ApprovalPending(val proposal: Proposal) : ProposalResult()
        data class Applied(val proposal: Proposal, val artifactId: String) : ProposalResult()
        data class Rejected(val reason: String) : ProposalResult()
        data class Conflict(val reason: String) : ProposalResult()
        data class Failed(val reason: String) : ProposalResult()
    }

    private val gson = Gson()
    private val proposals = ConcurrentHashMap<String, Proposal>()
    private val indexFile = File(context.filesDir, "workspace/project-file-edits/index.json")
    private val payloadDirectory = File(context.filesDir, "workspace/project-file-edits/payloads")
    private val _allProposals = MutableStateFlow<List<Proposal>>(emptyList())
    val allProposals: StateFlow<List<Proposal>> = _allProposals.asStateFlow()

    init {
        restore()
    }

    fun forProject(projectId: String): List<Proposal> = allProposals.value
        .filter { it.projectId == projectId }
        .sortedByDescending { it.updatedAtMs}

    fun getProposalForProject(proposalId: String, projectId: String): Proposal? =
        proposals[proposalId]?.takeIf { it.projectId == projectId }

    /**
     * Creates a persisted, private candidate only for the active project and a
     * running exact task step. The caller supplies already-reviewed candidate
     * text; this method never asks a model to fabricate a file edit.
     */
    suspend fun createProposal(
        projectId: String,
        taskId: String,
        missionId: String,
        runId: String,
        stepId: String,
        targetFileId: String,
        candidateText: String
    ): ProposalResult = withContext(Dispatchers.IO) {
        val task = ownedRunningTask(projectId, taskId, missionId, runId, stepId)
            ?: return@withContext ProposalResult.Rejected("The active task does not own this project step")
        val target = projectFileManager.findById(targetFileId)
            ?.takeIf { it.projectId == projectId }
            ?: return@withContext ProposalResult.Rejected("The selected project file is unavailable")
        val sourceText = projectFileManager.readTextForEdit(projectId, targetFileId)
            ?: return@withContext ProposalResult.Rejected("Only ready managed text files can be proposed for editing")
        val baseHash = sha256(sourceText)
        if (target.sha256 != baseHash) {
            return@withContext ProposalResult.Conflict("The project-file metadata is stale; refresh before proposing an edit")
        }
        if (candidateText.toByteArray(Charsets.UTF_8).size > MAX_CANDIDATE_BYTES) {
            return@withContext ProposalResult.Rejected("The proposed content exceeds the local edit limit")
        }
        val candidateHash = sha256(candidateText)
        if (candidateHash == baseHash) {
            return@withContext ProposalResult.Rejected("The proposed content does not change the file")
        }

        val proposal = Proposal(
            projectId = projectId,
            taskId = task.id,
            missionId = missionId,
            runId = runId,
            stepId = stepId,
            targetFileId = targetFileId,
            targetFileName = target.name,
            baseContentHash = baseHash,
            candidateContentHash = candidateHash,
            diff = ProjectFileEditPolicy.summarizeDiff(sourceText, candidateText)
        )
        if (!writeCandidate(proposal.id, candidateText)) {
            return@withContext ProposalResult.Failed("The private edit proposal could not be saved")
        }
        proposals[proposal.id] = proposal
        if (!persist()) {
            deleteCandidate(proposal.id)
            proposals.remove(proposal.id)
            return@withContext ProposalResult.Failed("The edit proposal index could not be saved")
        }
        publish()
        ProposalResult.Created(proposal)
    }

    /** Persists approval and a typed, one-shot continuation before any file write. */
    fun requestApproval(proposalId: String): ProposalResult {
        val proposal = proposals[proposalId] ?: return ProposalResult.Rejected("The edit proposal is unavailable")
        if (proposal.status != ProposalStatus.DRAFT) {
            return ProposalResult.Rejected("This edit proposal is not awaiting approval")
        }
        if (ownedRunningTask(proposal) == null) {
            return ProposalResult.Rejected("The active task no longer owns this project step")
        }
        if (!candidateMatches(proposal)) {
            return ProposalResult.Failed("The private edit proposal no longer passes its integrity check")
        }
        val current = projectFileManager.findById(proposal.targetFileId)
            ?: return ProposalResult.Rejected("The target project file is unavailable")
        if (current.projectId != proposal.projectId || current.sha256 != proposal.baseContentHash) {
            return ProposalResult.Conflict("The project file changed before approval")
        }

        val approval = durableTaskManager.requestApproval(
            taskId = proposal.taskId,
            action = "project_file_write",
            description = "Apply reviewed edit to ${safeLabel(proposal.targetFileName)} (${proposal.diff.addedLineCount} additions, ${proposal.diff.removedLineCount} removals)",
            riskLevel = "HIGH",
            runId = proposal.runId,
            stepId = proposal.stepId
        ) ?: return ProposalResult.Rejected("The task cannot accept a file-write approval")
        val continuation = ApprovalContinuation(
            approvalId = approval.id,
            taskId = proposal.taskId,
            missionId = proposal.missionId,
            projectId = proposal.projectId,
            runId = proposal.runId,
            stepId = proposal.stepId,
            projectFileWrite = ResumableProjectFileWrite(
                proposalId = proposal.id,
                targetFileId = proposal.targetFileId,
                baseContentHash = proposal.baseContentHash,
                candidateContentHash = proposal.candidateContentHash,
                idempotencyKey = "file-edit-${proposal.id}"
            ),
            expiresAtMs = approval.expiresAtMs
        )
        val pending = proposal.copy(
            approvalId = approval.id,
            continuationId = continuation.id,
            status = ProposalStatus.PENDING_APPROVAL,
            updatedAtMs = System.currentTimeMillis(),
            outcome = "Waiting for one explicit approval"
        )
        proposals[proposal.id] = pending
        if (!persist()) {
            proposals[proposal.id] = proposal
            durableTaskManager.decideApproval(
                approvalId = approval.id,
                status = TaskApprovalStatus.DENIED,
                reason = "Project-file proposal persistence failed"
            )
            return ProposalResult.Failed("The edit proposal could not be persisted for approval")
        }
        if (!durableTaskManager.pauseForApproval(proposal.taskId, approval.id, continuation)) {
            proposals[proposal.id] = proposal.copy(
                status = ProposalStatus.FAILED,
                updatedAtMs = System.currentTimeMillis(),
                outcome = "The exact task step could not be paused for approval"
            )
            persist()
            durableTaskManager.decideApproval(
                approvalId = approval.id,
                status = TaskApprovalStatus.DENIED,
                reason = "Exact-step pause rejected"
            )
            publish()
            return ProposalResult.Failed("The exact task step could not be paused for approval")
        }
        publish()
        return ProposalResult.ApprovalPending(pending)
    }

    /** Claims and applies one approved project-file continuation exactly once. */
    suspend fun resume(approvalId: String): ProposalResult? = withContext(Dispatchers.IO) {
        val pending = durableTaskManager.continuationForApproval(approvalId) ?: return@withContext null
        if (pending.invocation != null || pending.projectFileWrite == null) return@withContext null
        val continuation = durableTaskManager.claimApprovedContinuation(approvalId) ?: return@withContext null
        val write = continuation.projectFileWrite ?: return@withContext null
        val proposal = proposals[write.proposalId]
        if (proposal == null || !matchesContinuation(proposal, continuation, write)) {
            return@withContext failClaimedContinuation(
                continuation,
                proposal,
                "The project-file proposal is missing or no longer matches its approved operation"
            )
        }
        if (!workspaceOwns(proposal.projectId) || ownedRunningTask(proposal) == null) {
            return@withContext failClaimedContinuation(
                continuation,
                proposal,
                "The active workspace or task no longer owns this project step"
            )
        }
        if (!candidateMatches(proposal)) {
            return@withContext failClaimedContinuation(
                continuation,
                proposal,
                "The private candidate content failed its integrity check"
            )
        }
        val claimed = proposal.copy(
            status = ProposalStatus.CLAIMED,
            updatedAtMs = System.currentTimeMillis(),
            outcome = "Approved edit claimed for one local apply"
        )
        proposals[proposal.id] = claimed
        if (!persist()) {
            return@withContext failClaimedContinuation(
                continuation,
                claimed,
                "The claimed edit state could not be persisted"
            )
        }
        publish()

        val candidate = readCandidate(claimed.id)
            ?: return@withContext failClaimedContinuation(
                continuation,
                claimed,
                "The private candidate content is unavailable"
            )
        when (val applied = projectFileManager.applyTextRevision(
            projectId = claimed.projectId,
            id = claimed.targetFileId,
            expectedContentHash = claimed.baseContentHash,
            candidateText = candidate
        )) {
            is ProjectFileManager.TextRevisionResult.Applied -> {
                val evidence = createEvidence(claimed, applied.file.sha256)
                if (evidence == null || !durableTaskManager.linkArtifact(
                        taskId = claimed.taskId,
                        artifactId = evidence.id,
                        runId = claimed.runId,
                        stepId = claimed.stepId
                    )
                ) {
                    evidence?.let { artifactManager.deleteArtifact(it.id) }
                    val rollback = projectFileManager.restoreTextRevision(
                        projectId = claimed.projectId,
                        id = claimed.targetFileId,
                        expectedCurrentHash = applied.file.sha256,
                        backup = applied.backup
                    )
                    val rollbackOutcome = if (rollback is ProjectFileManager.TextRevisionResult.Applied) {
                        "Evidence linkage failed; the prior file content was restored"
                    } else {
                        "Evidence linkage failed after write; manual recovery is required"
                    }
                    return@withContext failClaimedContinuation(continuation, claimed, rollbackOutcome)
                }
                val completed = claimed.copy(
                    status = ProposalStatus.APPLIED,
                    updatedAtMs = System.currentTimeMillis(),
                    outcome = "Approved project-file revision applied"
                )
                proposals[completed.id] = completed
                persist()
                deleteCandidate(completed.id)
                durableTaskManager.finishApprovalContinuation(
                    continuationId = continuation.id,
                    outcome = "Approved project-file revision applied",
                    succeeded = true
                )
                durableTaskManager.markStepCompleted(completed.taskId, completed.stepId)
                publish()
                ProposalResult.Applied(completed, evidence.id)
            }
            is ProjectFileManager.TextRevisionResult.Conflict ->
                failClaimedContinuation(continuation, claimed, applied.reason, conflict = true)
            is ProjectFileManager.TextRevisionResult.Rejected ->
                failClaimedContinuation(continuation, claimed, applied.reason)
            is ProjectFileManager.TextRevisionResult.Failed ->
                failClaimedContinuation(continuation, claimed, applied.reason)
        }
    }

    /** Recovery considers only the typed project-file continuation list. */
    suspend fun resumeApprovedAfterRecovery(): List<ProposalResult> =
        durableTaskManager.approvedProjectFileWriteApprovalIds().mapNotNull { resume(it) }

    /** Reconciles a denial or expiry immediately so private candidate content is removed. */
    fun reconcileApproval(approvalId: String): Boolean {
        val proposal = proposals.values.firstOrNull { it.approvalId == approvalId } ?: return false
        if (proposal.status != ProposalStatus.PENDING_APPROVAL) return false
        val approval = durableTaskManager.findApproval(approvalId)?.second ?: return false
        val terminal = when (approval.status) {
            TaskApprovalStatus.DENIED -> ProposalStatus.REJECTED
            TaskApprovalStatus.EXPIRED -> ProposalStatus.EXPIRED
            else -> return false
        }
        proposals[proposal.id] = proposal.copy(
            status = terminal,
            updatedAtMs = System.currentTimeMillis(),
            outcome = "Approval ${terminal.name.lowercase()}"
        )
        deleteCandidate(proposal.id)
        persist()
        publish()
        return true
    }

    /** Cancels a draft or still-pending proposal without changing managed file bytes. */
    fun cancelProposal(proposalId: String, reason: String = "Cancelled before file apply"): Boolean {
        val current = proposals[proposalId] ?: return false
        if (current.status !in setOf(ProposalStatus.DRAFT, ProposalStatus.PENDING_APPROVAL)) return false
        current.approvalId?.let { approvalId ->
            durableTaskManager.decideApproval(
                approvalId = approvalId,
                status = TaskApprovalStatus.DENIED,
                reason = reason
            )
        }
        proposals[proposalId] = current.copy(
            status = ProposalStatus.CANCELLED,
            updatedAtMs = System.currentTimeMillis(),
            outcome = safeOutcome(reason)
        )
        deleteCandidate(proposalId)
        persist()
        publish()
        return true
    }

    private fun ownedRunningTask(
        projectId: String,
        taskId: String,
        missionId: String,
        runId: String,
        stepId: String
    ): DurableTask? {
        if (!workspaceOwns(projectId)) return null
        val task = durableTaskManager.getTask(taskId) ?: return null
        return task.takeIf {
            it.status == DurableTaskStatus.RUNNING &&
                durableTaskManager.ownsConnectorExecution(taskId, missionId, projectId, runId, stepId)
        }
    }

    private fun ownedRunningTask(proposal: Proposal): DurableTask? = ownedRunningTask(
        projectId = proposal.projectId,
        taskId = proposal.taskId,
        missionId = proposal.missionId,
        runId = proposal.runId,
        stepId = proposal.stepId
    )

    private fun workspaceOwns(projectId: String): Boolean =
        workspaceRuntime.activeSession.value?.sessionId == projectId

    private fun matchesContinuation(
        proposal: Proposal,
        continuation: ApprovalContinuation,
        write: ResumableProjectFileWrite
    ): Boolean =
        continuation.status == ApprovalContinuationStatus.CLAIMED &&
            continuation.taskId == proposal.taskId &&
            continuation.missionId == proposal.missionId &&
            continuation.projectId == proposal.projectId &&
            continuation.runId == proposal.runId &&
            continuation.stepId == proposal.stepId &&
            write.proposalId == proposal.id &&
            write.targetFileId == proposal.targetFileId &&
            write.baseContentHash == proposal.baseContentHash &&
            write.candidateContentHash == proposal.candidateContentHash &&
            continuation.id == proposal.continuationId

    private fun candidateMatches(proposal: Proposal): Boolean =
        readCandidate(proposal.id)?.let(::sha256) == proposal.candidateContentHash

    private suspend fun createEvidence(proposal: Proposal, appliedHash: String): ArtifactManager.Artifact? = runCatching {
        artifactManager.createArtifact(
            sessionId = proposal.projectId,
            name = "project-file-edit-${proposal.id.take(8)}",
            type = ArtifactManager.ArtifactType.TEXT,
            content = buildString {
                appendLine("Approved project-file revision applied")
                appendLine("Target: ${safeLabel(proposal.targetFileName)}")
                appendLine("Base integrity: ${proposal.baseContentHash.take(HASH_PREFIX_CHARS)}")
                appendLine("Applied integrity: ${appliedHash.take(HASH_PREFIX_CHARS)}")
                appendLine("Diff: +${proposal.diff.addedLineCount} / -${proposal.diff.removedLineCount}")
            },
            description = "Approved managed project-file revision",
            provenance = ArtifactProvenance(
                projectId = proposal.projectId,
                taskId = proposal.taskId,
                runId = proposal.runId,
                stepId = proposal.stepId,
                toolId = TOOL_ID,
                summary = "Approved project-file revision; +${proposal.diff.addedLineCount}/-${proposal.diff.removedLineCount}; ${appliedHash.take(HASH_PREFIX_CHARS)}"
            )
        )
    }.getOrNull()

    private fun failClaimedContinuation(
        continuation: ApprovalContinuation,
        proposal: Proposal?,
        reason: String,
        conflict: Boolean = false
    ): ProposalResult {
        val safe = safeOutcome(reason)
        proposal?.let {
            proposals[it.id] = it.copy(
                status = if (conflict) ProposalStatus.FAILED else ProposalStatus.FAILED,
                updatedAtMs = System.currentTimeMillis(),
                outcome = safe
            )
            persist()
            publish()
        }
        durableTaskManager.finishApprovalContinuation(continuation.id, safe, succeeded = false)
        durableTaskManager.markStepFailed(continuation.taskId, continuation.stepId, safe)
        return if (conflict) ProposalResult.Conflict(safe) else ProposalResult.Failed(safe)
    }

    private fun reconcileTerminalApprovals() {
        proposals.values
            .filter { it.status == ProposalStatus.PENDING_APPROVAL }
            .mapNotNull { it.approvalId }
            .forEach(::reconcileApproval)
    }

    private fun writeCandidate(proposalId: String, candidate: String): Boolean = runCatching {
        payloadDirectory.mkdirs()
        val destination = candidateFile(proposalId)
        val temp = File(payloadDirectory, ".${proposalId}.tmp")
        temp.writeText(candidate, Charsets.UTF_8)
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
        true
    }.getOrDefault(false)

    private fun readCandidate(proposalId: String): String? = runCatching {
        val candidate = candidateFile(proposalId)
        if (candidate.exists()) candidate.readText(Charsets.UTF_8) else null
    }.getOrNull()

    private fun deleteCandidate(proposalId: String) {
        runCatching { candidateFile(proposalId).delete() }
    }

    private fun candidateFile(proposalId: String): File =
        File(payloadDirectory, "$proposalId.candidate")

    private fun restore() {
        runCatching {
            if (!indexFile.exists()) return
            val type = object : TypeToken<List<Proposal>>() {}.type
            val restored: List<Proposal> = gson.fromJson(indexFile.readText(Charsets.UTF_8), type) ?: emptyList()
            restored.filter { ProjectFileEditPolicy.validPersistedProposal(it) }
                .forEach { proposals[it.id] = it }
            publish()
        }.onFailure { error ->
            Log.w(TAG, "PROJECT_FILE_EDIT_RESTORE_FAILED type=${error.javaClass.simpleName}")
        }
    }

    private fun persist(): Boolean = runCatching {
        indexFile.parentFile?.mkdirs()
        val temp = File(indexFile.parentFile, "${indexFile.name}.tmp")
        temp.writeText(gson.toJson(proposals.values.toList()), Charsets.UTF_8)
        if (!temp.renameTo(indexFile)) {
            temp.copyTo(indexFile, overwrite = true)
            temp.delete()
        }
        true
    }.getOrElse { error ->
        Log.w(TAG, "PROJECT_FILE_EDIT_PERSIST_FAILED type=${error.javaClass.simpleName}")
        false
    }

    private fun publish() {
        _allProposals.value = proposals.values.sortedByDescending { it.updatedAtMs }
    }

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun safeOutcome(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().take(MAX_OUTCOME_CHARS)

    private fun safeLabel(value: String): String =
        value.replace(Regex("[\\r\\n]"), " ").trim().take(MAX_FILE_LABEL_CHARS)

    private companion object {
        const val TAG = "ProjectFileEditRuntime"
        const val TOOL_ID = "project_file_editor"
        const val MAX_CANDIDATE_BYTES = 1_024 * 1_024
        const val MAX_OUTCOME_CHARS = 240
        const val MAX_FILE_LABEL_CHARS = 100
        const val HASH_PREFIX_CHARS = 12
    }
}

/** Pure policy for bounded diff rendering and safe proposal-index restoration. */
internal object ProjectFileEditPolicy {
    private const val MAX_PREVIEW_LINES = 120
    private val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
    private val SHA256 = Regex("^[a-f0-9]{64}$")

    fun summarizeDiff(before: String, after: String): ProjectFileEditRuntime.DiffSummary {
        val oldLines = before.lines()
        val newLines = after.lines()
        val shared = minOf(oldLines.size, newLines.size)
        var added = 0
        var removed = 0
        val preview = buildList {
            for (index in 0 until shared) {
                if (oldLines[index] != newLines[index]) {
                    removed++
                    added++
                    add("-${oldLines[index]}")
                    add("+${newLines[index]}")
                }
            }
            oldLines.drop(shared).forEach {
                removed++
                add("-$it")
            }
            newLines.drop(shared).forEach {
                added++
                add("+$it")
            }
        }.take(MAX_PREVIEW_LINES).joinToString("\n")
        return ProjectFileEditRuntime.DiffSummary(added, removed, preview)
    }

    fun validPersistedProposal(proposal: ProjectFileEditRuntime.Proposal): Boolean =
        proposal.id.matches(SAFE_ID) &&
            proposal.projectId.matches(SAFE_ID) &&
            proposal.taskId.matches(SAFE_ID) &&
            proposal.missionId.matches(SAFE_ID) &&
            proposal.runId.matches(SAFE_ID) &&
            proposal.stepId.matches(SAFE_ID) &&
            proposal.targetFileId.matches(SAFE_ID) &&
            proposal.baseContentHash.matches(SHA256) &&
            proposal.candidateContentHash.matches(SHA256) &&
            proposal.diff.preview.length <= MAX_PREVIEW_LINES * 4_096
}
