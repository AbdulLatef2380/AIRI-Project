package com.airi.assistant.core

import android.content.Context
import android.util.Log
import com.airi.assistant.ai.LlamaManager
import com.airi.assistant.ai.PromptBuilder
import com.airi.assistant.core.intent.IntentType
import com.airi.assistant.memory.repository.MemoryManager
import kotlinx.coroutines.*
import org.json.JSONObject

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
     * معالجة المدخلات بشكل متدفق (Streaming) - المسار المفضل للمحادثة
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

                // 2. جلب السياق من الذاكرة (Memory Context)
                val memoryContext = memoryManager.getRecentMessages(5)
                    .reversed()
                    .joinToString("\n") { "${it.role}: ${it.content}" }

                // 3. بناء الـ Prompt
                val prompt = PromptBuilder.buildPrompt(input, memoryContext)

                // 4. التفكير والتوليد المتدفق (Reasoning & Streaming)
                llamaManager.generateStream(prompt, onToken, onComplete)

            } catch (e: Exception) {
                Log.e("UCL", "Error in streaming cognitive loop: ${e.message}")
                withContext(Dispatchers.Main) {
                    onToken("عذراً، حدث خطأ في معالجة الطلب.")
                    onComplete("")
                }
            }
        }
    }

    /**
     * معالجة المدخلات بشكل كامل (Non-streaming)
     */
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
