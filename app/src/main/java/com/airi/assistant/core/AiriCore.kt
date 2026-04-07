package com.airi.assistant.core

/**
 * AIRI Core - The Central Event-Driven Bus.
 * Refactored for core package: No Android imports, No Context, No external layer dependencies.
 */
object AiriCore {

    private var isInitialized = false
    private lateinit var cognitiveLoop: UnifiedCognitiveLoop
    private lateinit var policyEngine: PolicyEngine
    private lateinit var intentRouter: IntentRouter

    sealed class AiriEvent {
        data class UserInput(val text: String, val source: InputSource) : AiriEvent()
        data class ScreenContext(val data: String) : AiriEvent()
        data class UIRequest(val message: String) : AiriEvent()
        data class VoiceInput(val text: String) : AiriEvent()
        object RefreshTools : AiriEvent()
    }

    fun init(loop: UnifiedCognitiveLoop, policy: PolicyEngine, router: IntentRouter) {
        if (isInitialized) return
        
        cognitiveLoop = loop
        policyEngine = policy
        intentRouter = router
        
        isInitialized = true
    }

    suspend fun send(event: AiriEvent) {
        handleEvent(event)
    }

    private suspend fun handleEvent(event: AiriEvent) {
        when (event) {
            is AiriEvent.UserInput -> cognitiveLoop.process(event.text)
            is AiriEvent.VoiceInput -> cognitiveLoop.process(event.text)
            is AiriEvent.ScreenContext -> { /* Handle screen context */ }
            is AiriEvent.UIRequest -> { /* Handle UI request */ }
            is AiriEvent.RefreshTools -> { /* Refresh tools */ }
        }
    }
}
