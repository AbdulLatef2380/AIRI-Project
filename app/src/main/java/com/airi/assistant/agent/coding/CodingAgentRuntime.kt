package com.airi.assistant.agent.coding

import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.domain.tool.ToolExecutionPipeline
import kotlinx.coroutines.CancellationException

/** Builds a bounded coding task from a user request and its owned execution context. */
fun interface CodingTaskPlanner {
    suspend fun plan(input: String, context: SubAgentContext): CodingTask?
}

/** Production-facing result of a coding execution, including bounded model evidence. */
data class CodingRuntimeResult(
    val report: CodingRepairReport,
    val evidence: String,
)

/**
 * The single production entry point for CodingAgent code execution.
 *
 * Planning is deliberately injected: the model may propose a task, but execution
 * always enters [ToolExecutionPipeline] through [CodingAgentOrchestrator]. No
 * direct filesystem or ProcessBuilder access is available from this class.
 */
class CodingAgentRuntime(
    private val planner: CodingTaskPlanner,
    pipeline: ToolExecutionPipeline,
    private val repairPlanner: CodingRepairPlanner? = null,
    private val maxRepairAttempts: Int = 3,
    private val maxEvidenceChars: Int = 4_000,
) {
    private val orchestrator = CodingAgentOrchestrator(ToolPipelineCodingExecutor(pipeline))

    init {
        require(maxEvidenceChars > 0)
        require(maxRepairAttempts > 0)
    }

    suspend fun execute(input: String, context: SubAgentContext): CodingRuntimeResult {
        val initial = planner.plan(input, context)
            ?: return CodingRuntimeResult(
                report = CodingRepairReport(
                    status = CodingRepairStatus.BLOCKED,
                    attempts = emptyList(),
                    finalTaskId = context.parentTaskId.ifBlank { "coding-unplanned" },
                    finalExecution = CodingAgentExecutionReport(
                        taskId = context.parentTaskId.ifBlank { "coding-unplanned" },
                        status = CodingAgentStatus.BLOCKED,
                        steps = emptyList(),
                        remainingStepIds = emptyList(),
                    ),
                    terminalReason = "planner_returned_no_task",
                ),
                evidence = "coding_status=BLOCKED reason=planner_returned_no_task",
            )
        val report = try {
            CodingRepairLoop(
                runner = orchestrator,
                planner = repairPlanner ?: CodingRepairPlanner { request ->
                    planner.plan(
                        input = input,
                        context = context.copy(
                            parentTaskId = request.failedTask.taskId,
                            executionId = request.failedTask.taskId,
                        ),
                    )
                },
                maxAttempts = maxRepairAttempts,
            ).execute(initial)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return CodingRuntimeResult(
                report = CodingRepairReport(
                    status = CodingRepairStatus.FAILED,
                    attempts = emptyList(),
                    finalTaskId = initial.taskId,
                    finalExecution = CodingAgentExecutionReport(
                        taskId = initial.taskId,
                        status = CodingAgentStatus.FAILED,
                        steps = listOf(CodingStepEvidence("runtime", "coding_runtime", "FAILED", "runtime_failure")),
                        remainingStepIds = initial.actions.map { it.stepId },
                    ),
                    terminalReason = "runtime_failure:${error::class.simpleName ?: "unknown"}",
                ),
                evidence = "coding_status=FAILED reason=runtime_failure",
            )
        }
        if (report.finalExecution.status == CodingAgentStatus.CANCELLED) {
            throw CancellationException("Coding task was cancelled")
        }
        return CodingRuntimeResult(report, report.modelContext(maxEvidenceChars))
    }
}
