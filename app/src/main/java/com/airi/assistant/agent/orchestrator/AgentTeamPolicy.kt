package com.airi.assistant.agent.orchestrator

import com.airi.assistant.agent.subagent.SubAgentCapability

/**
 * Admission and isolation policy for a multi-agent [ProductionAgentOrchestrator]
 * plan. This is deliberately a policy layer: it does not route or execute agents.
 *
 * A team plan has one cloud budget owned by the parent task. The budget is split
 * before dispatch, so a role receives only its assigned ceiling in
 * [com.airi.assistant.agent.subagent.SubAgentContext.remainingCloudTokenBudget].
 * Dependency output is injected by the orchestrator after a dependency succeeds;
 * callers cannot preload it into a child context.
 */
object AgentTeamPolicy {
    const val MAX_TASKS_PER_PLAN = 12
    const val DEFAULT_MAX_PARALLEL_TASKS = 4

    data class Admission(
        val accepted: Boolean,
        val reason: String,
        val maxParallelTasks: Int = DEFAULT_MAX_PARALLEL_TASKS,
        val taskCloudBudgets: Map<String, Int> = emptyMap()
    )

    fun admit(
        plan: ProductionAgentOrchestrator.OrchestratorPlan,
        capabilities: List<SubAgentCapability>
    ): Admission {
        if (plan.tasks.isEmpty()) return rejected("A team plan must contain at least one task")
        if (plan.tasks.size > MAX_TASKS_PER_PLAN) {
            return rejected("Team plan exceeds $MAX_TASKS_PER_PLAN tasks")
        }
        if (plan.maxParallelTasks !in 1..DEFAULT_MAX_PARALLEL_TASKS) {
            return rejected("maxParallelTasks must be between 1 and $DEFAULT_MAX_PARALLEL_TASKS")
        }

        val taskIds = plan.tasks.map { it.id }
        if (taskIds.distinct().size != taskIds.size) return rejected("Team task IDs must be unique")
        val taskIdSet = taskIds.toSet()
        plan.tasks.forEach { task ->
            if (task.dependencies.any { it !in taskIdSet || it == task.id }) {
                return rejected("Task ${task.id} has an unknown or self dependency")
            }
            if (plan.isolateTaskContext && task.context.dependencyResults.isNotEmpty()) {
                return rejected("Task ${task.id} cannot preload dependency results into an isolated team")
            }
            if (plan.projectId != null && task.context.projectId != null && task.context.projectId != plan.projectId) {
                return rejected("Task ${task.id} belongs to a different project")
            }
        }
        if (hasDependencyCycle(plan.tasks)) {
            return rejected("Team plan contains a dependency cycle")
        }

        val availableBudget = plan.teamCloudTokenBudget
            ?: plan.tasks.minOf { it.context.remainingCloudTokenBudget }
        if (availableBudget < 0) return rejected("Team cloud budget cannot be negative")

        val capabilityById = capabilities.associateBy { it.agentId }
        val reserves = plan.tasks.associate { task ->
            val capability = task.agentId?.let(capabilityById::get)
            val reserve = when {
                capability == null -> 0
                !capability.requiresCloud -> 0
                else -> capability.costTier.estimatedTokensPerCall.first
            }
            task.id to reserve
        }
        val requiredBudget = reserves.values.sum()
        if (requiredBudget > availableBudget) {
            return rejected("Team minimum cloud reserve $requiredBudget exceeds available budget $availableBudget")
        }

        val cloudTaskIds = reserves.filterValues { it > 0 }.keys
        val remainingBudget = availableBudget - requiredBudget
        val discretionaryShare = if (cloudTaskIds.isEmpty()) 0 else remainingBudget / cloudTaskIds.size
        val allocations = plan.tasks.associate { task ->
            val reserve = reserves.getValue(task.id)
            val cap = if (reserve == 0) 0 else reserve + discretionaryShare
            task.id to minOf(cap, task.context.remainingCloudTokenBudget)
        }

        return Admission(
            accepted = true,
            reason = "Team admitted: ${plan.tasks.size} role(s), cloud reserve=$requiredBudget/$availableBudget",
            maxParallelTasks = plan.maxParallelTasks,
            taskCloudBudgets = allocations
        )
    }

    private fun hasDependencyCycle(tasks: List<ProductionAgentOrchestrator.OrchestratorTask>): Boolean {
        val dependenciesByTask = tasks.associate { it.id to it.dependencies }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(taskId: String): Boolean {
            if (taskId in visited) return false
            if (!visiting.add(taskId)) return true
            val hasCycle = dependenciesByTask.getValue(taskId).any(::visit)
            visiting.remove(taskId)
            if (!hasCycle) visited.add(taskId)
            return hasCycle
        }

        return dependenciesByTask.keys.any(::visit)
    }

    private fun rejected(reason: String) = Admission(accepted = false, reason = reason)
}
