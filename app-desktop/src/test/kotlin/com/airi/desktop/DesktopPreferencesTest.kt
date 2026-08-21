package com.airi.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPreferencesTest {

    @Test
    fun `missing preferences use safe defaults`() {
        val file = Files.createTempDirectory("airi-desktop-preferences").resolve("preferences.properties")

        assertEquals(DesktopPreferences(), DesktopPreferencesStore(file).load())
    }

    @Test
    fun `preferences persist across store instances`() {
        val file = Files.createTempDirectory("airi-desktop-preferences").resolve("preferences.properties")
        DesktopPreferencesStore(file).save(DesktopPreferences(showCapabilityHints = false))

        val restored = DesktopPreferencesStore(file).load()

        assertTrue(!restored.showCapabilityHints)
    }
}
