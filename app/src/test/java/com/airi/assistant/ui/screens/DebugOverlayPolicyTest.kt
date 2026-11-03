package com.airi.assistant.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugOverlayPolicyTest {

    @Test
    fun releaseBuildNeverShowsDiagnosticsEvenWhenPreferenceIsEnabled() {
        assertFalse(DebugOverlayPolicy.shouldShowDiagnostics(isDebugBuild = false, debugModeEnabled = true))
        assertFalse(DebugOverlayPolicy.shouldShowDiagnostics(isDebugBuild = false, debugModeEnabled = false))
    }

    @Test
    fun debugBuildShowsDiagnosticsOnlyAfterExplicitOptIn() {
        assertFalse(DebugOverlayPolicy.shouldShowDiagnostics(isDebugBuild = true, debugModeEnabled = false))
        assertTrue(DebugOverlayPolicy.shouldShowDiagnostics(isDebugBuild = true, debugModeEnabled = true))
    }
}
