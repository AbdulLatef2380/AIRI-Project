package com.airi.core.chain

class TaskChainer {

    private val tasks = mutableListOf<() -> Unit>()

    fun add(task: () -> Unit) {
        tasks.add(task)
    }

    fun execute() {
        tasks.forEach { it.invoke() }
    }
}
