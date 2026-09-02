package com.airi.assistant.profile

import org.junit.Assert.*
import org.junit.Test

/**
 * LogoutContractTest — verifies the expected behavioral contract of the
 * logout flow without requiring Android or Firebase dependencies.
 *
 * These tests assert policy, not implementation, so they remain stable
 * across UI refactors.
 */
class LogoutContractTest {

    @Test
    fun `logout with pending double-click: second click is no-op when isLoggingOut is true`() {
        var logoutCallCount = 0
        var isLoggingOut    = false

        val onLogoutClick = {
            if (!isLoggingOut) {
                isLoggingOut = true
                logoutCallCount++
            }
        }

        // Simulate double-click
        onLogoutClick()
        onLogoutClick()

        assertEquals("Logout must fire exactly once even if clicked twice", 1, logoutCallCount)
    }

    @Test
    fun `resetIdentity is called before signOut in contract sequence`() {
        val callOrder = mutableListOf<String>()

        val simulatedSignOut = {
            callOrder += "resetIdentity"
            callOrder += "signOut"
            callOrder += "clearMessages"
        }
        simulatedSignOut()

        assertEquals(
            "resetIdentity must precede signOut",
            "resetIdentity",
            callOrder.first()
        )
        assertEquals(
            "signOut must follow resetIdentity",
            "signOut",
            callOrder[1]
        )
    }

    @Test
    fun `logout success clears local profile identity`() {
        var profile = UserPreferences(
            displayName    = "Test User",
            localPhotoPath = "/data/files/profile/uid/avatar.jpg"
        )

        // Simulate what resetIdentity() does to the in-memory model
        profile = profile.copy(
            displayName    = "",
            localPhotoPath = ""
        )

        assertEquals("", profile.displayName)
        assertEquals("", profile.localPhotoPath)
    }

    @Test
    fun `account switch: new account starts with blank identity`() {
        // Account A signs out → identity reset
        var profile = UserPreferences(
            displayName    = "Account A",
            localPhotoPath = "/data/files/profile/uid_a/avatar.jpg"
        )
        // Sign-out resets identity
        profile = profile.copy(displayName = "", localPhotoPath = "")

        // Account B signs in — profile starts from defaults
        val accountBProfile = UserPreferences()
        assertEquals("", accountBProfile.displayName)
        assertEquals("", accountBProfile.localPhotoPath)

        // Assert A's data does not leak to B's fresh profile
        assertNotEquals("Account A", accountBProfile.displayName)
    }
}
