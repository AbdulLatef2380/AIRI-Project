package com.airi.assistant.agent.execution.command

import android.util.Log
import com.airi.assistant.agent.planning.PlanStep

object CommandRouter {

    private const val TAG = "CommandRouter"

    suspend fun execute(step: PlanStep): CommandResult {
        Log.d(TAG, "Executing step: ${step.id} (${step::class.simpleName})")

        return when (step) {
            is PlanStep.OpenApp -> {
                if (step.appName.isEmpty()) {
                    CommandResult(false, "Missing app name")
                } else {
                    AccessibilityCommandBridge.launchApp(step.appName)
                }
            }

            is PlanStep.Search -> {
                if (step.query.isEmpty()) {
                    CommandResult(false, "Missing search query")
                } else {
                    AccessibilityCommandBridge.search(step.query)
                }
            }

            is PlanStep.Click -> {
                if (step.targetText.isEmpty()) {
                    CommandResult(false, "Missing click target")
                } else {
                    AccessibilityCommandBridge.click(step.targetText)
                }
            }

            is PlanStep.Type -> {
                if (step.text.isEmpty()) {
                    CommandResult(false, "Missing text to type")
                } else {
                    AccessibilityCommandBridge.typeText(step.text)
                }
            }

            is PlanStep.Navigate -> {
                when (step.direction) {
                    PlanStep.Navigate.NavigationDirection.BACK -> AccessibilityCommandBridge.performBack()
                    PlanStep.Navigate.NavigationDirection.HOME -> AccessibilityCommandBridge.performHome()
                    PlanStep.Navigate.NavigationDirection.RECENTS -> AccessibilityCommandBridge.performRecents()
                }
            }

            is PlanStep.Wait -> {
                val durationMs = step.durationMs ?: 1000L
                kotlinx.coroutines.delay(durationMs)
                CommandResult(true, "Waited ${durationMs}ms")
            }

            is PlanStep.Scroll -> {
                when (step.direction) {
                    PlanStep.Scroll.ScrollDirection.UP -> AccessibilityCommandBridge.scrollUp()
                    PlanStep.Scroll.ScrollDirection.DOWN -> AccessibilityCommandBridge.scrollDown()
                    PlanStep.Scroll.ScrollDirection.LEFT -> AccessibilityCommandBridge.scrollLeft()
                    PlanStep.Scroll.ScrollDirection.RIGHT -> AccessibilityCommandBridge.scrollRight()
                }
            }

            is PlanStep.Custom -> {
                Log.w(TAG, "Custom action: ${step.action}")
                CommandResult(false, "Custom action not implemented: ${step.action}")
            }
        }
    }
}
