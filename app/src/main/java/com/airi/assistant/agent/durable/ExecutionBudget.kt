package com.airi.assistant.agent.durable

fun interface ExecutionClock { fun nowMs(): Long }
object SystemExecutionClock : ExecutionClock { override fun nowMs(): Long = System.currentTimeMillis() }

/** Independent, persisted limits for a task and one bounded execution run. */
data class ExecutionBudget(
    val decisionCycles: Int = 128,
    val toolOperations: Int = 256,
    val retries: Int = 8,
    val tokens: Long = 64_000L,
    val costMicros: Long = 0L,
    val taskDeadlineMs: Long = Long.MAX_VALUE,
    val runDeadlineMs: Long = Long.MAX_VALUE,
    val hardDecisionCycleCeiling: Int = 200,
) {
    init {
        require(decisionCycles >= 0 && toolOperations >= 0 && retries >= 0)
        require(tokens >= 0 && costMicros >= 0)
        require(hardDecisionCycleCeiling >= 1)
    }

    fun guard(usage: ExecutionUsage, nowMs: Long): BudgetPauseReason? = when {
        nowMs >= taskDeadlineMs -> BudgetPauseReason.TASK_DEADLINE_REACHED
        nowMs >= runDeadlineMs -> BudgetPauseReason.RUN_BUDGET_EXHAUSTED
        usage.decisionCycles >= hardDecisionCycleCeiling || usage.decisionCycles >= decisionCycles -> BudgetPauseReason.RUN_BUDGET_EXHAUSTED
        usage.toolOperations >= toolOperations -> BudgetPauseReason.RUN_BUDGET_EXHAUSTED
        usage.retries >= retries -> BudgetPauseReason.TOOL_RETRY_BUDGET_EXHAUSTED
        tokens > 0 && usage.tokens >= tokens -> BudgetPauseReason.TASK_TOKEN_BUDGET_EXHAUSTED
        costMicros > 0 && usage.costMicros >= costMicros -> BudgetPauseReason.TASK_COST_BUDGET_EXHAUSTED
        else -> null
    }

    fun consumeDecision(usage: ExecutionUsage) = usage.copy(decisionCycles = usage.decisionCycles + 1)
    fun consumeTool(usage: ExecutionUsage) = usage.copy(toolOperations = usage.toolOperations + 1)
    fun consumeRetry(usage: ExecutionUsage) = usage.copy(retries = usage.retries + 1)
    fun consumeTokens(usage: ExecutionUsage, count: Long) = usage.copy(tokens = usage.tokens + count.coerceAtLeast(0))
    fun consumeCost(usage: ExecutionUsage, micros: Long) = usage.copy(costMicros = usage.costMicros + micros.coerceAtLeast(0))
}

data class ExecutionUsage(
    val decisionCycles: Int = 0,
    val toolOperations: Int = 0,
    val retries: Int = 0,
    val tokens: Long = 0L,
    val costMicros: Long = 0L,
)

/** Run-owned accounting; callers deliberately do not reset this on retry/resume. */
class ExecutionBudgetTracker(
    val budget: ExecutionBudget,
    private val clock: ExecutionClock = SystemExecutionClock,
    initialUsage: ExecutionUsage = ExecutionUsage(),
) {
    @Volatile var usage: ExecutionUsage = initialUsage
        private set
    @Synchronized fun beforeDecision(): BudgetPauseReason? = before { usage = budget.consumeDecision(usage) }
    @Synchronized fun beforeTool(): BudgetPauseReason? = before { usage = budget.consumeTool(usage) }
    @Synchronized fun beforeRetry(): BudgetPauseReason? = before { usage = budget.consumeRetry(usage) }
    fun check(): BudgetPauseReason? = budget.guard(usage, clock.nowMs())
    private fun before(consume: () -> Unit): BudgetPauseReason? {
        val reason = budget.guard(usage, clock.nowMs())
        if (reason == null) consume()
        return reason
    }
}

/** Cooperative progress watchdog; external waits are progress, not a stall. */
class ProgressWatchdog(private val idleTimeoutMs: Long) {
    private var lastProgressMs: Long = Long.MIN_VALUE
    fun markProgress(nowMs: Long) { lastProgressMs = nowMs }
    fun isStalled(nowMs: Long, externalPending: Boolean = false): Boolean =
        !externalPending && lastProgressMs != Long.MIN_VALUE && nowMs - lastProgressMs >= idleTimeoutMs
}
