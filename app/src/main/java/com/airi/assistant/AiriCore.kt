package com.airi.assistant

import android.content.Context
import android.util.Log
import com.airi.assistant.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

/**
 * AIRI Core - The Central Event-Driven Bus.
 * Orchestrates communication between Senses (Accessibility, Voice) and Brain (LLM).
 */
object AiriCore {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val eventChannel = Channel<AiriEvent>(Channel.UNLIMITED)
    
    private var isInitialized = false
    private lateinit var appContext: Context
    private lateinit var llama: LlamaNative
    private lateinit var memoryManager: MemoryManager
    private lateinit var policyEngine: PolicyEngine
    private lateinit var auditManager: AuditManager
    private lateinit var controlManager: SystemControlManager
    private lateinit var voiceManager: VoiceManager
    private lateinit var promptBuilder: PromptBuilder
    private lateinit var cognitiveLoop: UnifiedCognitiveLoop

    sealed class AiriEvent {
        data class UserInput(val text: String, val source: InputSource) : AiriEvent()
        data class ScreenContext(val data: String) : AiriEvent()
        data class UIRequest(val message: String) : AiriEvent()
    }

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        
        llama = LlamaNative(appContext)
        memoryManager = MemoryManager(appContext)
        policyEngine = PolicyEngine()
        auditManager = AuditManager(appContext)
        controlManager = SystemControlManager(appContext)
        voiceManager = VoiceManager(appContext)
        promptBuilder = PromptBuilder(memoryManager)
        
        cognitiveLoop = UnifiedCognitiveLoop(
            appContext,
            promptBuilder,
            llama,
            policyEngine,
            auditManager,
            controlManager,
            voiceManager
        )
        
        startEventLoop()
        isInitialized = true
        Log.d("AIRI_CORE", "Core Bus initialized and running.")
    }

    private fun startEventLoop() {
        scope.launch {
            for (event in eventChannel) {
                try {
                    handleEvent(event)
                } catch (e: Exception) {
                    Log.e("AIRI_CORE", "Error handling event: ${e.message}")
                }
            }
        }
    }

    suspend fun send(event: AiriEvent) {
        eventChannel.send(event)
    }

    private fun handleEvent(event: AiriEvent) {
        when (event) {
            is AiriEvent.UserInput -> cognitiveLoop.processInput(event.text, event.source)
            is AiriEvent.ScreenContext -> updateScreenContext(event.data)
            is AiriEvent.UIRequest -> updateUI(event.message)
        }
    }

    private fun updateScreenContext(data: String) {
        Log.d("AIRI_CORE", "Screen context updated (Hash: ${data.hashCode()})")
    }

    private fun updateUI(message: String) {
        Log.i("AIRI_CORE", "UI Update: $message")
        val intent = android.content.Intent("com.airi.assistant.UI_UPDATE")
        intent.setPackage(appContext.packageName)
        intent.putExtra("message", message)
        appContext.sendBroadcast(intent)
    }
}
