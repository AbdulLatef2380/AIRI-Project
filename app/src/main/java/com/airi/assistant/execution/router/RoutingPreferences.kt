package com.airi.assistant.execution.router

import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.PrivacyLevel

/** Values needed to make an execution routing decision. */
interface RoutingPreferences {
    val effectiveMode: ExecutionMode
    val privacyLevel: PrivacyLevel
    val internetPermissionGranted: Boolean
    val offlineFallbackEnabled: Boolean
    val isCloudBudgetExhausted: Boolean
    val maxDailyCloudTokens: Int
    val cloudTokensUsedToday: Int
}
