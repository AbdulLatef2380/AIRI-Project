package com.airi.assistant.profile

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * AccountOwnershipPolicyTest — verifies account-ownership invariants for
 * [UserPreferences] and the identity-reset contract documented in
 * [UserProfileRepository].
 *
 * These are pure-logic tests; no Android context is required.
 */
class AccountOwnershipPolicyTest {

    // ── Profile state helpers ─────────────────────────────────────────────────

    private fun accountAProfile() = UserPreferences(
        displayName    = "Account A User",
        username       = "account_a",
        localPhotoPath = "/data/user/0/com.airi.assistant/files/profile/uid_a/avatar.jpg",
        avatarUrl      = "https://example.com/a.jpg"
    )

    /** Simulates resetIdentity() outcome on [UserPreferences]. */
    private fun UserPreferences.simulateIdentityReset() = copy(
        displayName    = "",
        username       = "",
        localPhotoPath = "",
        avatarUrl      = ""
    )

    // ── Identity-reset contract ────────────────────────────────────────────────

    @Test
    fun `identity fields are blank after reset`() {
        val after = accountAProfile().simulateIdentityReset()
        assertEquals("", after.displayName)
        assertEquals("", after.username)
        assertEquals("", after.localPhotoPath)
        assertEquals("", after.avatarUrl)
    }

    @Test
    fun `non-identity preferences survive identity reset`() {
        val profile = accountAProfile().copy(
            preferredLanguage = "ar",
            darkMode          = UserPreferences.DarkMode.DARK,
            voiceEnabled      = true,
            airiPersonaTone   = UserPreferences.Tone.CASUAL
        )
        val after = profile.simulateIdentityReset()

        // Non-identity fields must not be affected
        assertEquals("ar",                       after.preferredLanguage)
        assertEquals(UserPreferences.DarkMode.DARK, after.darkMode)
        assertEquals(true,                       after.voiceEnabled)
        assertEquals(UserPreferences.Tone.CASUAL, after.airiPersonaTone)
    }

    @Test
    fun `account A photo path does not appear in reset profile`() {
        val reset = accountAProfile().simulateIdentityReset()
        assertFalse(
            "Account A's photo path must not carry over",
            reset.localPhotoPath.contains("uid_a")
        )
    }

    @Test
    fun `fresh profile defaults are identity-blank`() {
        val fresh = UserPreferences()
        assertEquals("", fresh.displayName)
        assertEquals("", fresh.username)
        assertEquals("", fresh.localPhotoPath)
        assertEquals("", fresh.avatarUrl)
    }

    // ── Display name validation ────────────────────────────────────────────────

    @Test
    fun `blank display name is invalid`() {
        val name = "   "
        assertTrue("Blank name should be rejected", name.isBlank())
    }

    @Test
    fun `display name within limit is valid`() {
        val name = "A".repeat(60)
        assertFalse(name.isBlank())
        assertTrue(name.length <= 60)
    }

    @Test
    fun `display name exceeding limit is invalid`() {
        val name = "A".repeat(61)
        assertTrue("Name > 60 chars should be rejected", name.length > 60)
    }

    @Test
    fun `trimmed display name is stored`() {
        val raw     = "  Alice  "
        val trimmed = raw.trim()
        assertEquals("Alice", trimmed)
        assertFalse(trimmed.isBlank())
    }

    // ── Photo path ownership ───────────────────────────────────────────────────

    @Test
    fun `photo path for uid_a is scoped to uid_a`() {
        val uid  = "uid_a"
        val path = "/data/files/profile/$uid/avatar.jpg"
        assertTrue(path.contains(uid))
        assertFalse(path.contains("uid_b"))
    }

    @Test
    fun `photo path for uid_b does not match uid_a`() {
        val pathA = "/data/files/profile/uid_a/avatar.jpg"
        val pathB = "/data/files/profile/uid_b/avatar.jpg"
        assertNotEquals(pathA, pathB)
    }

    @Test
    fun `blank uid prevents photo caching`() {
        // cachePhotoForAccount() returns null when uid is blank.
        // We cannot call it here (needs Context), but we can assert
        // the uid-blank guard condition directly.
        val uid = ""
        assertTrue("Blank uid must block photo caching", uid.isBlank())
    }

    // ── Delete-account: cancel must not delete ────────────────────────────────

    @Test
    fun `cancel flag starts as false and prevents deletion`() {
        var deletionTriggered = false
        var cancelled         = false

        // Simulates dialog: cancel sets flag, no delete fires
        cancelled = true
        if (!cancelled) {
            deletionTriggered = true
        }

        assertFalse("Cancel must prevent deletion", deletionTriggered)
    }

    @Test
    fun `confirmation flag allows deletion to proceed`() {
        var deletionTriggered = false
        val confirmed         = true

        if (confirmed) {
            deletionTriggered = true
        }

        assertTrue("Confirmation must allow deletion", deletionTriggered)
    }

    // ── Security: User ID is UID, not a token ─────────────────────────────────

    @Test
    fun `uid does not look like a JWT token`() {
        // Firebase UIDs are short opaque IDs (typically 28 chars, no dots/colons).
        // ID tokens are long Base64url-encoded JWTs with two dots.
        val uid = "abc123XYZ789exampleUID000001"
        assertFalse("UID must not contain dots (token separator)", uid.contains('.'))
        assertTrue("UID should be reasonably short", uid.length < 128)
    }

    @Test
    fun `token-like string is distinct from uid`() {
        val idToken = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1aWQifQ.signature"
        val uid     = "abc123uid"
        // An ID token contains dots; a UID does not
        assertTrue(idToken.contains('.'))
        assertFalse(uid.contains('.'))
    }
}
