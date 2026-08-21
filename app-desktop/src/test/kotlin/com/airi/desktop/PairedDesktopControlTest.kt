package com.airi.desktop

import com.airi.core.remote.RemoteControlCommand
import com.airi.core.remote.RemoteControlCommandType
import com.airi.core.remote.RemoteControlSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PairedDesktopControlTest {

    private class Target : DesktopRemoteControlTarget {
        var draftStarted = false
        var submittedText: String? = null
        var cancellable = false

        override fun status(): String = "Desktop is ready."
        override fun startNewDraft() { draftStarted = true }
        override fun submitTextRequest(text: String) { submittedText = text }
        override fun cancelOwnedRequest(): Boolean = cancellable
    }

    private fun session() = RemoteControlSession(
        pairingId = "pair-1",
        controllerDeviceId = "android-1",
        desktopDeviceId = "desktop-1",
        expiresAtMillis = 10_000,
        allowedCommands = RemoteControlCommandType.entries.toSet()
    )

    private fun command(type: RemoteControlCommandType, sequence: Long, text: String? = null) = RemoteControlCommand(
        commandId = "command-$sequence",
        pairingId = "pair-1",
        controllerDeviceId = "android-1",
        sequence = sequence,
        issuedAtMillis = 1_000,
        type = type,
        text = text
    )

    @Test
    fun `paired commands dispatch only AIRI owned actions`() {
        val target = Target()
        val control = PairedDesktopControl(session(), target) { 2_000 }

        assertIs<DesktopRemoteControlResult.Executed>(control.receive(command(RemoteControlCommandType.START_NEW_DRAFT, 1)))
        assertIs<DesktopRemoteControlResult.Executed>(control.receive(command(RemoteControlCommandType.SUBMIT_TEXT_REQUEST, 2, "افتح مسودة")))

        assertEquals(true, target.draftStarted)
        assertEquals("افتح مسودة", target.submittedText)
    }

    @Test
    fun `replayed and noncancellable commands do not execute`() {
        val target = Target()
        val control = PairedDesktopControl(session(), target) { 2_000 }

        assertIs<DesktopRemoteControlResult.Executed>(control.receive(command(RemoteControlCommandType.REQUEST_STATUS, 1)))
        assertIs<DesktopRemoteControlResult.Rejected>(control.receive(command(RemoteControlCommandType.START_NEW_DRAFT, 1)))
        assertIs<DesktopRemoteControlResult.Rejected>(control.receive(command(RemoteControlCommandType.CANCEL_OWNED_REQUEST, 2)))
    }

    @Test
    fun `revoked pairing rejects future commands`() {
        val control = PairedDesktopControl(session(), Target()) { 2_000 }
        control.revoke()

        assertIs<DesktopRemoteControlResult.Rejected>(control.receive(command(RemoteControlCommandType.REQUEST_STATUS, 1)))
    }
}
