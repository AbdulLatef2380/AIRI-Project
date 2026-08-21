package com.airi.desktop

import com.airi.core.remote.RemoteControlCommand
import com.airi.core.remote.RemoteControlCommandType
import com.airi.core.remote.RemoteControlDecision
import com.airi.core.remote.RemoteControlPolicy
import com.airi.core.remote.RemoteControlSession

interface DesktopRemoteControlTarget {
    fun status(): String
    fun startNewDraft()
    fun submitTextRequest(text: String)
    fun cancelOwnedRequest(): Boolean
}

sealed interface DesktopRemoteControlResult {
    data class Executed(val message: String) : DesktopRemoteControlResult
    data class Rejected(val reason: String) : DesktopRemoteControlResult
}

class PairedDesktopControl(
    session: RemoteControlSession,
    private val target: DesktopRemoteControlTarget,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var activeSession = session

    fun receive(command: RemoteControlCommand): DesktopRemoteControlResult {
        return when (val decision = RemoteControlPolicy.decide(activeSession, command, clock())) {
            is RemoteControlDecision.Rejected -> DesktopRemoteControlResult.Rejected(decision.reason)
            is RemoteControlDecision.Accepted -> {
                activeSession = decision.updatedSession
                when (command.type) {
                    RemoteControlCommandType.REQUEST_STATUS,
                    RemoteControlCommandType.SYNC_STATE -> DesktopRemoteControlResult.Executed(target.status())
                    RemoteControlCommandType.START_NEW_DRAFT -> {
                        target.startNewDraft()
                        DesktopRemoteControlResult.Executed("A new AIRI Desktop draft is ready.")
                    }
                    RemoteControlCommandType.SUBMIT_TEXT_REQUEST -> {
                        target.submitTextRequest(requireNotNull(command.text).trim())
                        DesktopRemoteControlResult.Executed("The request was delivered to AIRI Desktop.")
                    }
                    RemoteControlCommandType.CANCEL_OWNED_REQUEST -> {
                        if (target.cancelOwnedRequest()) {
                            DesktopRemoteControlResult.Executed("The active AIRI Desktop request was cancelled.")
                        } else {
                            DesktopRemoteControlResult.Rejected("There is no cancellable AIRI Desktop request.")
                        }
                    }
                }
            }
        }
    }

    fun revoke() {
        activeSession = activeSession.copy(revoked = true)
    }
}
