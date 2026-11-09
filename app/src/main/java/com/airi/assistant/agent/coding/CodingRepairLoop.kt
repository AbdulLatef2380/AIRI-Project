package com.airi.assistant.agent.coding

/** Runner abstraction keeps the repair loop deterministic and independently testable. */
fun interface CodingTaskRunner {
    suspend fun execute(task: CodingTask): CodingAgentExecutionReport
}

data class CodingRepairRequest(
    val failedTask: CodingTask,
    val failedReport: CodingAgentExecutionReport,
    val attempt: Int,
) {
    init {
        require(attempt > 0)
    }
}

fun interface CodingRepairPlanner {
    suspend fun replan(request: CodingRepairRequest): CodingTask?
}

enum class CodingRepairStatus { SUCCEEDED, FAILED, EXHAUSTED, BLOCKED }

data class CodingRepairAttempt(
    val attempt: Int,
    val taskId: String,
    val failedStepId: String?,
    val failureReason: String?,
    val repairTaskId: String?,
    val diffEvidence: String,
    val verificationEvidence: String,
)

data class CodingRepairReport(
    val status: CodingRepairStatus,
    val attempts: List<CodingRepairAttempt>,
    val finalTaskId: String,
    val finalExecution: CodingAgentExecutionReport,
    val terminalReason: String? = null,
) {
    /** Bounded evidence suitable for trace/model context; raw tool payloads never enter it. */
    fun modelContext(maxChars: Int = 4_000): String {
        require(maxChars > 0)
        return buildString {
            append("repair_status=").append(status.name).append('\n')
            append("final_task=").append(finalTaskId).append('\n')
            terminalReason?.let { append("terminal_reason=").append(it.take(300)).append('\n') }
            attempts.forEach { attempt ->
                append("attempt=").append(attempt.attempt)
                    .append(" task=").append(attempt.taskId)
                attempt.failedStepId?.let { append(" failed_step=").append(it) }
                attempt.failureReason?.let { append(" failure=").append(it.take(300)) }
                if (attempt.diffEvidence.isNotBlank()) {
                    append(" diff=").append(attempt.diffEvidence.take(1_000))
                }
                if (attempt.verificationEvidence.isNotBlank()) {
                    append(" verification=").append(attempt.verificationEvidence.take(1_000))
                }
                append('\n')
            }
        }.take(maxChars)
    }
}

/**
 * Runs a coding task, then performs bounded replanning only after a test/build
 * failure. A repair plan must contain both an apply_patch and a final verification
 * action; otherwise it is rejected before execution.
 */
