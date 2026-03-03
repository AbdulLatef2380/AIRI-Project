package com.airi.core.chain

class GoalBuilder(private val id: String) {

    private val steps = mutableListOf<PlanStep>()

    fun click(text: String) {
        steps.add(PlanStep.Click(text))
    }

    fun scroll(times: Int = 1) {
        repeat(times) {
            steps.add(PlanStep.ScrollForward())
        }
    }

    fun waitFor(text: String, timeout: Long = 2000) {
        steps.add(PlanStep.WaitFor(text, timeout))
    }

    fun build(description: String): AgentGoal {
        return AgentGoal(id, description, steps)
    }
}

fun goal(id: String, description: String, block: GoalBuilder.() -> Unit): AgentGoal {
    val builder = GoalBuilder(id)
    builder.block()
    return builder.build(description)
}
