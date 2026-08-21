package com.airi.core.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RemoteControlPolicyTest {

    private fun session(
        allowed: Set<RemoteControlCommandType> = setOf(
            RemoteControlCommandType.REQUEST_STATUS,
            RemoteControlCommandType.START_NEW_DRAFT,
            RemoteControlCommandType.SUBMIT_TEXT_REQUEST,
            RemoteControlCommandType.CANCEL_OWNED_REQUEST
        )
    ) = RemoteControlSession(
        pairingId = "pair-1",
        controllerDeviceId = "android-1",
        desktopDeviceId = "desktop-1",
        expiresAtMillis = 10_000,
        allowedCommands = allowed
    )

    private fun command(
        type: RemoteControlCommandType = RemoteControlCommandType.REQUEST_STATUS,
        sequence: Long = 1,
        text: String? = null
    ) = RemoteControlCommand(
        commandId = "command-$sequence",
        pairingId = "pair-1",
        controllerDeviceId = "android-1",
        sequence = sequence,
        issuedAtMillis = 1_000,
        type = type,
        text = text
    )

    @Test
    fun `accepts ordered command from paired controller`() {
        val decision = RemoteControlPolicy.decide(session(), command(), nowMillis = 2_000)

        assertEquals(1, assertIs<RemoteControlDecision.Accepted>(decision).updatedSession.lastAcceptedSequence)
    }

    @Test
    fun `rejects replayed or out of order command`() {
        val decision = RemoteControlPolicy.decide(session().copy(lastAcceptedSequence = 4), command(sequence = 4), nowMillis = 2_000)

        assertIs<RemoteControlDecision.Rejected>(decision)
    }

    @Test
    fun `rejects expired revoked and unavailable command`() {
        assertIs<RemoteControlDecision.Rejected>(RemoteControlPolicy.decide(session(), command(), nowMillis = 10_000))
        assertIs<RemoteControlDecision.Rejected>(RemoteControlPolicy.decide(session().copy(revoked = true), command(), nowMillis = 2_000))
        assertIs<RemoteControlDecision.Rejected>(
            RemoteControlPolicy.decide(
                session(setOf(RemoteControlCommandType.REQUEST_STATUS)),
                command(RemoteControlCommandType.CANCEL_OWNED_REQUEST),
                nowMillis = 2_000
            )
        )
    }

    @Test
    fun `validates text payload shape and size`() {
        assertIs<RemoteControlDecision.Accepted>(
            RemoteControlPolicy.decide(
                session(),
                command(RemoteControlCommandType.SUBMIT_TEXT_REQUEST, text = "افتح مسودة جديدة"),
                nowMillis = 2_000
            )
        )
        assertIs<RemoteControlDecision.Rejected>(
            RemoteControlPolicy.decide(session(), command(RemoteControlCommandType.SUBMIT_TEXT_REQUEST), nowMillis = 2_000)
        )
        assertIs<RemoteControlDecision.Rejected>(
            RemoteControlPolicy.decide(session(), command(text = "not allowed"), nowMillis = 2_000)
        )
    }
}
