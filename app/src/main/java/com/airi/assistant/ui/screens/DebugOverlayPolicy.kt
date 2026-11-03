package com.airi.assistant.ui.screens

/** Release builds must never expose debug overlays or diagnostic banners. */
internal object DebugOverlayPolicy {
    fun shouldShowDiagnostics(isDebugBuild: Boolean, debugModeEnabled: Boolean): Boolean =
        isDebugBuild && debugModeEnabled
}
