package com.airi.assistant.core

object SystemTools {

    fun execute(command: String): String {
        return when (command) {
            "back" -> "BACK_EXECUTED"
            else -> "UNKNOWN_COMMAND"
        }
    }
}
