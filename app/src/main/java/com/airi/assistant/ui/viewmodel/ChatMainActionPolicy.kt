package com.airi.assistant.ui.viewmodel

internal enum class ChatMainAction { SEND, CANCEL, VOICE, NONE }

internal object ChatMainActionPolicy {
    fun resolve(
        showSend: Boolean,
        isGenerating: Boolean,
        isDispatchingAttachment: Boolean,
        isLongTextConversionInFlight: Boolean,
        canSend: Boolean,
        inferenceReady: Boolean,
        modelLoading: Boolean,
    ): ChatMainAction = when {
        isGenerating -> ChatMainAction.CANCEL
        isDispatchingAttachment || isLongTextConversionInFlight -> ChatMainAction.NONE
        showSend && canSend -> ChatMainAction.SEND
        showSend -> ChatMainAction.NONE
        inferenceReady && !modelLoading -> ChatMainAction.VOICE
        else -> ChatMainAction.NONE
    }
}
