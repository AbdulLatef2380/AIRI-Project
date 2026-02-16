package com.airi.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

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
    private lateinit var controlManager: SystemControlManager

    sealed class AiriEvent {
        data class VoiceInput(val text: String) : AiriEvent()
        data class ScreenContext(val data: String) : AiriEvent()
        data class CommandRequest(val intent: String, val action: String, val params: Map<String, String>) : AiriEvent()
        data class UIRequest(val message: String) : AiriEvent()
    }

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        
        llama = LlamaNative()
        memoryManager = MemoryManager(appContext)
        policyEngine = PolicyEngine()
        controlManager = SystemControlManager(appContext)
        
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

    private suspend fun handleEvent(event: AiriEvent) {
        when (event) {
            is AiriEvent.VoiceInput -> processUserPrompt(event.text)
            is AiriEvent.ScreenContext -> updateScreenContext(event.data)
            is AiriEvent.CommandRequest -> executeSecureCommand(event.intent, event.action, event.params)
            is AiriEvent.UIRequest -> updateUI(event.message)
        }
    }

    private suspend fun processUserPrompt(text: String) {
        Log.d("AIRI_CORE", "Processing prompt: $text")
        
        // 1. Get Context (Memory + Screen)
        val history = memoryManager.getConversationContext()
        val screenContext = AiriAccessibilityService.getInstance()?.getScreenContext() ?: ""
        
        val fullPrompt = "Screen Context:\n$screenContext\nHistory:\n$history\nUser: $text"
        
        // 2. Inference (Off-main thread)
        val response = withContext(Dispatchers.Default) {
            llama.generateResponse(fullPrompt)
        }
        
        // 3. Parse Intent & Commands (Command Router Layer)
        handleLLMResponse(response)
        
        memoryManager.recordInteraction("airi", response)
    }

    private suspend fun handleLLMResponse(response: String) {
        // Extract UI Message
        val uiMessage = response.substringBefore("COMMAND:").trim()
        if (uiMessage.isNotEmpty()) {
            send(AiriEvent.UIRequest(uiMessage))
        }

        // Extract and Route Commands
        if (response.contains("COMMAND:")) {
            try {
                val jsonStr = response.substringAfter("COMMAND:").trim()
                val json = org.json.JSONObject(jsonStr)
                val action = json.optString("action")
                val target = json.optString("target")
                
                // Map to Intent for Policy Engine
                val intent = when(action) {
                    "OPEN_APP", "OPEN_URL" -> "system"
                    "VOLUME_UP", "VOLUME_DOWN", "MEDIA_PLAY_PAUSE" -> "media"
                    else -> "unknown"
                }

                send(AiriEvent.CommandRequest(intent, action, mapOf("target" to target)))
            } catch (e: Exception) {
                Log.e("AIRI_CORE", "Failed to parse command JSON: ${e.message}")
            }
        }
    }

    private fun updateScreenContext(data: String) {
        // Update local context cache
        Log.d("AIRI_CORE", "Screen context updated (Hash: ${data.hashCode()})")
    }

    private suspend fun executeSecureCommand(intent: String, action: String, params: Map<String, String>) {
        // 1. Policy Check
        val evaluation = policyEngine.evaluate(intent, action)
        
        // 2. Audit Log
        AuditManager.logDecision(evaluation, intent, action)
        
        if (evaluation.isAllowed) {
            Log.d("AIRI_CORE", "Executing command: $action")
            
            val actionType = try {
                ActionType.valueOf(action)
            } catch (e: Exception) {
                ActionType.UNKNOWN
            }

            if (actionType != ActionType.UNKNOWN) {
                withContext(Dispatchers.Main) {
                    controlManager.execute(AiriCommand(actionType, params["target"]))
                }
            }
        } else {
            Log.w("AIRI_CORE", "Command blocked: ${evaluation.reason}")
            send(AiriEvent.UIRequest("عذراً، لا يمكنني تنفيذ ذلك: ${evaluation.reason}"))
        }
    }

    private fun updateUI(message: String) {
        Log.i("AIRI_CORE", "UI Update: $message")
        // Broadcast to OverlayService
        val intent = android.content.Intent("com.airi.assistant.UI_UPDATE")
        intent.setPackage(appContext.packageName)
        intent.putExtra("message", message)
        appContext.sendBroadcast(intent)
    }
}
