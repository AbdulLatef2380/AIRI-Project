package com.airi.assistant.domain.release

/**
 * Controls product families deliberately excluded from the frozen Android release.
 * Re-enabling a surface requires its own provider, legal, and store evidence.
 */
object ReleaseScopePolicy {
    const val commercialSurfacesEnabled: Boolean = false
}
