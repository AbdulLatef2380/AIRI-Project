package com.airi.assistant.agent.task

class TaskChain(
    val steps: MutableList<TaskStep>
) {

    var currentIndex = 0
        private set

    fun next(): TaskStep? {
        if (currentIndex >= steps.size) return null
        return steps[currentIndex++]
    }

    fun reset() {
        currentIndex = 0
    }

    fun hasMore(): Boolean {
        return currentIndex < steps.size
    }
}
