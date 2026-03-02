package com.airi.assistant.agent.command

object CommandRouter {

    suspend fun execute(step: String): CommandResult {

        return when (step) {

            "LAUNCH_APP" ->
                AccessibilityCommandBridge.launchApp()

            "GO_BACK" ->
                AccessibilityCommandBridge.performBack()

            "TYPE_QUERY" ->
                AccessibilityCommandBridge.typeText("Sample Query")

            else ->
                CommandResult(false, "Unknown step")
        }
    }
}
