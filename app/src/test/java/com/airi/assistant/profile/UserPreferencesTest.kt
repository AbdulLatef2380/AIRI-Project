package com.airi.assistant.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class UserPreferencesTest {
    @Test
    fun `profile defaults are local-safe and arabic-first`() {
        val preferences = UserPreferences()

        assertEquals("ar", preferences.preferredLanguage)
        assertEquals("", preferences.username)
        assertEquals("", preferences.localPhotoPath)
    }

    @Test
    fun `profile identity fields survive a typed copy`() {
        val preferences = UserPreferences(
            displayName = "AIRI User",
            username = "airi_user",
            localPhotoPath = "/data/user/0/com.airi.assistant/files/profile/avatar.jpg"
        )

        assertEquals("AIRI User", preferences.displayName)
        assertEquals("airi_user", preferences.username)
        assertEquals("/data/user/0/com.airi.assistant/files/profile/avatar.jpg", preferences.localPhotoPath)
    }
}
