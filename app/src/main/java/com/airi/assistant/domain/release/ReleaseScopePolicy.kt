package com.airi.assistant.domain.release

import com.airi.assistant.BuildConfig

/**
 * Controls product families deliberately excluded from the frozen Android release.
 * Re-enabling a surface requires its own provider, legal, and store evidence.
 */
object ReleaseScopePolicy {
    const val commercialSurfacesEnabled: Boolean = false

    /**
     * Zapier and IFTTT remain excluded until their provider applications, credential
     * lifecycle, authorization callback, and release evidence are independently reviewed.
     */
    const val externalAutomationIntegrationsEnabled: Boolean = false

    /**
     * Internal traces, diagnostics, terminal and secret-inspection views are not
     * end-user release features. Debug builds retain them for development only.
     */
    val internalSurfacesEnabled: Boolean
        get() = allowsInternalSurfaces(isDebugBuild = BuildConfig.DEBUG)

    fun allowsInternalSurfaces(isDebugBuild: Boolean): Boolean = isDebugBuild
}
