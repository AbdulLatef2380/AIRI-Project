package com.airi.assistant.agent.planning

/** A transport-neutral representation of a planned node and its prerequisites. */
data class PlanNodeReference(
    val id: String,
    val dependsOn: List<String> = emptyList(),
)

enum class PlanValidationError {
    EMPTY_PLAN,
    STEP_LIMIT_EXCEEDED,
    BLANK_STEP_ID,
    DUPLICATE_STEP_ID,
    BLANK_DEPENDENCY_ID,
    SELF_DEPENDENCY,
    MISSING_DEPENDENCY,
    CYCLIC_DEPENDENCY,
}

data class PlanValidationResult(
    val errors: Set<PlanValidationError>,
    /** Deterministic topological order; empty when validation fails. */
    val dependencyOrder: List<String> = emptyList(),
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Validates plan admission independently from JSON, Android, or UI layers.
 *
 * A valid plan has at least one uniquely identified step, bounded size, only
 * satisfiable dependencies, and no cycles. Among simultaneously ready nodes,
 * the original planner order is retained, making dependency scheduling stable.
 */
object PlanStructureValidator {
    const val DEFAULT_MAX_STEPS = 16

    fun validate(
        nodes: List<PlanNodeReference>,
        maxSteps: Int = DEFAULT_MAX_STEPS,
    ): PlanValidationResult {
        require(maxSteps > 0) { "maxSteps must be positive" }
        val errors = linkedSetOf<PlanValidationError>()
        if (nodes.isEmpty()) errors += PlanValidationError.EMPTY_PLAN
        if (nodes.size > maxSteps) errors += PlanValidationError.STEP_LIMIT_EXCEEDED

        val ids = nodes.map { it.id.trim() }
        if (ids.any { it.isEmpty() }) errors += PlanValidationError.BLANK_STEP_ID
        if (ids.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().any { it.value > 1 }) {
            errors += PlanValidationError.DUPLICATE_STEP_ID
        }

        val knownIds = ids.filter { it.isNotEmpty() }.toSet()
        nodes.forEachIndexed { index, node ->
            val id = ids[index]
            node.dependsOn.forEach { rawDependency ->
                val dependency = rawDependency.trim()
                when {
                    dependency.isEmpty() -> errors += PlanValidationError.BLANK_DEPENDENCY_ID
                    dependency == id -> errors += PlanValidationError.SELF_DEPENDENCY
                    dependency !in knownIds -> errors += PlanValidationError.MISSING_DEPENDENCY
                }
            }
        }
        if (errors.isNotEmpty()) return PlanValidationResult(errors)

        val order = deterministicTopologicalOrder(nodes, ids)
        if (order.size != nodes.size) {
            return PlanValidationResult(errors + PlanValidationError.CYCLIC_DEPENDENCY)
        }
        return PlanValidationResult(errors, order)
    }

    private fun deterministicTopologicalOrder(
        nodes: List<PlanNodeReference>,
        ids: List<String>,
    ): List<String> {
        val declaredIndex = ids.withIndex().associate { it.value to it.index }
        val remainingDependencies = nodes.associate { node ->
            node.id.trim() to node.dependsOn.map { it.trim() }.toMutableSet()
        }.toMutableMap()
        val order = mutableListOf<String>()

        while (remainingDependencies.isNotEmpty()) {
            val next = remainingDependencies
                .filterValues { dependencies -> dependencies.isEmpty() }
                .keys
                .minByOrNull { id -> declaredIndex.getValue(id) }
                ?: break
            order += next
            remainingDependencies.remove(next)
            remainingDependencies.values.forEach { dependencies -> dependencies.remove(next) }
        }
        return order
    }
}
