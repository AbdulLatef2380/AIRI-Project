package com.airi.assistant.ai.agent

data class Task(
    val originalInput: String,
    val steps: List<TaskStep>
)
