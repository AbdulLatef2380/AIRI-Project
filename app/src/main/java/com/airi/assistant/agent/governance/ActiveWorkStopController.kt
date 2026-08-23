package com.airi.assistant.agent.governance

import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator
import com.airi.assistant.agent.scheduler.ScheduledJobOrchestrator
import com.airi.assistant.memory.repository.AuditRepository
import com.airi.assistant.terminal.TerminalRuntime

/**
 * Stops the user-owned work that AIRI can cancel through live runtime APIs.
 *
 * This is deliberately narrower than a generic "stop everything" claim:
 * connector calls already in flight and external browser/remote sessions do not
 * currently expose a process-wide cancellation contract, so they are not
 * reported as stopped here.
 */
class ActiveWorkStopController(
    private val productionOrchestrator: ProductionAgentOrchestrator,
    private val durableTaskManager: DurableTaskManager,
    private val scheduledJobOrchestrator: ScheduledJobOrchestrator,
    private val terminalRuntime: TerminalRuntime,
    private val auditRepository: AuditRepository
) {
    data class StopReport(
        val cancelledDurableTaskCount: Int,
        val cancelledScheduledJobCount: Int,
        val terminalCommandCancelled: Boolean
    )

    fun stopActiveUserWork(): StopReport {
        productionOrchestrator.cancelAll()

        val activeTaskIds = durableTaskManager.activeTasks().map { it.id }
        activeTaskIds.forEach(durableTaskManager::cancel)

        val cancelledScheduledJobCount = scheduledJobOrchestrator.cancelAllUserJobs()
        val terminalCommandCancelled = terminalRuntime.cancelActiveCommand()
        val report = StopReport(
            cancelledDurableTaskCount = activeTaskIds.size,
            cancelledScheduledJobCount = cancelledScheduledJobCount,
            terminalCommandCancelled = terminalCommandCancelled
        )

        auditRepository.info(
            tag = AUDIT_TAG,
            message = "User stop applied: durableTasks=${report.cancelledDurableTaskCount}, " +
                "scheduledJobs=${report.cancelledScheduledJobCount}, " +
                "terminalCancelled=${report.terminalCommandCancelled}"
        )
        return report
    }

    private companion object {
        const val AUDIT_TAG = "ACTIVE_WORK_STOP"
    }
}
