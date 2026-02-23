package com.airi.assistant.core

import android.content.Context
import android.util.Log
import com.airi.assistant.*
import com.airi.assistant.tools.*
import com.airi.assistant.planner.*
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * المحرك الإدراكي الموحد (Unified Cognitive Loop)
 * يربط الإدراك بالتفكير ثم التنفيذ، مع دمج طبقة الأدوات والسياسات والتعلم الذاتي.
 */
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
            val startTime = System.currentTimeMillis()
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
                handleLLMResponse(text, response, startTime)

            } catch (e: Exception) {
                Log.e("UCL", "Error in cognitive loop: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun executeFastPath(intent: IntentResult) {
        val action = intent.extractedData["command"] ?: intent.extractedData["appName"] ?: ""
        val policyDecision = policyEngine.evaluate(intent.type.name, action)
        
        auditManager.logDecision(
            result = policyDecision,
            intent = intent.type.name,
            action = action
        )

        if (policyDecision.isAllowed) {
            when (intent.type) {
                IntentType.SYSTEM_COMMAND -> systemControl.executeCommand(action)
                IntentType.APP_CONTROL -> systemControl.openApp(action)
                IntentType.AUTOMATION -> {
                    val tool = ToolRegistry.findBestMatch(action)
                    if (tool != null) {
                        executeTool(tool, intent.extractedData)
                    }
                }
                else -> {}
            }
        }
    }

    private fun handleLLMResponse(goal: String, jsonResponse: String, startTime: Long) {
        try {
            val json = JSONObject(jsonResponse)
            val mode = json.optString("mode")
            val responseText = json.optString("response")
            
            if (mode == "RESPONSE" || mode == "HYBRID") {
                if (responseText.isNotEmpty()) {
                    voiceManager.speak(responseText)
                    scope.launch { AiriCore.send(AiriCore.AiriEvent.UIRequest(responseText)) }
                }
            }

            if (mode == "ACTION" || mode == "HYBRID") {
                val actionObj = json.optJSONObject("action")
                val toolName = actionObj?.optString("tool") ?: actionObj?.optString("type")
                val params = actionObj?.optJSONObject("parameters")
                
                if (toolName != null) {
                    val tool = ToolRegistry.findByName(toolName)
                    if (tool != null) {
                        val paramMap = mutableMapOf<String, Any>()
                        params?.keys()?.forEach { key ->
                            paramMap[key] = params.get(key)
                        }
                        
                        val policyDecision = policyEngine.evaluate("TOOL_EXECUTION", toolName)
                        if (policyDecision.isAllowed) {
                            executeTool(tool, paramMap, goal, jsonResponse, startTime)
                        } else {
                            val msg = "Policy Denied: ${policyDecision.reason}"
                            voiceManager.speak(msg)
                            scope.launch { AiriCore.send(AiriCore.AiriEvent.UIRequest(msg)) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("UCL", "Failed to parse LLM response: ${e.message}")
            voiceManager.speak(jsonResponse)
            scope.launch { AiriCore.send(AiriCore.AiriEvent.UIRequest(jsonResponse)) }
        }
    }

    private fun executeTool(
        tool: ToolDefinition, 
        params: Map<String, Any>, 
        goal: String? = null, 
        plan: String? = null, 
        startTime: Long = 0
    ) {
        scope.launch {
            withContext(Dispatchers.IO) {
                Log.i("UCL", "Executing tool: ${tool.name}")
                val result = ToolExecutor.execute(tool, params)
                val timeTaken = if (startTime > 0) System.currentTimeMillis() - startTime else 0
                
                // تسجيل العملية في سجل الأثر
                val policyDecision = policyEngine.evaluate("TOOL_EXECUTION", tool.name)
                auditManager.logDecision(
                    result = policyDecision,
                    intent = "TOOL_EXECUTION",
                    action = tool.name
                )
                
                // تسجيل الخبرة للتعلم الذاتي (Self-Improving)
                if (goal != null && plan != null) {
                    val score = PlanScorer.score(result, 1, timeTaken)
                    ExecutionLogger.logEnd(goal, plan, tool.name, result ?: "", score)
                }
                
                scope.launch { AiriCore.send(AiriCore.AiriEvent.UIRequest("Tool Result: $result")) }
            }
        }
    }
}
