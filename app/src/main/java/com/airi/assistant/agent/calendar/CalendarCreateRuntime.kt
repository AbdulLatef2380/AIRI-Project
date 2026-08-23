package com.airi.assistant.agent.calendar

import android.content.Context
import com.airi.assistant.agent.durable.ApprovalContinuation
import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.agent.durable.DurableTaskStatus
import com.airi.assistant.agent.durable.ResumableCalendarCreate
import com.airi.assistant.agent.durable.TaskApprovalStatus
import com.airi.assistant.agent.durable.TaskStepStatus
import com.airi.assistant.agent.loop.AgentLoopExecutionContext
import com.airi.assistant.tools.execution.CalendarTool
import com.airi.assistant.workspace.ArtifactManager
import com.airi.assistant.workspace.ArtifactProvenance
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Typed local calendar mutation runtime.
 *
 * The proposal payload is retained only in app-private storage. Durable task
 * JSON contains ownership coordinates, hashes, a fixed calendar policy and an
 * idempotency key, never event text, time text, prompt history, credentials or
 * provider response bodies. A claimed insert is deliberately never retried.
 */
class CalendarCreateRuntime(
    private val context: Context,
    private val durableTaskManager: DurableTaskManager,
    private val artifactManager: ArtifactManager,
    private val writer: CalendarEventWriter = AndroidCalendarEventWriter(context)
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

    data class Proposal(
        val id: String = UUID.randomUUID().toString(),
        val projectId: String,
        val taskId: String,
        val missionId: String,
        val runId: String,
        val stepId: String,
        val titleHash: String,
        val scheduleHash: String,
        val createdAtMs: Long = System.currentTimeMillis(),
        val updatedAtMs: Long = createdAtMs,
        val approvalId: String? = null,
        val continuationId: String? = null,
        val status: ProposalStatus = ProposalStatus.DRAFT,
        val outcome: String = ""
    )

    /** Private review data is never copied to the durable task or artifact. */
    data class PrivateReview(
        val proposalId: String,
        val title: String,
        val startMs: Long,
        val durationMinutes: Int
    )

    private data class PrivatePayload(
        val title: String,
        val startMs: Long,
        val durationMinutes: Int
    )

    sealed class ProposalResult {
        data class Created(val proposal: Proposal) : ProposalResult()
        data class ApprovalPending(val proposal: Proposal) : ProposalResult()
        data class Applied(val proposal: Proposal, val artifactId: String) : ProposalResult()
        data class Rejected(val reason: String) : ProposalResult()
        data class Failed(val reason: String) : ProposalResult()
    }

    private val gson = Gson()
    private val proposals = ConcurrentHashMap<String, Proposal>()
    private val indexFile = File(context.filesDir, "agent/calendar-proposals/index.json")
    private val payloadDirectory = File(context.filesDir, "agent/calendar-proposals/payloads")

    init {
        restore()
        reconcileExpiredApprovals()
    }

    /**
     * Creates a private proposal from the model's structured call after exact
     * task/run/step ownership is already established. The first migration accepts
     * only ISO-8601 instants so recovery is deterministic and locale-independent.
     */
    suspend fun createProposal(
        execution: AgentLoopExecutionContext,
        title: String?,
        startTime: String?,
        durationText: String?
    ): ProposalResult = withContext(Dispatchers.IO) {
        val task = ownedRunningTask(execution)
            ?: return@withContext ProposalResult.Rejected("The active calendar task no longer owns this project step")
        val normalizedTitle = title?.trim().orEmpty()
        if (normalizedTitle.isEmpty() || normalizedTitle.length > MAX_TITLE_CHARS || looksSensitive(normalizedTitle)) {
            return@withContext ProposalResult.Rejected("The calendar event title is not accepted")
        }
        val startMs = startTime?.trim()?.let(::parseInstant)
            ?: return@withContext ProposalResult.Rejected("Calendar creation requires an ISO-8601 start time with an offset")
        val durationMinutes = durationText?.trim()?.toIntOrNull() ?: DEFAULT_DURATION_MINUTES
        if (durationMinutes !in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES) {
            return@withContext ProposalResult.Rejected("The calendar event duration is outside the supported range")
        }
        val titleHash = sha256(normalizedTitle)
        val scheduleHash = sha256("$startMs:$durationMinutes")
        val proposal = Proposal(
            projectId = requireNotNull(execution.projectId),
            taskId = task.id,
            missionId = execution.missionId,
            runId = execution.runId,
            stepId = execution.stepId,
            titleHash = titleHash,
            scheduleHash = scheduleHash
        )
        val payload = PrivatePayload(normalizedTitle, startMs, durationMinutes)
        if (!writePayload(proposal.id, payload)) {
            return@withContext ProposalResult.Failed("The private calendar proposal could not be saved")
        }
        proposals[proposal.id] = proposal
        if (!persist()) {
            proposals.remove(proposal.id)
            deletePayload(proposal.id)
            return@withContext ProposalResult.Failed("The calendar proposal index could not be saved")
        }
        ProposalResult.Created(proposal)
    }

    /** Returns details only from private proposal storage for an approval review UI. */
    fun privateReviewForApproval(approvalId: String): PrivateReview? {
        val approval = durableTaskManager.findApproval(approvalId)?.second ?: return null
        if (approval.status != TaskApprovalStatus.PENDING || approval.expiresAtMs <= System.currentTimeMillis()) {
            return null
        }
        val proposal = proposals.values.firstOrNull { it.approvalId == approvalId }
            ?.takeIf { it.status == ProposalStatus.PENDING_APPROVAL }
            ?: return null
        val payload = readPayload(proposal.id) ?: return null
        if (!payloadMatches(proposal, payload)) return null
        return PrivateReview(
            proposalId = proposal.id,
            title = payload.title,
            startMs = payload.startMs,
            durationMinutes = payload.durationMinutes
        )
    }

    /** Persists an approval and exact typed continuation before any provider I/O. */
    fun requestApproval(proposalId: String): ProposalResult {
        val proposal = proposals[proposalId] ?: return ProposalResult.Rejected("The calendar proposal is unavailable")
        if (proposal.status != ProposalStatus.DRAFT) {
            return ProposalResult.Rejected("This calendar proposal is not awaiting approval")
        }
        if (ownedRunningTask(proposal) == null) {
            return ProposalResult.Rejected("The active calendar task no longer owns this project step")
        }
        val payload = readPayload(proposal.id)
            ?: return ProposalResult.Failed("The private calendar proposal is unavailable")
        if (!payloadMatches(proposal, payload)) {
            return ProposalResult.Failed("The private calendar proposal failed its integrity check")
        }
        val approval = durableTaskManager.requestApproval(
            taskId = proposal.taskId,
            action = CALENDAR_CREATE,
            description = "Create one reviewed calendar event for the active project",
            riskLevel = "HIGH",
            runId = proposal.runId,
            stepId = proposal.stepId
        ) ?: return ProposalResult.Rejected("The calendar task cannot accept an approval")
        val continuation = ApprovalContinuation(
            approvalId = approval.id,
            taskId = proposal.taskId,
            missionId = proposal.missionId,
            projectId = proposal.projectId,
            runId = proposal.runId,
            stepId = proposal.stepId,
            calendarCreate = ResumableCalendarCreate(
                proposalId = proposal.id,
                titleHash = proposal.titleHash,
                scheduleHash = proposal.scheduleHash,
                calendarPolicy = CALENDAR_POLICY,
                idempotencyKey = "calendar-${proposal.id}"
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
                reason = "Calendar proposal persistence failed"
            )
            return ProposalResult.Failed("The calendar proposal could not be persisted for approval")
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
            deletePayload(proposal.id)
            return ProposalResult.Failed("The exact calendar step could not be paused for approval")
        }
        return ProposalResult.ApprovalPending(pending)
    }

    /** Claims and invokes one approved calendar insert exactly once. */
    suspend fun resume(approvalId: String): ProposalResult? = withContext(Dispatchers.IO) {
        val pending = durableTaskManager.continuationForApproval(approvalId) ?: return@withContext null
        if (pending.invocation != null || pending.projectFileWrite != null || pending.calendarCreate == null) {
            return@withContext null
        }
        val continuation = durableTaskManager.claimApprovedContinuation(approvalId) ?: return@withContext null
        val calendar = continuation.calendarCreate ?: return@withContext null
        val proposal = proposals[calendar.proposalId]
        if (proposal == null || !matchesContinuation(proposal, continuation, calendar)) {
            return@withContext failClaimed(continuation, proposal, "The approved calendar proposal no longer matches its operation")
        }
        if (ownedRunningTask(proposal) == null || !durableTaskManager.isClaimedCalendarContinuation(
                continuationId = continuation.id,
                taskId = proposal.taskId,
                missionId = proposal.missionId,
                projectId = proposal.projectId,
                runId = proposal.runId,
                stepId = proposal.stepId,
                proposalId = proposal.id,
                titleHash = proposal.titleHash,
                scheduleHash = proposal.scheduleHash,
                calendarPolicy = CALENDAR_POLICY,
                idempotencyKey = calendar.idempotencyKey
            )) {
            return@withContext failClaimed(continuation, proposal, "The active task no longer owns this approved calendar step")
        }
        val payload = readPayload(proposal.id)
            ?: return@withContext failClaimed(continuation, proposal, "The private calendar proposal is unavailable")
        if (!payloadMatches(proposal, payload)) {
            return@withContext failClaimed(continuation, proposal, "The private calendar proposal failed its integrity check")
        }
        val claimed = proposal.copy(
            status = ProposalStatus.CLAIMED,
            updatedAtMs = System.currentTimeMillis(),
            outcome = "Approved calendar creation claimed for one provider insert"
        )
        proposals[proposal.id] = claimed
        if (!persist()) {
            return@withContext failClaimed(continuation, claimed, "The claimed calendar state could not be persisted")
        }

        when (val write = writer.create(payload.title, payload.startMs, payload.durationMinutes)) {
            is CalendarWriteResult.Created -> {
                val evidence = createEvidence(claimed)
                    ?: return@withContext failClaimed(
                        continuation,
                        claimed,
                        "The calendar event was created but local evidence could not be recorded; no automatic replay was attempted"
                    )
                if (!durableTaskManager.linkArtifact(
                        taskId = claimed.taskId,
                        artifactId = evidence.id,
                        runId = claimed.runId,
                        stepId = claimed.stepId
                    )) {
                    artifactManager.deleteArtifact(evidence.id)
                    return@withContext failClaimed(
                        continuation,
                        claimed,
                        "The calendar event was created but evidence could not be linked; no automatic replay was attempted"
                    )
                }
                val applied = claimed.copy(
                    status = ProposalStatus.APPLIED,
                    updatedAtMs = System.currentTimeMillis(),
                    outcome = "Approved calendar event created"
                )
                proposals[applied.id] = applied
                persist()
                deletePayload(applied.id)
                durableTaskManager.finishApprovalContinuation(
                    continuationId = continuation.id,
                    outcome = "Approved calendar event created",
                    succeeded = true
                )
                durableTaskManager.markCompleted(applied.taskId, "Approved calendar event created")
                ProposalResult.Applied(applied, evidence.id)
            }
            is CalendarWriteResult.Failed -> {
                failClaimed(continuation, claimed, write.reason)
            }
        }
    }

    /** Restores only pre-approved typed calendar records; claimed records never replay. */
    suspend fun resumeApprovedAfterRecovery(): List<ProposalResult> =
        durableTaskManager.approvedCalendarCreateApprovalIds().mapNotNull { resume(it) }

    /** Resolves pending approvals that elapsed while no Trust Center action was open. */
    fun reconcileExpiredApprovals(nowMs: Long = System.currentTimeMillis()): Int {
        val expiredIds = proposals.values
            .filter { proposal -> proposal.status == ProposalStatus.PENDING_APPROVAL }
            .mapNotNull { proposal ->
                val approval = proposal.approvalId?.let { durableTaskManager.findApproval(it)?.second }
                if (approval != null && approval.status == TaskApprovalStatus.PENDING && approval.expiresAtMs <= nowMs) {
                    approval.id
                } else {
                    null
                }
            }
        expiredIds.forEach { approvalId ->
            durableTaskManager.decideApproval(
                approvalId = approvalId,
                status = TaskApprovalStatus.DENIED,
                reason = "Calendar approval expired"
            )
            reconcileApproval(approvalId)
        }
        return expiredIds.size
    }

    /** Reconciles denial/expiry and removes private event content immediately. */
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
        deletePayload(proposal.id)
        persist()
        durableTaskManager.markStepFailed(proposal.taskId, proposal.stepId, "Calendar approval ${terminal.name.lowercase()}")
        durableTaskManager.markFailed(proposal.taskId, "Calendar approval ${terminal.name.lowercase()}")
        return true
    }

    fun cancelProposal(proposalId: String): Boolean {
        val proposal = proposals[proposalId] ?: return false
        if (proposal.status != ProposalStatus.DRAFT) return false
        proposals[proposalId] = proposal.copy(
            status = ProposalStatus.CANCELLED,
            updatedAtMs = System.currentTimeMillis(),
            outcome = "Calendar proposal cancelled"
        )
        deletePayload(proposalId)
        return persist()
    }

    private fun ownedRunningTask(execution: AgentLoopExecutionContext): DurableTask? {
        if (!execution.isStructurallyValid() || execution.projectId.isNullOrBlank()) return null
        val task = durableTaskManager.getTask(execution.taskId) ?: return null
        return task.takeIf {
            it.missionId == execution.missionId &&
                it.projectId == execution.projectId &&
                it.status == DurableTaskStatus.RUNNING &&
                it.currentRunId == execution.runId &&
                it.currentStepId == execution.stepId &&
                it.runs.any { run ->
                    run.id == execution.runId &&
                        run.taskId == execution.taskId &&
                        run.missionId == execution.missionId &&
                        run.projectId == execution.projectId
                } &&
                it.plan.any { step ->
                    step.id == execution.stepId &&
                        step.runId == execution.runId &&
                        step.status == TaskStepStatus.RUNNING
                }
        }
    }

    private fun ownedRunningTask(proposal: Proposal): DurableTask? = ownedRunningTask(
        AgentLoopExecutionContext(
            taskId = proposal.taskId,
            missionId = proposal.missionId,
            projectId = proposal.projectId,
            runId = proposal.runId,
            stepId = proposal.stepId,
            agentId = AgentLoopExecutionContext.AGENT_LOOP_PRINCIPAL,
            sourceSessionId = "recovered"
        )
    )

    private fun matchesContinuation(
        proposal: Proposal,
        continuation: ApprovalContinuation,
        calendar: ResumableCalendarCreate
    ): Boolean =
        continuation.taskId == proposal.taskId &&
            continuation.missionId == proposal.missionId &&
            continuation.projectId == proposal.projectId &&
            continuation.runId == proposal.runId &&
            continuation.stepId == proposal.stepId &&
            calendar.proposalId == proposal.id &&
            calendar.titleHash == proposal.titleHash &&
            calendar.scheduleHash == proposal.scheduleHash &&
            calendar.calendarPolicy == CALENDAR_POLICY &&
            calendar.idempotencyKey == "calendar-${proposal.id}"

    private suspend fun createEvidence(proposal: Proposal): ArtifactManager.Artifact? = runCatching {
        artifactManager.createArtifact(
            sessionId = proposal.projectId,
            name = "calendar-event-evidence",
            type = ArtifactManager.ArtifactType.TEXT,
            content = "Approved calendar event creation completed.",
            description = "Bounded evidence for an approved calendar operation",
            agentId = AgentLoopExecutionContext.AGENT_LOOP_PRINCIPAL,
            provenance = ArtifactProvenance(
                projectId = proposal.projectId,
                taskId = proposal.taskId,
                runId = proposal.runId,
                stepId = proposal.stepId,
                toolId = CALENDAR_CREATE,
                summary = "Approved calendar operation completed"
            )
        )
    }.getOrNull()

    private fun failClaimed(
        continuation: ApprovalContinuation,
        proposal: Proposal?,
        reason: String
    ): ProposalResult.Failed {
        proposal?.let {
            proposals[it.id] = it.copy(
                status = ProposalStatus.FAILED,
                updatedAtMs = System.currentTimeMillis(),
                outcome = reason.take(MAX_OUTCOME_CHARS)
            )
            deletePayload(it.id)
            persist()
        }
        durableTaskManager.finishApprovalContinuation(
            continuationId = continuation.id,
            outcome = reason,
            succeeded = false
        )
        durableTaskManager.markStepFailed(continuation.taskId, continuation.stepId, reason)
        durableTaskManager.markFailed(continuation.taskId, reason)
        return ProposalResult.Failed(reason)
    }

    private fun payloadMatches(proposal: Proposal, payload: PrivatePayload): Boolean =
        proposal.titleHash == sha256(payload.title) &&
            proposal.scheduleHash == sha256("${payload.startMs}:${payload.durationMinutes}") &&
            payload.title.isNotBlank() &&
            payload.title.length <= MAX_TITLE_CHARS &&
            !looksSensitive(payload.title) &&
            payload.durationMinutes in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES

    private fun parseInstant(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun writePayload(proposalId: String, payload: PrivatePayload): Boolean = runCatching {
        payloadDirectory.mkdirs()
        val target = File(payloadDirectory, "$proposalId.json")
        val temp = File(payloadDirectory, "$proposalId.tmp")
        temp.writeText(gson.toJson(payload), StandardCharsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) error("Unable to atomically save calendar proposal")
        }
    }.isSuccess

    private fun readPayload(proposalId: String): PrivatePayload? = runCatching {
        val file = File(payloadDirectory, "$proposalId.json")
        if (!file.exists()) null else gson.fromJson(file.readText(StandardCharsets.UTF_8), PrivatePayload::class.java)
    }.getOrNull()

    private fun deletePayload(proposalId: String) {
        File(payloadDirectory, "$proposalId.json").delete()
        File(payloadDirectory, "$proposalId.tmp").delete()
    }

    private fun restore() {
        runCatching {
            if (!indexFile.exists()) return
            val type = object : TypeToken<List<Proposal>>() {}.type
            val restored: List<Proposal> = gson.fromJson(indexFile.readText(StandardCharsets.UTF_8), type) ?: emptyList()
            restored.filter { it.id.matches(SAFE_IDENTIFIER) }.forEach { proposal -> proposals[proposal.id] = proposal }
            persist()
        }
    }

    private fun persist(): Boolean = runCatching {
        indexFile.parentFile?.mkdirs()
        val temp = File(indexFile.parentFile, "${indexFile.name}.tmp")
        temp.writeText(gson.toJson(proposals.values.sortedBy { it.createdAtMs }), StandardCharsets.UTF_8)
        if (!temp.renameTo(indexFile)) {
            indexFile.delete()
            if (!temp.renameTo(indexFile)) error("Unable to atomically save calendar proposal index")
        }
    }.isSuccess

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun looksSensitive(value: String): Boolean = SENSITIVE_VALUE.containsMatchIn(value)

    companion object {
        const val CALENDAR_CREATE = "calendar_create"
        const val CALENDAR_POLICY = "PRIMARY_OR_FIRST"
        private const val DEFAULT_DURATION_MINUTES = 60
        private const val MIN_DURATION_MINUTES = 5
        private const val MAX_DURATION_MINUTES = 1_440
        private const val MAX_TITLE_CHARS = 160
        private const val MAX_OUTCOME_CHARS = 240
        private val SAFE_IDENTIFIER = Regex("^[A-Za-z0-9._-]{1,128}$")
        private val SENSITIVE_VALUE = Regex(
            "(?i)(api[_ -]?key|authorization|bearer\\s+[a-z0-9._-]{8,}|password|secret|token\\s*[:=])"
        )
    }
}

sealed class CalendarWriteResult {
    data class Created(val providerEventId: Long) : CalendarWriteResult()
    data class Failed(val reason: String) : CalendarWriteResult()
}

fun interface CalendarEventWriter {
    suspend fun create(title: String, startMs: Long, durationMinutes: Int): CalendarWriteResult
}

private class AndroidCalendarEventWriter(context: Context) : CalendarEventWriter {
    private val calendarTool = CalendarTool(context.applicationContext)

    override suspend fun create(title: String, startMs: Long, durationMinutes: Int): CalendarWriteResult {
        val eventId = calendarTool.createEvent(
            title = title,
            startMs = startMs,
            durationMs = durationMinutes * 60_000L
        )
        return if (eventId > 0L) CalendarWriteResult.Created(eventId) else {
            CalendarWriteResult.Failed("Calendar provider did not confirm event creation")
        }
    }
}
