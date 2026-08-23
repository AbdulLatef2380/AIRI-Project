package com.airi.assistant.agent.durable

/**
 * Ownership boundary for the durable Mission aggregate.
 *
 * AIRI keeps the durable task as the persisted execution record, while
 * [missionId] is the stable aggregate identity that groups its plan, runs,
 * approvals, diagnostics, and owned output references. Legacy tasks receive
 * their own task ID as mission identity during normalization; this is
 * deterministic and does not require a lossy migration of existing JSON.
 */
object MissionKernel {
    fun normalize(task: DurableTask): DurableTask {
        val resolvedMissionId = task.missionId?.takeIf(String::isNotBlank) ?: task.id
        val normalizedRuns = task.runs.map { run ->
            run.copy(
                taskId = run.taskId.takeIf(String::isNotBlank) ?: task.id,
                missionId = run.missionId.takeIf(String::isNotBlank) ?: resolvedMissionId,
                projectId = run.projectId ?: task.projectId
            )
        }
        val normalizedSteps = task.plan.map { step ->
            val impliedRunId = step.runId ?: task.currentRunId
            if (impliedRunId == null) step else step.copy(runId = impliedRunId)
        }
        val normalizedApprovals = task.approvals.map { approval ->
            approval.copy(
                taskId = approval.taskId.takeIf(String::isNotBlank) ?: task.id,
                missionId = approval.missionId.takeIf(String::isNotBlank) ?: resolvedMissionId,
                projectId = approval.projectId ?: task.projectId,
                runId = approval.runId ?: task.currentRunId,
                stepId = approval.stepId ?: task.currentStepId
            )
        }
        val normalizedContinuations = task.approvalContinuations.map { continuation ->
            continuation.copy(
                taskId = continuation.taskId.takeIf(String::isNotBlank) ?: task.id,
                missionId = continuation.missionId.takeIf(String::isNotBlank) ?: resolvedMissionId,
                projectId = continuation.projectId ?: task.projectId
            )
        }
        return task.copy(
            missionId = resolvedMissionId,
            runs = normalizedRuns,
            plan = normalizedSteps,
            approvals = normalizedApprovals,
            approvalContinuations = normalizedContinuations,
            approvalIds = (task.approvalIds + normalizedApprovals.map { it.id }).distinct()
        )
    }

    fun validate(task: DurableTask): MissionOwnershipValidation {
        val missionId = task.missionId?.takeIf(String::isNotBlank)
            ?: return MissionOwnershipValidation.Invalid("Task has no mission identity")
        val runsById = task.runs.associateBy { it.id }

        task.runs.firstOrNull { run ->
            run.taskId != task.id || run.missionId != missionId || run.projectId != task.projectId
        }?.let { run ->
            return MissionOwnershipValidation.Invalid("Run ${run.id} does not belong to task/project/mission")
        }

        task.plan.firstOrNull { step ->
            val runId = step.runId
            runId != null && runsById[runId] == null
        }?.let { step ->
            return MissionOwnershipValidation.Invalid("Step ${step.id} references an unknown run")
        }

        task.approvals.firstOrNull { approval ->
            approval.taskId != task.id ||
                approval.missionId != missionId ||
                approval.projectId != task.projectId ||
                (approval.runId != null && runsById[approval.runId] == null)
        }?.let { approval ->
            return MissionOwnershipValidation.Invalid("Approval ${approval.id} does not belong to task/project/mission")
        }

        task.approvalContinuations.firstOrNull { continuation ->
            val approval = task.approvals.firstOrNull { it.id == continuation.approvalId }
            continuation.taskId != task.id ||
                continuation.missionId != missionId ||
                continuation.projectId != task.projectId ||
                runsById[continuation.runId] == null ||
                task.plan.none { it.id == continuation.stepId && it.runId == continuation.runId } ||
                approval == null ||
                approval.taskId != continuation.taskId ||
                approval.missionId != continuation.missionId ||
                approval.projectId != continuation.projectId ||
                approval.runId != continuation.runId ||
                approval.stepId != continuation.stepId ||
                !continuation.invocation.isSafeToPersist()
        }?.let { continuation ->
            return MissionOwnershipValidation.Invalid("Continuation ${continuation.id} is not bound to its approval/task/project/run/step")
        }

        return MissionOwnershipValidation.Valid
    }

    /** Denies a project-owned resource unless the active task explicitly owns that project. */
    fun canAccessProject(task: DurableTask, resourceProjectId: String?): Boolean =
        task.projectId != null && task.projectId == resourceProjectId

    /**
     * Pure connector execution boundary used before an adapter can request a
     * project-bound secret. It verifies all persisted coordinates but does not
     * imply approval for a side effect.
     */
    fun ownsConnectorExecution(
        task: DurableTask,
        missionId: String,
        projectId: String,
        runId: String,
        stepId: String
    ): Boolean {
        val normalized = normalize(task)
        return normalized.missionId == missionId && normalized.projectId == projectId &&
            normalized.runs.any { run ->
                run.id == runId && run.taskId == normalized.id &&
                    run.missionId == missionId && run.projectId == projectId
            } &&
            normalized.plan.any { step -> step.id == stepId && step.runId == runId }
    }
}

sealed class MissionOwnershipValidation {
    data object Valid : MissionOwnershipValidation()
    data class Invalid(val reason: String) : MissionOwnershipValidation()
}
