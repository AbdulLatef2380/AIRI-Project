package com.airi.assistant.core

import android.content.Context
import android.util.Log
import com.airi.assistant.ai.LlamaManager
import com.airi.assistant.ai.PromptBuilder
import com.airi.assistant.agent.tools.SystemTools
import com.airi.assistant.core.intent.IntentType
import com.airi.assistant.memory.repository.MemoryManager
import kotlinx.coroutines.*
import org.json.JSONObject
import com.airi.assistant.agent.decision.PolicyEngine
import com.airi.assistant.agent.execution.ExperienceStore
/**
 * المحرك الإدراكي الموحد (Unified Cognitive Loop)
 * يربط الإدراك (Intent) بالذاكرة (Memory) ثم التفكير (LLM) والتنفيذ (Tools).
 */
class UnifiedCognitiveLoop(
    private val context: Context,
    private val intentRouter: IntentRouter,
    private val llamaManager: LlamaManager
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val memoryManager = MemoryManager(context)

    /**
     * معالجة المدخلات بشكل متدفق (Streaming)
     */
    fun processStream(
        input: String, 
        onToken: (String) -> Unit, 
        onComplete: (String) -> Unit
    ) {
        scope.launch {
            try {
                // 1. الإدراك (Perception)
                val event = IntentEvent(input, InputSource.TEXT)
                val intentResult = intentRouter.route(event)

                // 2. المسار السريع (Fast Path) للأوامر المباشرة
                if (intentResult.confidence > 0.85f && intentResult.type != IntentType.CONVERSATION) {
                    executeAction(intentResult)
                    withContext(Dispatchers.Main) {
                        onToken("تم تنفيذ الأمر: ${intentResult.type}")
                        onComplete("Action Executed")
                    }
                    return@launch
                }

                // 3. جلب السياق من الذاكرة (Memory Context)
                val memoryContext = memoryManager.getRecentMessages(5)
                    .reversed()
                    .joinToString("\n") { "${it.role}: ${it.content}" }

                // 4. بناء الـ Prompt (Agent Mode)
                val prompt = PromptBuilder.buildAgentPrompt(input, memoryContext)

                // 5. التفكير والتوليد المتدفق (Reasoning & Streaming)
                llamaManager.generateStream(prompt, { token ->
                    onToken(token)
                }, { fullResponse ->
                    handleAgentResponse(fullResponse)
                    onComplete(fullResponse)
                })

            } catch (e: Exception) {
                Log.e("UCL", "Error in streaming cognitive loop: ${e.message}")
                withContext(Dispatchers.Main) {
                    onToken("عذراً، حدث خطأ في معالجة الطلب.")
                    onComplete("")
                }
            }
        }
    }

    private fun executeAction(intent: IntentResult) {
        val data = intent.extractedData
        when (intent.type) {
            IntentType.SYSTEM_COMMAND -> {
                when (data["command"]) {
                    "back" -> SystemTools.goBack()
                    "home" -> SystemTools.goHome()
                }
            }
            IntentType.APP_CONTROL -> {
                data["appName"]?.let { SystemTools.openApp(context, it) }
            }
            else -> {}
        }
    }

    private fun handleAgentResponse(response: String) {
        try {
            if (response.trim().startsWith("{")) {
                val json = JSONObject(response)
                val mode = json.optString("mode")
                if (mode == "ACTION" || mode == "HYBRID") {
                    val action = json.optJSONObject("action")
                    val tool = action?.optString("tool")
                    val params = action?.optJSONObject("parameters")
                    
                    when (tool) {
                        "open_app" -> params?.optString("package")?.let { SystemTools.openApp(context, it) }
                        "search" -> params?.optString("query")?.let { SystemTools.searchWeb(context, it) }
                        "go_back" -> SystemTools.goBack()
                        "go_home" -> SystemTools.goHome()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("UCL", "Failed to parse agent response: ${e.message}")
        }
    }

    fun process(input: String, onResult: (String) -> Unit) {
        scope.launch {
            try {
                val event = IntentEvent(input, InputSource.TEXT)
                val intentResult = intentRouter.route(event)
                
                val memoryContext = memoryManager.getRecentMessages(5)
                    .reversed()
                    .joinToString("\n") { "${it.role}: ${it.content}" }

                val prompt = PromptBuilder.buildPrompt(input, memoryContext)
                llamaManager.generate(prompt, onResult)
            } catch (e: Exception) {
                Log.e("UCL", "Error in cognitive loop: ${e.message}")
                withContext(Dispatchers.Main) { onResult("خطأ في النظام.") }
            }
        }
    }
}
