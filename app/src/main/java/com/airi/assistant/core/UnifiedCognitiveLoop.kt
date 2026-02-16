package com.airi.assistant.core

import android.content.Context
import com.airi.assistant.*
import kotlinx.coroutines.*
import org.json.JSONObject

class UnifiedCognitiveLoop(
    private val context: Context,
    private val promptBuilder: PromptBuilder,
    private val llama: LlamaNative,
    private val policyEngine: PolicyEngine,
    private val auditManager: AuditManager,
    private val systemControl: SystemControlManager,
    private val voiceManager: VoiceManager
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun processInput(text: String, source: InputSource) {
        scope.launch {
            try {
                // 1. Perception & Intent Routing (Fast Path)
                val event = IntentEvent(text, source)
                val router = IntentRouter()
                val fastIntent = router.route(event)

                if (fastIntent.confidence > 0.9f && fastIntent.type != IntentType.CONVERSATION) {
                    executeFastPath(fastIntent)
                    return@launch
                }

                // 2. Reasoning (Slow Path - LLM)
                val prompt = promptBuilder.build(text)
                val response = llama.generateResponse(prompt)
                
                // 3. Planning & Policy Check
                handleLLMResponse(response)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun executeFastPath(intent: IntentResult) {
        val action = intent.extractedData["command"] ?: intent.extractedData["appName"] ?: ""
        val policyDecision = policyEngine.evaluate(intent.type.name, action)
        
        auditManager.logDecision(
            intentId = java.util.UUID.randomUUID().toString(),
            intent = intent.type.name,
            action = action,
            decision = policyDecision.isAllowed,
            reason = policyDecision.reason
        )

        if (policyDecision.isAllowed) {
            when (intent.type) {
                IntentType.SYSTEM_COMMAND -> systemControl.executeCommand(action)
                IntentType.APP_CONTROL -> systemControl.openApp(action)
                else -> {}
            }
        }
    }

    private fun handleLLMResponse(jsonResponse: String) {
        try {
            val json = JSONObject(jsonResponse)
            val mode = json.optString("mode")
            val responseText = json.optString("response")
            
            if (mode == "RESPONSE" || mode == "HYBRID") {
                if (responseText.isNotEmpty()) {
                    voiceManager.speak(responseText)
                    // Update UI via Broadcast or EventBus
                }
            }

            if (mode == "ACTION" || mode == "HYBRID") {
                val actionObj = json.optJSONObject("action")
                val actionType = actionObj?.optString("type")
                val params = actionObj?.optJSONObject("parameters")
                
                // Policy Check for LLM Action
                val policyDecision = policyEngine.evaluate(actionType ?: "NONE", params?.toString() ?: "")
                if (policyDecision.isAllowed) {
                    // Execute Action
                }
            }
        } catch (e: Exception) {
            // Fallback to plain text if JSON fails
            voiceManager.speak(jsonResponse)
        }
    }
}
