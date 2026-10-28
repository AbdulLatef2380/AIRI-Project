package com.airi.assistant.ui.text

/**
 * Stateful identity reconciliation for one assistant message.
 *
 * [AdaptiveContentPolicy] remains a pure one-shot planner. Compose streaming
 * uses one session per message so units that survive reparsing keep their IDs
 * even when a new block is inserted before them.
 */
class AdaptiveContentSession(private val messageId: String) {
    private var previousUnits: List<AssistantContentUnit> = emptyList()
    private var nextUnitSequence: Long = 0

    fun plan(
        text: String,
        isStreaming: Boolean = false,
        toolOutputs: List<AssistantToolOutput> = emptyList(),
    ): AdaptiveContentPlan {
        val freshPlan = AdaptiveContentPolicy.plan(
            text = text,
            isStreaming = isStreaming,
            messageId = messageId,
            toolOutputs = toolOutputs,
        )
        val units = reconcile(previousUnits, freshPlan.units)
        previousUnits = units
        return freshPlan.copy(units = units)
    }

    private fun reconcile(
        previous: List<AssistantContentUnit>,
        current: List<AssistantContentUnit>,
    ): List<AssistantContentUnit> {
        if (current.isEmpty()) return emptyList()
        if (previous.isEmpty()) return current.map(::assignSessionId)

        val reusedPrevious = BooleanArray(previous.size)
        val reusedCurrent = BooleanArray(current.size)
        val previousIndexForCurrent = IntArray(current.size) { -1 }

        // Longest-common-subsequence matching preserves order while preferring
        // later current duplicates when new identical blocks were prepended.
        val lengths = Array(previous.size + 1) { IntArray(current.size + 1) }
        for (oldIndex in previous.indices.reversed()) {
            for (newIndex in current.indices.reversed()) {
                lengths[oldIndex][newIndex] = if (sameUnit(previous[oldIndex], current[newIndex])) {
                    1 + lengths[oldIndex + 1][newIndex + 1]
                } else {
                    maxOf(lengths[oldIndex + 1][newIndex], lengths[oldIndex][newIndex + 1])
                }
            }
        }

        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < previous.size && newIndex < current.size) {
            val canMatchHere = sameUnit(previous[oldIndex], current[newIndex]) &&
                lengths[oldIndex][newIndex] == 1 + lengths[oldIndex + 1][newIndex + 1]
            val canDeferToLaterDuplicate = canMatchHere &&
                lengths[oldIndex][newIndex + 1] == lengths[oldIndex][newIndex]
            if (canMatchHere && !canDeferToLaterDuplicate) {
                reusedPrevious[oldIndex] = true
                reusedCurrent[newIndex] = true
                previousIndexForCurrent[newIndex] = oldIndex
                oldIndex++
                newIndex++
            } else if (lengths[oldIndex][newIndex + 1] >= lengths[oldIndex + 1][newIndex]) {
                // Tie-break toward skipping newly inserted leading units.
                newIndex++
            } else {
                oldIndex++
            }
        }

        // A streamed unit may grow or be shortened between snapshots. Reuse
        // its ID only for same-kind prefix-related content; unrelated blocks
        // never inherit an identity merely because their ordinal is unchanged.
        previous.indices
            .filterNot { reusedPrevious[it] }
            .sortedByDescending { previous[it].source.length }
            .forEach { candidateOld ->
                val oldUnit = previous[candidateOld]
                val candidateNew = current.indices
                    .filterNot { reusedCurrent[it] }
                    .filter { current[it].kind == oldUnit.kind }
                    .filter { index -> prefixRelated(oldUnit.source, current[index].source) }
                    .maxByOrNull { index -> continuityScore(oldUnit.source, current[index].source) }
                    ?: return@forEach
                reusedPrevious[candidateOld] = true
                reusedCurrent[candidateNew] = true
                previousIndexForCurrent[candidateNew] = candidateOld
            }

        return current.mapIndexed { index, unit ->
            val old = previousIndexForCurrent[index]
            if (old >= 0) unit.copy(id = previous[old].id) else assignSessionId(unit)
        }
    }

    private fun assignSessionId(unit: AssistantContentUnit): AssistantContentUnit =
        unit.copy(id = "$messageId-${unit.kind.name.lowercase()}-session-${nextUnitSequence++}")

    private fun sameUnit(left: AssistantContentUnit, right: AssistantContentUnit): Boolean =
        left.kind == right.kind && left.source == right.source

    private fun prefixRelated(left: String, right: String): Boolean =
        left.isNotEmpty() && right.isNotEmpty() && (
            left.startsWith(right) ||
                right.startsWith(left) ||
                (left.length >= 4 && right.contains(left)) ||
                (right.length >= 4 && left.contains(right))
            )

    private fun continuityScore(left: String, right: String): Int =
        if (left.contains(right) || right.contains(left)) {
            minOf(left.length, right.length) * 2
        } else {
            commonPrefixLength(left, right)
        }

    private fun commonPrefixLength(left: String, right: String): Int {
        val limit = minOf(left.length, right.length)
        var index = 0
        while (index < limit && left[index] == right[index]) index++
        return index
    }
}
