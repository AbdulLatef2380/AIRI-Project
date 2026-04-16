package com.airi.assistant.agent.planning

sealed class PlanStep {
    abstract val id: String
    abstract val dependsOn: List<String>
    abstract val expectedOutcome: String?

    data class OpenApp(
        override val id: String,
        val appName: String,
        val packageName: String? = null,
        override val dependsOn: List<String> = emptyList(),
        override val expectedOutcome: String? = null
    ) : PlanStep()

    data class Search(
        override val id: String,
        val query: String,
        override val dependsOn: List<String> = emptyList(),
        override val expectedOutcome: String? = null
    ) : PlanStep()

    data class Click(
        override val id: String,
        val targetText: String,
        val targetId: String? = null,
        override val dependsOn: List<String> = emptyList(),
        override val expectedOutcome: String? = null
    ) : PlanStep()

    data class Type(
        override val id: String,
        val text: String,
        val targetField: String? = null,
        override val dependsOn: List<String> = emptyList(),
        override val expectedOutcome: String? = null
    ) : PlanStep()

    data class Navigate(
        override val id: String,
        val direction: NavigationDirection,
        override val dependsOn: List<String> = emptyList(),
        override val expectedOutcome: String? = null
    ) : PlanStep() {
        enum class NavigationDirection {
            BACK, HOME, RECENTS
        }
    }

    data class Wait(
        override val id: String,
        val durationMs: Long? = null,
        val condition: String? = null,
        override val dependsOn: List<String> = emptyList(),
        override val expectedOutcome: String? = null
    ) : PlanStep()

    data class Scroll(
        override val id: String,
        val direction: ScrollDirection,
        val amount: Int = 1,
        override val dependsOn: List<String> = emptyList(),
        override val expectedOutcome: String? = null
    ) : PlanStep() {
        enum class ScrollDirection {
            UP, DOWN, LEFT, RIGHT
        }
    }

    data class Custom(
        override val id: String,
        val action: String,
        val parameters: Map<String, String> = emptyMap(),
        override val dependsOn: List<String> = emptyList(),
        override val expectedOutcome: String? = null
    ) : PlanStep()
}
