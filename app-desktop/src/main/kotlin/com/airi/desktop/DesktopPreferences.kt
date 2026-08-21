package com.airi.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class DesktopPreferences(
    val showCapabilityHints: Boolean = true
)

class DesktopPreferencesStore(
    private val file: Path = Path.of(System.getProperty("user.home"), ".airi-desktop", "preferences.properties")
) {
    fun load(): DesktopPreferences = runCatching {
        if (!Files.exists(file)) return DesktopPreferences()
        val properties = Properties().apply {
            Files.newInputStream(file).use(::load)
        }
        DesktopPreferences(
            showCapabilityHints = properties.getProperty("showCapabilityHints")?.toBooleanStrictOrNull() ?: true
        )
    }.getOrDefault(DesktopPreferences())

    fun save(preferences: DesktopPreferences) {
        Files.createDirectories(file.parent)
        val properties = Properties().apply {
            setProperty("showCapabilityHints", preferences.showCapabilityHints.toString())
        }
        Files.newOutputStream(file).use { output ->
            properties.store(output, "AIRI Desktop preferences")
        }
    }
}

enum class DesktopServiceAvailability(val message: String) {
    AUTH_UNAVAILABLE("Authentication is not configured on this desktop."),
    VOICE_UNAVAILABLE("Voice capture is not configured on this desktop.")
}
