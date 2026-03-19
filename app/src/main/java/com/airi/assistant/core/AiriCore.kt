package com.airi.assistant.core

import android.content.Context
import android.content.Intent
import android.util.Log
import com.airi.assistant.ai.LlamaManager
import com.airi.assistant.tools.ToolRegistry
import com.airi.assistant.tools.ToolScanner
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.system.SystemControlManager
import com.airi.assistant.voice.VoiceManager
import com.airi.assistant.core.UnifiedCognitiveLoop
import com.airi.assistant.agent.decision.PolicyEngine
import com.airi.assistant.agent.execution.ExperienceStore
import kotlinx.coroutines.*
import com.airi.assistant.core.IntentRouter
import kotlinx.coroutines.channels.Channel

/**
 * AIRI Core
 * Central Event-Driven Runtime Bus
 */
object AiriCore {

    private const val TAG = "AIRI_CORE"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val eventChannel = Channel<AiriEvent>(Channel.UNLIMITED)

    private var initialized = false
    private lateinit var appContext: Context

    private lateinit var memoryManager: MemoryManager
    private lateinit var policyEngine: PolicyEngine
    private lateinit var controlManager: SystemControlManager
    private lateinit var voiceManager: VoiceManager

    private lateinit var intentRouter: IntentRouter
    private lateinit var llamaManager: LlamaManager
    private lateinit var cognitiveLoop: UnifiedCognitiveLoop

    /**
     * Voice listener
     */
    private val voiceListener = object : VoiceManager.VoiceListener {

        override fun onWakeWordDetected() {
            Log.d(TAG, "Wake word detected")
        }

        override fun onSpeechResult(text: String) {
            scope.launch {
                send(AiriEvent.VoiceInput(text))
            }
        }

        override fun onError(error: String) {
            Log.e(TAG, "Voice error: $error")
        }
    }

    /**
     * Event Types
     */
    sealed class AiriEvent {

        data class UserInput(
            val text: String,
            val source: InputSource
        ) : AiriEvent()

        data class VoiceInput(
            val text: String
        ) : AiriEvent()

        data class ScreenContext(
            val data: String
        ) : AiriEvent()

        data class UIRequest(
            val message: String
        ) : AiriEvent()

        object RefreshTools : AiriEvent()
    }

    /**
     * Initialization
     */
    fun init(context: Context) {

        if (initialized) return

        appContext = context.applicationContext

        Log.i(TAG, "Initializing AIRI Core")

        // تهيئة مخزن الخبرة أولاً لضمان توفر قاعدة البيانات
        ExperienceStore.init(appContext)

        memoryManager = MemoryManager(appContext)
        policyEngine = PolicyEngine()
        controlManager = SystemControlManager(appContext)

        voiceManager = VoiceManager(
            appContext,
            voiceListener
        )

        // ✅ التعديل: استخدام الـ Constructor الافتراضي كما طلبت
        intentRouter = IntentRouter()
        
        llamaManager = LlamaManager(appContext)

        cognitiveLoop = UnifiedCognitiveLoop(
            appContext,
            intentRouter,
            llamaManager
        )

        refreshTools()

        startEventLoop()

        initialized = true

        Log.i(TAG, "AIRI Core initialized")
    }

    /**
     * Event loop
     */
    private fun startEventLoop() {

        scope.launch {

            for (event in eventChannel) {

                try {

                    handleEvent(event)

                } catch (e: Exception) {

                    Log.e(TAG, "Event processing error", e)

                }

            }

        }

    }

    /**
     * Send event
     */
    suspend fun send(event: AiriEvent) {

        eventChannel.send(event)

    }

    /**
     * Handle events
     */
    private fun handleEvent(event: AiriEvent) {

        when (event) {

            is AiriEvent.UserInput -> {
                processText(event.text)
            }

            is AiriEvent.VoiceInput -> {
                processText(event.text)
            }

            is AiriEvent.ScreenContext -> {
                updateScreenContext(event.data)
            }

            is AiriEvent.UIRequest -> {
                updateUI(event.message)
            }

            AiriEvent.RefreshTools -> {
                refreshTools()
            }

        }

    }

    /**
     * Run LLM pipeline
     */
    private fun processText(text: String) {

        scope.launch {

            cognitiveLoop.process(text) { result ->

                updateUI(result)

            }

        }

    }

    /**
     * Tool refresh
     */
    private fun refreshTools() {

        scope.launch(Dispatchers.IO) {

            try {

                val tools = ToolScanner.scan(appContext)

                // ✅ ToolRegistry كـ Object يتم استدعاؤه مباشرة
                ToolRegistry.register(tools)

                Log.i(TAG, "Tools refreshed: ${tools.size}")

            } catch (e: Exception) {

                Log.e(TAG, "Tool refresh failed", e)

            }

        }

    }

    /**
     * Screen context update
     */
    private fun updateScreenContext(data: String) {

        Log.d(TAG, "Screen context updated: ${data.hashCode()}")

    }

    /**
     * UI broadcast
     */
    private fun updateUI(message: String) {

        Log.i(TAG, "UI update: $message")

        val intent = Intent("com.airi.assistant.UI_UPDATE")

        intent.setPackage(appContext.packageName)

        intent.putExtra("message", message)

        appContext.sendBroadcast(intent)

    }

}
