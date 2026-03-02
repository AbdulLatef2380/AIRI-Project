package com.airi.assistant.agent.validation

import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.delay

object TemporalValidator {

    suspend fun validateAction(
        service: AccessibilityService,
        delayMs: Long = 600
    ): Boolean {

        val beforeHash = UiStateHasher.generateHash(
            service.rootInActiveWindow
        )

        delay(delayMs)

        val afterHash = UiStateHasher.generateHash(
            service.rootInActiveWindow
        )

        return beforeHash != afterHash
    }
}
