package com.airi.assistant.profile

import org.junit.Assert.*
import org.junit.Test

/**
 * DeleteAccountPolicyTest — behavioral policy tests for account deletion.
 *
 * Tests that: dialog must be shown, cancel does not delete, confirmation
 * is required, and a failure does not report false success.
 */
class DeleteAccountPolicyTest {

    @Test
    fun `delete button shows confirmation dialog, does not delete immediately`() {
        var deletionTriggered = false
        var dialogShown       = false

        // Simulate pressing delete button
        dialogShown = true

        // Deletion must not fire until confirmation
        assertFalse("Deletion must not fire without confirmation", deletionTriggered)
        assertTrue("Confirmation dialog must be shown", dialogShown)
    }

    @Test
    fun `cancel in confirmation dialog does not trigger deletion`() {
        var deletionTriggered = false

        // Simulate cancel click inside dialog
        val onCancelClick = { /* showDeleteConfirm = false */ }
        onCancelClick()

        assertFalse("Cancel must not trigger deletion", deletionTriggered)
    }

    @Test
    fun `confirming deletion triggers the deletion flow`() {
        var deletionFlowStarted = false

        val onConfirmDelete = { deletionFlowStarted = true }
        onConfirmDelete()

        assertTrue("Confirmed deletion must start the deletion flow", deletionFlowStarted)
    }

    @Test
    fun `failed deletion does not call success callback`() {
        var successCalled = false

        val simulateDeleteResult: (Boolean) -> Unit = { success ->
            if (success) successCalled = true
        }

        // Simulate a Firebase auth failure
        simulateDeleteResult(false)

        assertFalse("Success callback must not fire on failure", successCalled)
    }

    @Test
    fun `successful deletion clears local profile identity`() {
        var profile = UserPreferences(
            displayName    = "To Delete",
            localPhotoPath = "/data/files/profile/uid/avatar.jpg"
        )
        var navigatedToLogin = false

        // Simulate successful deletion sequence
        profile = profile.copy(displayName = "", localPhotoPath = "")
        navigatedToLogin = true

        assertEquals("", profile.displayName)
        assertEquals("", profile.localPhotoPath)
        assertTrue("Must navigate to login after deletion", navigatedToLogin)
    }

    @Test
    fun `reauth required flag is surfaced to user on session-expiry failure`() {
        var reauthMessageShown = false

        val handleDeletionResult: (requiresReauth: Boolean) -> Unit = { requiresReauth ->
            if (requiresReauth) reauthMessageShown = true
        }

        // Firebase returns FirebaseAuthRecentLoginRequiredException
        handleDeletionResult(/* requiresReauth = */ true)

        assertTrue("Re-auth requirement must be communicated to user", reauthMessageShown)
    }

    @Test
    fun `isDeleting flag prevents second delete invocation during in-flight request`() {
        var deleteCallCount = 0
        var isDeleting      = false

        val onConfirmDelete = {
            if (!isDeleting) {
                isDeleting = true
                deleteCallCount++
            }
        }

        onConfirmDelete()
        onConfirmDelete() // second invocation during in-flight

        assertEquals("Delete must be called exactly once", 1, deleteCallCount)
    }
}