class CodingRepairLoop(
    private val runner: CodingTaskRunner,
    private val planner: CodingRepairPlanner,
    private val maxAttempts: Int = 3,
) {
    init {
        require(maxAttempts > 0)
    }

    suspend fun execute(initialTask: CodingTask): CodingRepairReport {
        var currentTask = initialTask
        val attempts = mutableListOf<CodingRepairAttempt>()
        val seenTaskFingerprints = mutableSetOf<String>()

        repeat(maxAttempts + 1) { runIndex ->
            val fingerprint = fingerprint(currentTask)
            if (!seenTaskFingerprints.add(fingerprint)) {
                val blocked = blockedReport(
                    currentTask,
                    attempts,
                    "duplicate_repair_plan",
                )
                return blocked
            }

            val execution = runner.execute(currentTask)
            val verification = verificationEvidence(currentTask, execution)
            if (execution.status == CodingAgentStatus.SUCCEEDED) {
                if (verification.isBlank()) {
                    return blockedReport(currentTask, attempts, "verification_required")
                }
                if (runIndex > 0) {
                    attempts += CodingRepairAttempt(
                        attempt = runIndex,
                        taskId = currentTask.taskId,
                        failedStepId = null,
                        failureReason = null,
                        repairTaskId = currentTask.taskId,
                        diffEvidence = diffEvidence(execution),
                        verificationEvidence = verification,
                    )
                }
                return CodingRepairReport(
                    status = CodingRepairStatus.SUCCEEDED,
                    attempts = attempts.toList(),
                    finalTaskId = currentTask.taskId,
                    finalExecution = execution,
                )
            }

            val failed = execution.steps.lastOrNull { it.status != "SUCCEEDED" }
            val failureReason = failed?.evidence?.take(1_000)
                ?: execution.status.name.lowercase()
            val isRepairableFailure = failed?.toolId in VERIFICATION_TOOLS
            if (!isRepairableFailure) {
                return CodingRepairReport(
                    status = CodingRepairStatus.FAILED,
                    attempts = attempts + CodingRepairAttempt(
                        attempt = runIndex,
                        taskId = currentTask.taskId,
                        failedStepId = failed?.stepId,
                        failureReason = failureReason,
                        repairTaskId = null,
                        diffEvidence = "",
                        verificationEvidence = verification,
                    ),
                    finalTaskId = currentTask.taskId,
                    finalExecution = execution,
                    terminalReason = "failure_not_repairable",
                )
            }

            if (runIndex >= maxAttempts) {
                return CodingRepairReport(
                    status = CodingRepairStatus.EXHAUSTED,
                    attempts = attempts + CodingRepairAttempt(
                        attempt = runIndex,
                        taskId = currentTask.taskId,
                        failedStepId = failed?.stepId,
                        failureReason = failureReason,
                        repairTaskId = null,
                        diffEvidence = "",
                        verificationEvidence = verification,
                    ),
                    finalTaskId = currentTask.taskId,
                    finalExecution = execution,
                    terminalReason = "max_repair_attempts_reached",
                )
            }

            val request = CodingRepairRequest(currentTask, execution, runIndex + 1)
            val repair = planner.replan(request)
                ?: return blockedReport(currentTask, attempts, "planner_returned_no_repair")
            if (!containsPatchAndVerification(repair)) {
                return blockedReport(currentTask, attempts, "repair_plan_missing_patch_or_verification")
            }

            attempts += CodingRepairAttempt(
                attempt = runIndex,
                taskId = currentTask.taskId,
                failedStepId = failed?.stepId,
                failureReason = failureReason,
                repairTaskId = repair.taskId,
                diffEvidence = diffEvidence(execution),
                verificationEvidence = verification,
            )
            currentTask = repair
        }

        error("repeat must return from every iteration")
    }

    private fun containsPatchAndVerification(task: CodingTask): Boolean =
        task.actions.any { it.toolId == "apply_patch" } && task.actions.any { it.toolId in VERIFICATION_TOOLS }

    private fun verificationEvidence(
        task: CodingTask,
        execution: CodingAgentExecutionReport,
    ): String = execution.steps
        .filter { it.toolId in VERIFICATION_TOOLS }
        .joinToString("\n") { step ->
            "${step.toolId}:${step.status}:${step.evidence.take(1_000)}"
        }
        .takeIf { task.actions.any { it.toolId in VERIFICATION_TOOLS } }
        .orEmpty()

    private fun diffEvidence(execution: CodingAgentExecutionReport): String = execution.steps
        .filter { it.toolId == "apply_patch" && it.status == "SUCCEEDED" }
        .joinToString("\n") { it.evidence.take(1_000) }

    private fun blockedReport(
        task: CodingTask,
        attempts: List<CodingRepairAttempt>,
        reason: String,
    ) = CodingRepairReport(
        status = CodingRepairStatus.BLOCKED,
        attempts = attempts,
        finalTaskId = task.taskId,
        finalExecution = CodingAgentExecutionReport(
            taskId = task.taskId,
            status = CodingAgentStatus.BLOCKED,
            steps = emptyList(),
            remainingStepIds = task.actions.map { it.stepId },
        ),
        terminalReason = reason,
    )

    private fun fingerprint(task: CodingTask): String = task.actions.joinToString("|") {
        "${it.stepId}:${it.toolId}:${it.arguments.toSortedMap()}:${it.approvalToken.orEmpty()}"
    }

    private companion object {
        val VERIFICATION_TOOLS = setOf("run_tests", "build")
    }
}
