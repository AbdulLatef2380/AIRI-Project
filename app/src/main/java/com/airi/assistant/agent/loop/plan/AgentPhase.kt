package com.airi.assistant.agent.loop.plan

/**
 * Explicit phases of a code task inside AgentLoop (report part 3, phase 2, item 1):
 * PLAN (requirements extracted and recorded) -> ACT (tools run) -> VERIFY (the gate checks the
 * evidence) -> FINAL (answer released). VERIFY may send the loop back to ACT for a correction
 * turn; that is the only backward move.
 *
 * Pure Kotlin so it is unit-testable on the JVM.
 */
enum class AgentPhase { PLAN, ACT, VERIFY, FINAL }

class AgentPhaseTracker {
    var current: AgentPhase = AgentPhase.PLAN
        private set

    private val log = mutableListOf(AgentPhase.PLAN)

    /** Every phase entered, in order, starting with PLAN. */
    val history: List<AgentPhase> get() = log.toList()

    /** Moves to [next] when the transition is legal; returns false (and stays) otherwise. */
    fun moveTo(next: AgentPhase): Boolean {
        if (next == current || !isLegal(current, next)) return false
        current = next
        log += next
        return true
    }

    private fun isLegal(from: AgentPhase, to: AgentPhase): Boolean = when (from) {
        AgentPhase.PLAN -> to == AgentPhase.ACT
        AgentPhase.ACT -> to == AgentPhase.VERIFY
        AgentPhase.VERIFY -> to == AgentPhase.ACT || to == AgentPhase.FINAL
        AgentPhase.FINAL -> false
    }
}
