package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMainActionPolicyTest {
    @Test
    fun sendIsDisabledWhenModelIsLoadingEvenIfAProviderWasPreviouslyReady() {
        assertEquals(
            ChatMainAction.NONE,
            ChatMainActionPolicy.resolve(
                showSend = true,
                isGenerating = false,
                isDispatchingAttachment = false,
                isLongTextConversionInFlight = false,
                canSend = false,
                inferenceReady = true,
                modelLoading = true,
            )
        )
    }

    @Test
    fun cancelRemainsAvailableWhileGenerating() {
        assertEquals(
            ChatMainAction.CANCEL,
            ChatMainActionPolicy.resolve(true, true, false, false, false, false, false)
        )
    }

    @Test
    fun voiceIsAvailableOnlyWhenNoSendContentAndInferenceIsReady() {
        assertEquals(
            ChatMainAction.VOICE,
            ChatMainActionPolicy.resolve(false, false, false, false, false, true, false)
        )
        assertEquals(
            ChatMainAction.NONE,
            ChatMainActionPolicy.resolve(false, false, false, false, false, false, false)
        )
    }
}
