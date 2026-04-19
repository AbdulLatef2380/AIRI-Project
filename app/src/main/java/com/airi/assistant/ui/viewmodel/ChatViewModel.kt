package com.airi.assistant.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.ai.CatalogEntry
import com.airi.assistant.ai.DeviceProfiler
import com.airi.assistant.ai.ModelConfigManager
import com.airi.assistant.ai.PerformanceMode
import com.airi.assistant.ai.DeviceTier
import com.airi.assistant.ai.LlamaManager
import com.airi.assistant.ai.ModelCatalog
import com.airi.assistant.ai.ModelInfo
import com.airi.assistant.ai.ModelLoader
import com.airi.assistant.ai.ModelManager
import com.airi.assistant.ai.ModelRegistry
import com.airi.assistant.ai.ModelScout
import com.airi.assistant.ai.ModelSource
import com.airi.assistant.ai.ModelType
import com.airi.assistant.ai.ModelValidator
import com.airi.assistant.ai.ValidationResult
import com.airi.assistant.ai.agent.background.AgentWorker
import com.airi.assistant.ai.remote.RemoteModel
import com.airi.assistant.ai.remote.RemoteModelExecutor
import com.airi.assistant.ai.remote.RemoteModelRegistry
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.agent.AgentService
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.domain.growth.ReferralManager
import com.airi.assistant.domain.monetization.PaywallTriggerEngine
import com.airi.assistant.domain.monetization.SubscriptionManager
import com.airi.assistant.domain.retention.RetentionManager
import com.airi.assistant.domain.permission.PermissionService
import com.airi.assistant.domain.skill.SkillService
import com.airi.assistant.domain.skill.SkillService.ToolCallResult
import com.airi.assistant.memory.dao.ChatSessionSummary
import com.airi.assistant.memory.entity.ChatMessage as MemoryChatMessage
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.tools.FileUtils
import com.airi.assistant.tools.ModelDownloadManager
import com.airi.assistant.tools.ModelDownloadService
import com.google.gson.Gson
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val id: Long = System.currentTimeMillis(),
    val agentTag: String? = null,
    val traceId: String? = null
)

data class AgentState(
    val isWorking: Boolean = false,
    val currentAction: String = "",
    val currentStep: Int = 0,
    val totalSteps: Int = 0
)

enum class LoadErrorType {
    NONE, FILE_NOT_FOUND, INVALID_FORMAT, TOO_SMALL, INSUFFICIENT_RAM, LOAD_FAILED
}

enum class AgentMode(val label: String, val prompt: String) {
    ASSISTANT("Assistant", "أنت AIRI، مساعد ذكي متوازن، واضح، ودقيق."),
    CREATIVE("Creative", "أنت AIRI في الوضع الإبداعي. اقترح أفكاراً متنوعة، استخدم خيالاً عملياً، واجعل الردود ملهمة دون إطالة غير ضرورية."),
    TECHNICAL("Technical", "أنت AIRI في الوضع التقني. ركز على الدقة، الخطوات العملية، والتحليل المنظم.")
}

data class ModelUiState(
    val selectedModelId: String = "",
    val selectedModelName: String = "No model",
    val selectedModelPath: String = "",
    val selectedModelSize: Long = 0L,
    val isModelLoading: Boolean = false,
    val isModelReady: Boolean = false,
    val loadError: String? = null,
    val loadErrorType: LoadErrorType = LoadErrorType.NONE,
    val loadProgress: Int = -1,
    val downloadedModelAvailable: Boolean = false,
    val downloadedModelPath: String = "",
    val availableModels: List<ModelInfo> = emptyList(),
    val catalogModels: List<CatalogEntry> = ModelCatalog.entries,
    val recommendedModels: List<CatalogEntry> = emptyList(),
    val isScanning: Boolean = false,
    val scannedModelIds: Set<String> = emptySet()
)

data class UpgradePrompt(
    val message: String,
    val source: String
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext        = application.applicationContext
    private val preferences       = appContext.getSharedPreferences("airi_ui_state", Context.MODE_PRIVATE)
    private val perfPrefs         = appContext.getSharedPreferences("airi_perf_stats", Context.MODE_PRIVATE)
    private val llamaManager      = LlamaManager(appContext)
    private val memoryManager     = MemoryManager(appContext)
    private val downloadManager   = ModelDownloadManager(appContext)
    private val modelConfigManager = ModelConfigManager(appContext)
    private val remoteExecutor    = RemoteModelExecutor()
    private val gson              = Gson()

    // ── Domain services ───────────────────────────────────────────────────────
    private val agentService         = ServiceLocator.agentService
    private val skillService         = ServiceLocator.skillService
    private val promptService        = ServiceLocator.promptService
    private val subscriptionManager  = ServiceLocator.subscriptionManager
    private val permissionService    = ServiceLocator.permissionService

    // ── UI State ──────────────────────────────────────────────────────────────

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _agentState = MutableStateFlow(AgentState())
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _modelState = MutableStateFlow(createInitialModelState())
    val modelState: StateFlow<ModelUiState> = _modelState.asStateFlow()

    private val _sessions = MutableStateFlow<List<ChatSessionSummary>>(emptyList())
    val sessions: StateFlow<List<ChatSessionSummary>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow("")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private val _agentMode = MutableStateFlow(
        runCatching {
            AgentMode.valueOf(
                preferences.getString("agent_mode", AgentMode.ASSISTANT.name) ?: AgentMode.ASSISTANT.name
            )
        }.getOrDefault(AgentMode.ASSISTANT)
    )
    val agentMode: StateFlow<AgentMode> = _agentMode.asStateFlow()

    private val _temperature = MutableStateFlow(preferences.getFloat("gen_temperature", 0.7f))
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _maxTokens = MutableStateFlow(preferences.getInt("gen_max_tokens", 512))
    val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

    private val _systemPrompt = MutableStateFlow(
        preferences.getString("gen_system_prompt", "").orEmpty()
    )
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _responseStyle = MutableStateFlow(
        preferences.getString("gen_response_style", "balanced") ?: "balanced"
    )
    val responseStyle: StateFlow<String> = _responseStyle.asStateFlow()

    private val _themeMode = MutableStateFlow(
        preferences.getString("app_theme_mode", "dark") ?: "dark"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _memoryEntries = MutableStateFlow<List<MemoryChatMessage>>(emptyList())
    val memoryEntries: StateFlow<List<MemoryChatMessage>> = _memoryEntries.asStateFlow()

    private val _memoryCount = MutableStateFlow(0)
    val memoryCount: StateFlow<Int> = _memoryCount.asStateFlow()

    private val _backgroundAgentEnabled = MutableStateFlow(
        preferences.getBoolean("background_agent_enabled", false)
    )
    val backgroundAgentEnabled: StateFlow<Boolean> = _backgroundAgentEnabled.asStateFlow()

    private val _performanceMode = MutableStateFlow(modelConfigManager.getPerformanceMode())
    val performanceMode: StateFlow<PerformanceMode> = _performanceMode.asStateFlow()

    fun setPerformanceMode(mode: PerformanceMode) {
        _performanceMode.value = mode
        _maxTokens.value = mode.maxTokens
        _temperature.value = mode.temperature
        modelConfigManager.setPerformanceMode(mode)
        preferences.edit()
            .putInt("gen_max_tokens", mode.maxTokens)
            .putFloat("gen_temperature", mode.temperature)
            .apply()
    }

    // ── Paywall trigger ───────────────────────────────────────────────────────

    private val _paywallTrigger = MutableStateFlow(false)
    val paywallTrigger: StateFlow<Boolean> = _paywallTrigger.asStateFlow()

    fun clearPaywallTrigger() { _paywallTrigger.value = false }

    private val _upgradePrompt = MutableStateFlow<UpgradePrompt?>(null)
    val upgradePrompt: StateFlow<UpgradePrompt?> = _upgradePrompt.asStateFlow()

    fun clearUpgradePrompt() { _upgradePrompt.value = null }

    // ── Storage permission gate ───────────────────────────────────────────────

    private val _storagePermissionRequired = MutableStateFlow(false)
    val storagePermissionRequired: StateFlow<Boolean> = _storagePermissionRequired.asStateFlow()

    fun onStoragePermissionGranted() {
        EventBus.emitSync(AppEvent.PermissionGranted("READ_EXTERNAL_STORAGE"))
        _storagePermissionRequired.value = false
        scanForLocalModels()
    }

    fun onStoragePermissionDenied(permanent: Boolean) {
        EventBus.emitSync(AppEvent.PermissionDenied("READ_EXTERNAL_STORAGE", permanent))
        _storagePermissionRequired.value = false
    }

    // ── Subscription info ─────────────────────────────────────────────────────

    fun getSubscriptionSummary(): SubscriptionManager.UsageSummary =
        subscriptionManager.getUsageSummary()

    fun isPremium(): Boolean = subscriptionManager.isPremium()

    fun upgradeToPremium() {
        subscriptionManager.setTier(com.airi.assistant.domain.monetization.SubscriptionTier.PREMIUM)
    }

    fun downgradeToFree() {
        subscriptionManager.setTier(com.airi.assistant.domain.monetization.SubscriptionTier.FREE)
    }

    init {
        ModelManager.setLoader(ModelLoader(llamaManager))
        loadInitialSession()
        val savedModel = ModelRegistry.getById(_modelState.value.selectedModelId)
        if (savedModel != null && File(savedModel.path).exists()) {
            loadModel(savedModel)
        }
        refreshRecommendedModels()
    }

    // ── Session Management ────────────────────────────────────────────────────

    private fun loadInitialSession() {
        viewModelScope.launch {
            val sessions  = runCatching { memoryManager.getAllSessions() }.getOrElse { emptyList() }
            val sessionId = preferences.getString(KEY_SESSION_ID, null)
                ?.takeIf { saved -> sessions.any { it.id == saved } }
                ?: sessions.firstOrNull()?.id
                ?: memoryManager.ensureDefaultSession().id
            loadSession(sessionId)
        }
    }

    fun getAllSessions() {
        viewModelScope.launch { refreshSessions() }
    }

    fun createNewSession() {
        viewModelScope.launch {
            val session = memoryManager.createSession()
            _currentSessionId.value = session.id
            preferences.edit().putString(KEY_SESSION_ID, session.id).apply()
            _messages.value = emptyList()
            _streamingText.value = ""
            _agentState.value = AgentState()
            llamaManager.setHistory(emptyList())
            refreshSessions()
        }
    }

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val history = runCatching { memoryManager.loadSession(sessionId) }.getOrElse { emptyList() }
            _currentSessionId.value = sessionId
            preferences.edit().putString(KEY_SESSION_ID, sessionId).apply()
            _messages.value = history.map { msg ->
                ChatMessage(text = msg.content, isUser = msg.role == "user", id = msg.id)
            }
            llamaManager.setHistory(history.takeLast(12))
            refreshSessions()
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            runCatching { memoryManager.deleteSession(sessionId) }
            refreshSessions()
            if (_currentSessionId.value == sessionId) {
                val next = _sessions.value.firstOrNull()?.id ?: memoryManager.createSession().id
                loadSession(next)
            }
        }
    }

    fun loadMemoryEntries() {
        viewModelScope.launch {
            _memoryEntries.value = runCatching { memoryManager.getSemanticMemories(200) }.getOrElse { emptyList() }
            _memoryCount.value = runCatching { memoryManager.getMessageCount() }.getOrElse { 0 }
        }
    }

    // ── Message Handling ──────────────────────────────────────────────────────

    fun sendMessage(input: String) {
        val trimmedInput = input.trim()
        if (trimmedInput.isEmpty()) return
        if (_modelState.value.isModelLoading) return

        // ── Subscription gate: enforce daily message quota (PolicyEngine) ────────
        val policyResult = com.airi.assistant.domain.policy.PolicyEngine.checkSubscriptionMessage(subscriptionManager)
        if (policyResult is com.airi.assistant.domain.policy.PolicyEngine.PolicyResult.Denied) {
            if (ReferralManager.consumeBonusUsage()) {
                AnalyticsService.funnelStep("bonus_message_used")
            } else {
                val summary = subscriptionManager.getUsageSummary()
                AnalyticsService.limitReached("daily_messages", summary.messagesUsed, summary.messagesLimit)
                PaywallTriggerEngine.onLimitReached()
                _messages.update {
                    it + ChatMessage(
                        "You reached your limit. Upgrade to continue.",
                        isUser = false
                    )
                }
                _paywallTrigger.value = true
                return
            }
        }

        val activeRemote = RemoteModelRegistry.getActive()
        if ((ModelManager.getCurrent() == null || !_modelState.value.isModelReady) && activeRemote == null) {
            _messages.update {
                it + ChatMessage("قم باختيار نموذج محلي أو Remote Model أولاً.", isUser = false)
            }
            return
        }

        viewModelScope.launch {
            val perfMode = _performanceMode.value
            val sessionId = currentSessionOrCreate()
            val wasEmpty  = _messages.value.isEmpty()
            val history   = trimContext(memoryManager.loadSession(sessionId), perfMode)
            val userMessage = memoryManager.recordChatMessage(sessionId, "user", trimmedInput)
            if (wasEmpty) memoryManager.renameSession(sessionId, trimmedInput.take(48))
            subscriptionManager.recordMessage()
            AnalyticsService.messageSent()
            if (RetentionManager.getTotalMessages() == 0) {
                AnalyticsService.firstMessageSent()
                AnalyticsService.funnelStep("signup_to_first_message")
            }
            RetentionManager.incrementMessageCount()
            // Soft paywall trigger — after threshold messages, show upgrade prompt post-send
            val triggerPaywallAfterSend = PaywallTriggerEngine.onMessageSent(subscriptionManager.isPremium())
            _messages.update { it + ChatMessage(trimmedInput, true, userMessage.id) }
            _agentState.value = AgentState(isWorking = true, currentAction = "Analyzing...")
            _streamingText.value = "Analyzing..."

            var thinkingJob: kotlinx.coroutines.Job? = viewModelScope.launch {
                kotlinx.coroutines.delay(700)
                if (_streamingText.value == "Analyzing...") {
                    _streamingText.value = "Planning..."
                    _agentState.update { it.copy(currentAction = "Planning...") }
                }
                kotlinx.coroutines.delay(800)
                if (_streamingText.value == "Planning...") {
                    _streamingText.value = "Generating..."
                    _agentState.update { it.copy(currentAction = "Generating...") }
                }
            }

            // ── Delegate to AgentService (goes through PolicyEngine + pipeline) ──
            val simpleQuery = promptService.isSimpleQuery(trimmedInput)
            val agentServiceResult = if (simpleQuery) {
                AgentService.AgentServiceResult(agentResult = null, errorMessage = null, isLlmFallback = true)
            } else {
                withContext(Dispatchers.IO) { agentService.handle(trimmedInput, history) }
            }

            when {
                agentServiceResult.errorMessage != null -> {
                    thinkingJob?.cancel(); thinkingJob = null
                    val errMsg = memoryManager.recordChatMessage(
                        sessionId, "assistant", agentServiceResult.errorMessage
                    )
                    _messages.update {
                        it + ChatMessage(agentServiceResult.errorMessage, isUser = false, id = errMsg.id)
                    }
                    _agentState.value = AgentState()
                    refreshSessions()
                    return@launch
                }

                agentServiceResult.agentResult != null -> {
                    thinkingJob?.cancel(); thinkingJob = null
                    val agentResult = agentServiceResult.agentResult
                    val responseText = if (agentResult.success) agentResult.text
                                       else agentResult.text.ifBlank { "Agent action failed. Please try again." }
                    if (agentResult.success) {
                        AnalyticsService.agentExecuted(agentResult.agentTag ?: "unknown")
                        // Soft paywall after first agent execution (non-blocking)
                        if (PaywallTriggerEngine.onAgentExecuted(subscriptionManager.isPremium())) {
                            _upgradePrompt.value = UpgradePrompt(
                                message = "AIRI can do much more — unlock Premium",
                                source = PaywallTriggerEngine.TriggerReason.FirstAgentExecution.source
                            )
                        }
                    }
                    if (responseText.isNotBlank()) {
                        val assistantMsg = memoryManager.recordChatMessage(sessionId, "assistant", responseText)
                        _messages.update {
                            it + ChatMessage(
                                text     = responseText,
                                isUser   = false,
                                id       = assistantMsg.id,
                                agentTag = agentResult.agentTag,
                                traceId  = agentResult.traceId
                            )
                        }
                    }
                    _agentState.value = AgentState()
                    refreshSessions()
                    // Fire soft paywall (message threshold) after response is shown
                    if (triggerPaywallAfterSend && !_paywallTrigger.value) {
                        _upgradePrompt.value = UpgradePrompt(
                            message = "Useful response? Unlock Premium for more AIRI power.",
                            source = PaywallTriggerEngine.TriggerReason.MessageThreshold.source
                        )
                    }
                    return@launch
                }

                // isLlmFallback == true — proceed to local LLM
            }

            val streamStart  = System.currentTimeMillis()
            var tokenCount   = 0
            llamaManager.setHistory(history)
            val deviceWeak = isDeviceWeak()
            val remote = RemoteModelRegistry.getActive()
            val systemPrompt = buildGenerationSystemPrompt(trimmedInput, perfMode)
            val uiPrefs = appContext.getSharedPreferences("airi_ui_state", android.content.Context.MODE_PRIVATE)
            val repeatPenalty    = uiPrefs.getFloat("gen_repeat_penalty",    1.1f)
            val topK             = uiPrefs.getInt  ("gen_top_k",             40)
            val topP             = uiPrefs.getFloat("gen_top_p",             0.9f)
            val minP             = uiPrefs.getFloat("gen_min_p",             0.05f)
            val presencePenalty  = uiPrefs.getFloat("gen_presence_penalty",  0.0f)
            val frequencyPenalty = uiPrefs.getFloat("gen_frequency_penalty", 0.0f)
            // Adaptive token limit — clamp based on available RAM to prevent OOM crashes
            val availableRamMb = DeviceProfiler.profile(appContext).availableRamMb
            val adaptiveMaxTokens = when {
                availableRamMb < 1000 -> minOf(perfMode.maxTokens, 256)
                availableRamMb < 2000 -> minOf(perfMode.maxTokens, 512)
                else                  -> perfMode.maxTokens
            }
            Log.d("AIRI_PERF", "AdaptiveTokens: requested=${perfMode.maxTokens} clamped=$adaptiveMaxTokens availRAM=${availableRamMb}MB")
            Log.d("AIRI_GEN", "params: maxTokens=$adaptiveMaxTokens temp=${perfMode.temperature} " +
                    "repeatPenalty=$repeatPenalty topK=$topK topP=$topP minP=$minP " +
                    "presence=$presencePenalty frequency=$frequencyPenalty " +
                    "model=${_modelState.value.selectedModelName} input_len=${trimmedInput.length}")
            val finish: suspend (String, Long, Int) -> Unit = { fullResponse, elapsedMs, tokens ->
                recordGenerationStats(elapsedMs, tokens)
                val tps = if (elapsedMs > 0) tokens * 1000f / elapsedMs.coerceAtLeast(1) else 0f
                AnalyticsService.responseGenerated(elapsedMs, tps, _modelState.value.selectedModelName, false)
                if (fullResponse.isNotBlank()) {
                    val wasToolCall = handleToolIfNeeded(fullResponse, sessionId)
                    if (!wasToolCall) {
                        val assistantMessage = memoryManager.recordChatMessage(sessionId, "assistant", fullResponse)
                        _messages.update { it + ChatMessage(fullResponse, isUser = false, assistantMessage.id) }
                    }
                    refreshSessions()
                    if (triggerPaywallAfterSend && !_paywallTrigger.value) {
                        _upgradePrompt.value = UpgradePrompt(
                            message = "Useful response? Unlock Premium for more AIRI power.",
                            source = PaywallTriggerEngine.TriggerReason.MessageThreshold.source
                        )
                    }
                }
                _streamingText.value = ""
                _agentState.value    = AgentState()
            }

            if (deviceWeak && remote != null) {
                thinkingJob?.cancel()
                thinkingJob = null
                streamRemoteResponse(remote, trimmedInput, systemPrompt, perfMode, streamStart, finish)
            } else {
                llamaManager.generateStream(
                    prompt = trimmedInput,
                    systemPrompt = systemPrompt,
                    maxTokens = adaptiveMaxTokens,
                    temperature = perfMode.temperature,
                    repeatPenalty = repeatPenalty,
                    topK = topK,
                    topP = topP,
                    minP = minP,
                    presencePenalty = presencePenalty,
                    frequencyPenalty = frequencyPenalty,
                    timeoutMs = 15_000L,
                    onToken = { tokenBatch ->
                        thinkingJob?.cancel()
                        thinkingJob = null
                        tokenCount += tokenBatch.length / 4 + 1
                        val thinkingStages = setOf("Thinking...", "Analyzing...", "Planning...", "Generating...")
                        _streamingText.update { current ->
                            if (current in thinkingStages) tokenBatch else current + tokenBatch
                        }
                    },
                    onComplete = { fullResponse ->
                        viewModelScope.launch {
                            finish(fullResponse, System.currentTimeMillis() - streamStart, tokenCount)
                        }
                    },
                    onError = {
                        viewModelScope.launch {
                            thinkingJob?.cancel()
                            thinkingJob = null
                            val fallbackRemote = RemoteModelRegistry.getActive()
                            if (fallbackRemote != null) {
                                _streamingText.value = "Analyzing..."
                                streamRemoteResponse(fallbackRemote, trimmedInput, systemPrompt, perfMode, streamStart, finish)
                            } else {
                                val fallback = "تعذر توليد الرد بسرعة. جرّب Fast Mode أو Remote Model."
                                val assistantMessage = memoryManager.recordChatMessage(sessionId, "assistant", fallback)
                                _messages.update { it + ChatMessage(fallback, isUser = false, assistantMessage.id) }
                                _streamingText.value = ""
                                _agentState.value = AgentState()
                                refreshSessions()
                            }
                        }
                    }
                )
            }
        }
    }

    // ── Tool call processing (delegates to SkillService) ─────────────────────

    private suspend fun handleToolIfNeeded(response: String, sessionId: String): Boolean {
        return when (val toolResult = skillService.executeToolCall(response)) {
            is ToolCallResult.NoToolCall -> false

            is ToolCallResult.Executed -> {
                val toolCall = toolResult.toolCall
                val result   = toolResult.result
                AnalyticsService.skillUsed(toolCall.toolName)

                _agentState.value = AgentState(
                    isWorking     = true,
                    currentAction = "Running tool: ${toolCall.toolName.replace("_", " ")}…"
                )

                val followUpPrompt = if (result.success) {
                    "Tool '${toolCall.toolName}' returned this data:\n${result.data}\n\nExplain this clearly and helpfully to the user."
                } else {
                    "Tool '${toolCall.toolName}' failed with error: ${result.error ?: "Unknown error"}. " +
                            "Inform the user in a friendly, helpful way."
                }

                val finalResponse = suspendCoroutine<String> { continuation ->
                    llamaManager.generate(
                        prompt       = followUpPrompt,
                        systemPrompt = _agentMode.value.prompt,
                        maxTokens    = _performanceMode.value.maxTokens,
                        temperature  = _performanceMode.value.temperature,
                        onResult     = { text -> continuation.resume(text) }
                    )
                }

                if (finalResponse.isNotBlank()) {
                    val assistantMsg = memoryManager.recordChatMessage(sessionId, "assistant", finalResponse)
                    _messages.update { it + ChatMessage(finalResponse, isUser = false, assistantMsg.id) }
                }
                true
            }

            is ToolCallResult.Failed -> {
                val errMsg = memoryManager.recordChatMessage(sessionId, "assistant", toolResult.errorMessage)
                _messages.update {
                    it + ChatMessage(toolResult.errorMessage, isUser = false, id = errMsg.id)
                }
                true
            }
        }
    }

    // ── Prompt building (delegates to PromptService) ──────────────────────────

    private fun buildEffectiveSystemPrompt(
        perfMode: PerformanceMode = _performanceMode.value
    ): String = promptService.buildSystemPrompt(
        modePrompt    = _agentMode.value.prompt,
        responseStyle = _responseStyle.value,
        customPrompt  = _systemPrompt.value.trim(),
        performanceMode = perfMode
    )

    private fun buildGenerationSystemPrompt(input: String, perfMode: PerformanceMode): String {
        return if (promptService.isSimpleQuery(input)) {
            "You are AIRI. Answer immediately and briefly. No long system context. Max ${perfMode.maxTokens} tokens."
        } else {
            buildEffectiveSystemPrompt(perfMode)
        }
    }

    private suspend fun streamRemoteResponse(
        remote: RemoteModel,
        prompt: String,
        systemPrompt: String,
        perfMode: PerformanceMode,
        streamStart: Long,
        finish: suspend (String, Long, Int) -> Unit
    ) {
        var tokenCount = 0
        val result = remoteExecutor.generateStream(
            model = remote,
            prompt = prompt,
            systemPrompt = systemPrompt,
            maxTokens = perfMode.maxTokens,
            temperature = perfMode.temperature,
            onToken = { token ->
                tokenCount++
                withContext(Dispatchers.Main) {
                    val stages = setOf("Thinking...", "Analyzing...", "Planning...", "Generating...")
                    _streamingText.update { current ->
                        if (current in stages) token else current + token
                    }
                    delay(0)
                }
            }
        )
        withContext(Dispatchers.Main) {
            when (result) {
                is RemoteModelExecutor.RemoteResult.Success ->
                    finish(result.text, result.latencyMs.coerceAtLeast(System.currentTimeMillis() - streamStart), tokenCount)
                is RemoteModelExecutor.RemoteResult.Failure -> {
                    val fallback = "الرد تأخر أكثر من 15 ثانية. حاول مرة أخرى أو استخدم نموذج أخف."
                    val sessionId = currentSessionOrCreate()
                    val assistantMessage = memoryManager.recordChatMessage(sessionId, "assistant", fallback)
                    _messages.update { it + ChatMessage(fallback, isUser = false, assistantMessage.id) }
                    _streamingText.value = ""
                    _agentState.value = AgentState()
                    refreshSessions()
                }
            }
        }
    }

    private fun trimContext(messages: List<MemoryChatMessage>, perfMode: PerformanceMode): List<MemoryChatMessage> {
        val recent = messages.takeLast(6)
        val trimmed = ArrayDeque<MemoryChatMessage>()
        var approxTokens = 0
        val maxTokens = minOf(perfMode.contextWindow, 1500)
        for (msg in recent.asReversed()) {
            val count = (msg.content.length / 4).coerceAtLeast(1)
            if (trimmed.isNotEmpty() && approxTokens + count > maxTokens) break
            trimmed.addFirst(msg)
            approxTokens += count
        }
        return trimmed.toList()
    }

    private fun recordGenerationStats(elapsedMs: Long, tokenCount: Int) {
        val elapsed = elapsedMs.coerceAtLeast(1L)
        val tps = tokenCount * 1000f / elapsed
        Log.d("AIRI_PERF", "Generation complete: latency=${elapsed}ms tokens=$tokenCount tps=%.2f".format(tps))
        perfPrefs.edit()
            .putFloat("tokens_per_sec", tps)
            .putLong("last_latency_ms", elapsed)
            .apply()
    }

    private fun isDeviceWeak(): Boolean {
        val profile = DeviceProfiler.profile(appContext)
        return profile.tier == DeviceTier.LOW || profile.availableRamMb < 1500 || profile.cpuCores <= 4
    }

    // ── Skill management (delegates to SkillService) ──────────────────────────

    fun getSkillInfos(): List<SkillRegistry.SkillInfo> = skillService.getAllSkillInfos()

    fun setSkillEnabled(skillName: String, enabled: Boolean) {
        skillService.setSkillEnabled(skillName, enabled)
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun setAgentMode(mode: AgentMode) {
        _agentMode.value = mode
        preferences.edit().putString("agent_mode", mode.name).apply()
    }

    fun setBackgroundAgentEnabled(enabled: Boolean) {
        _backgroundAgentEnabled.value = enabled
        preferences.edit().putBoolean("background_agent_enabled", enabled).apply()
        if (enabled) AgentWorker.schedule(appContext) else AgentWorker.cancel(appContext)
    }

    fun clearModelError() {
        _modelState.update { it.copy(loadError = null, loadErrorType = LoadErrorType.NONE) }
    }

    fun setTemperature(value: Float) {
        _temperature.value = value
        preferences.edit().putFloat("gen_temperature", value).apply()
    }

    fun setMaxTokens(value: Int) {
        _maxTokens.value = value
        preferences.edit().putInt("gen_max_tokens", value).apply()
    }

    fun setSystemPrompt(value: String) {
        _systemPrompt.value = value
        preferences.edit().putString("gen_system_prompt", value).apply()
    }

    fun setResponseStyle(style: String) {
        _responseStyle.value = style
        preferences.edit().putString("gen_response_style", style).apply()
    }

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        preferences.edit().putString("app_theme_mode", mode).apply()
    }

    fun clearMessages()  { createNewSession() }

    fun clearMemory() {
        viewModelScope.launch {
            runCatching { memoryManager.clearAll() }
            _memoryEntries.value = emptyList()
            _memoryCount.value   = 0
        }
    }

    // ── Model import / selection ──────────────────────────────────────────────

    fun importModel(uri: Uri) {
        _modelState.update { it.copy(isModelLoading = true, loadError = null, loadErrorType = LoadErrorType.NONE, loadProgress = 0) }
        viewModelScope.launch {
            try {
                val path  = FileUtils.copyToInternalStorage(appContext, uri)
                val file  = File(path)
                val model = createModelFromFile(file, ModelSource.LOCAL_FILE, "custom")
                when (val v = ModelValidator.validate(file, appContext, model.ramRequiredMb)) {
                    is ValidationResult.Valid -> {
                        ModelRegistry.addModel(model)
                        persistRegistry()
                        preferences.edit()
                            .putString(KEY_MODEL_ID, model.id)
                            .putString(KEY_MODEL_PATH, model.path)
                            .apply()
                        refreshModelList()
                        loadModel(model)
                    }
                    else -> {
                        file.delete()
                        val (msg, type) = validationMessage(v)
                        _modelState.update {
                            it.copy(isModelLoading = false, isModelReady = false, loadError = msg,
                                loadErrorType = type, loadProgress = -1, availableModels = ModelManager.getAllModels())
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = AppErrorHandler.capture(e, "importModel").message
                _modelState.update {
                    it.copy(isModelLoading = false, isModelReady = false,
                        loadError = msg, loadErrorType = LoadErrorType.LOAD_FAILED,
                        loadProgress = -1, availableModels = ModelManager.getAllModels())
                }
            }
        }
    }

    fun selectModel(modelId: String) {
        val model = ModelRegistry.getById(modelId) ?: return
        preferences.edit().putString(KEY_MODEL_ID, model.id).putString(KEY_MODEL_PATH, model.path).apply()
        loadModel(model)
    }

    fun activateDownloadedModel() {
        val file = downloadManager.getModelFile()
        if (!file.exists()) {
            _modelState.update { it.copy(loadError = "Downloaded model file not found", loadErrorType = LoadErrorType.FILE_NOT_FOUND) }
            return
        }
        val catalogMeta = ModelCatalog.entries.find { it.fileName == file.name }
        val model = createModelFromFile(file, ModelSource.DOWNLOADED, "chat", catalogMeta)
        ModelRegistry.addModel(model)
        persistRegistry()
        preferences.edit().putString(KEY_MODEL_ID, model.id).putString(KEY_MODEL_PATH, model.path).apply()
        refreshModelList()
        loadModel(model)
    }

    fun downloadCatalogModel(entry: CatalogEntry) {
        val intent = Intent(appContext, ModelDownloadService::class.java).apply {
            putExtra(ModelDownloadService.EXTRA_DOWNLOAD_URL, entry.downloadUrl)
            putExtra(ModelDownloadService.EXTRA_FILENAME, entry.fileName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
        else appContext.startService(intent)
    }

    fun activateCatalogDownload(entry: CatalogEntry) {
        val file = downloadManager.getFileForName(entry.fileName)
        if (!file.exists()) {
            _modelState.update { it.copy(loadError = "${entry.fileName} غير موجود، قم بتحميله أولاً", loadErrorType = LoadErrorType.FILE_NOT_FOUND) }
            return
        }
        val model = createModelFromFile(file, ModelSource.DOWNLOADED, "chat", entry)
        ModelRegistry.addModel(model)
        persistRegistry()
        preferences.edit().putString(KEY_MODEL_ID, model.id).putString(KEY_MODEL_PATH, model.path).apply()
        refreshModelList()
        loadModel(model)
    }

    fun startDefaultModelDownload() {
        val intent = Intent(appContext, ModelDownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
        else appContext.startService(intent)
        refreshDownloadedModelState()
    }

    fun refreshDownloadedModelState() {
        syncDownloadedModelAvailability()
        val downloadedFile = downloadManager.getModelFile()
        _modelState.update {
            it.copy(
                downloadedModelAvailable = downloadManager.isModelDownloaded(),
                downloadedModelPath      = downloadedFile.absolutePath,
                availableModels          = ModelManager.getAllModels()
            )
        }
    }

    // ── Recommendations ───────────────────────────────────────────────────────

    fun getRecommendedModels(): List<CatalogEntry> {
        val profile = DeviceProfiler.profile(appContext)
        return ModelCatalog.entries.filter { entry ->
            when (profile.tier) {
                DeviceTier.LOW  -> entry.ramRequiredMb <= 1024
                DeviceTier.MID  -> entry.ramRequiredMb <= 2048
                DeviceTier.HIGH -> true
            }
        }.sortedWith(compareBy({ it.ramRequiredMb }, { it.sizeBytes }))
    }

    fun refreshRecommendedModels() {
        _modelState.update { it.copy(recommendedModels = getRecommendedModels()) }
    }

    // ── Model Scout ───────────────────────────────────────────────────────────

    fun requestScanForLocalModels() {
        // On Android 13+ no storage permission is needed for scanning.
        // On Android 12 and below (API <= 32), READ_EXTERNAL_STORAGE is required.
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
            if (!permissionService.hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
                _storagePermissionRequired.value = true
                return
            }
        }
        scanForLocalModels()
    }

    fun scanForLocalModels() {
        _modelState.update { it.copy(isScanning = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val scanned = runCatching { ModelScout.scan(appContext) }.getOrElse { emptyList() }
            val existingFileNames = ModelRegistry.getAll().map { it.fileName }.toSet()
            val newScannedIds = mutableSetOf<String>()
            for (s in scanned) {
                if (existingFileNames.contains(s.fileName)) continue
                val file = File(s.path)
                val catalogMeta = ModelCatalog.entries.find { it.fileName == s.fileName }
                val model = createModelFromFile(file, ModelSource.LOCAL_FILE, "custom", catalogMeta)
                ModelRegistry.addModel(model)
                newScannedIds.add(model.id)
            }
            if (newScannedIds.isNotEmpty()) persistRegistry()
            val allScannedIds = _modelState.value.scannedModelIds + newScannedIds
            persistScannedIds(allScannedIds)
            _modelState.update {
                it.copy(isScanning = false, availableModels = ModelManager.getAllModels(), scannedModelIds = allScannedIds)
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private suspend fun currentSessionOrCreate(): String {
        val current = _currentSessionId.value
        if (current.isNotBlank()) return current
        val session = memoryManager.createSession()
        _currentSessionId.value = session.id
        preferences.edit().putString(KEY_SESSION_ID, session.id).apply()
        refreshSessions()
        return session.id
    }

    private suspend fun refreshSessions() {
        _sessions.value = memoryManager.getAllSessions()
    }

    private fun loadModel(model: ModelInfo) {
        val file = File(model.path)
        val validation = ModelValidator.validate(file, appContext, model.ramRequiredMb)
        if (validation !is ValidationResult.Valid) {
            val (msg, type) = validationMessage(validation)
            _modelState.update {
                it.copy(selectedModelId = model.id, selectedModelName = model.name,
                    selectedModelPath = model.path, selectedModelSize = model.size,
                    isModelLoading = false, isModelReady = false,
                    loadError = msg, loadErrorType = type, loadProgress = -1,
                    availableModels = ModelManager.getAllModels())
            }
            return
        }
        ModelManager.unload()
        val loadStart = System.currentTimeMillis()
        _modelState.update {
            it.copy(selectedModelId = model.id, selectedModelName = model.name,
                selectedModelPath = model.path, selectedModelSize = model.size,
                isModelLoading = true, isModelReady = false,
                loadError = null, loadErrorType = LoadErrorType.NONE, loadProgress = 0,
                downloadedModelAvailable = downloadManager.isModelDownloaded(),
                downloadedModelPath = downloadManager.getModelFile().absolutePath,
                availableModels = ModelManager.getAllModels())
        }
        ModelManager.load(model, onProgress = { percent ->
            _modelState.update { it.copy(loadProgress = percent) }
        }) { success ->
            if (success) {
                val loadMs = System.currentTimeMillis() - loadStart
                perfPrefs.edit().putLong("last_model_load_ms", loadMs).apply()
                AnalyticsService.modelLoaded(model.name, loadMs)
                preferences.edit().putString(KEY_MODEL_ID, model.id).putString(KEY_MODEL_PATH, model.path).apply()
                persistRegistry()
            }
            _modelState.update {
                it.copy(isModelLoading = false, isModelReady = success,
                    loadError = if (success) null else "فشل تحميل النموذج في محرك الاستنتاج",
                    loadErrorType = if (success) LoadErrorType.NONE else LoadErrorType.LOAD_FAILED,
                    loadProgress = -1, availableModels = ModelManager.getAllModels())
            }
        }
    }

    private fun createInitialModelState(): ModelUiState {
        restoreRegistry()
        syncDownloadedModelAvailability()
        val savedId    = preferences.getString(KEY_MODEL_ID, "").orEmpty()
        val savedPath  = preferences.getString(KEY_MODEL_PATH, "").orEmpty()
        val savedModel = ModelRegistry.getById(savedId)
            ?: ModelRegistry.getAll().firstOrNull { it.path == savedPath }
            ?: File(savedPath).takeIf { it.exists() && it.length() > 0 }
                ?.let { f -> createModelFromFile(f, ModelSource.LOCAL_FILE, "custom", ModelCatalog.entries.find { it.fileName == f.name }) }
        if (savedModel != null) { ModelRegistry.addModel(savedModel); persistRegistry() }
        val downloadedFile = downloadManager.getModelFile()
        return ModelUiState(
            selectedModelId = savedModel?.id.orEmpty(),
            selectedModelName = savedModel?.name ?: "No model",
            selectedModelPath = savedModel?.path.orEmpty(),
            selectedModelSize = savedModel?.size ?: 0L,
            isModelReady = false,
            downloadedModelAvailable = downloadManager.isModelDownloaded(),
            downloadedModelPath = downloadedFile.absolutePath,
            availableModels = ModelManager.getAllModels(),
            catalogModels = ModelCatalog.entries,
            scannedModelIds = restoreScannedIds()
        )
    }

    private fun restoreRegistry() {
        val json = preferences.getString(KEY_MODEL_REGISTRY, null) ?: return
        val type = object : TypeToken<List<ModelInfo>>() {}.type
        val restored = runCatching { gson.fromJson<List<ModelInfo>>(json, type) }.getOrNull().orEmpty()
        ModelRegistry.replaceAll(restored.filter { File(it.path).exists() })
    }

    private fun persistRegistry() {
        preferences.edit().putString(KEY_MODEL_REGISTRY, gson.toJson(ModelManager.getAllModels())).apply()
    }

    private fun syncDownloadedModelAvailability() {
        val downloadedFile = downloadManager.getModelFile()
        if (downloadManager.isModelDownloaded()) {
            val catalogMeta = ModelCatalog.entries.find { it.fileName == downloadedFile.name }
            ModelRegistry.addModel(createModelFromFile(downloadedFile, ModelSource.DOWNLOADED, "chat", catalogMeta))
            persistRegistry()
        }
    }

    private fun refreshModelList() {
        _modelState.update { it.copy(availableModels = ModelManager.getAllModels()) }
    }

    private fun createModelFromFile(
        file: File, source: ModelSource, type: String, catalogMeta: CatalogEntry? = null
    ): ModelInfo {
        val matched = catalogMeta ?: ModelCatalog.entries.find { it.fileName == file.name }
        return ModelInfo(
            name          = matched?.name ?: file.nameWithoutExtension,
            fileName      = file.name,
            size          = file.length(),
            quantization  = matched?.quantization ?: detectQuantization(file.name),
            path          = file.absolutePath,
            source        = source,
            id            = file.absolutePath,
            type          = matched?.type ?: when (type.lowercase()) {
                "gemma"   -> ModelType.GEMMA
                "mistral" -> ModelType.MISTRAL
                "llama"   -> ModelType.LLAMA
                else      -> ModelType.inferFromFileName(file.name)
            },
            isLocal       = true,
            ramRequiredMb = matched?.ramRequiredMb ?: 0,
            contextSize   = matched?.contextSize ?: 4096
        )
    }

    private fun detectQuantization(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            "q4_k_m" in lower -> "Q4_K_M"; "q4" in lower -> "4-bit"
            "q5" in lower -> "5-bit"; "q8" in lower -> "8-bit"; else -> "GGUF"
        }
    }

    private fun validationMessage(result: ValidationResult): Pair<String, LoadErrorType> = when (result) {
        is ValidationResult.FileNotFound    -> "الملف غير موجود أو تم حذفه" to LoadErrorType.FILE_NOT_FOUND
        is ValidationResult.InvalidFormat   -> "صيغة الملف غير صحيحة — يجب أن يكون ملف GGUF حقيقي" to LoadErrorType.INVALID_FORMAT
        is ValidationResult.TooSmall        -> "حجم الملف صغير جداً — قد يكون التحميل غير مكتمل" to LoadErrorType.TOO_SMALL
        is ValidationResult.InsufficientRam -> "الذاكرة غير كافية (مطلوب ${result.requiredMb} MB، متاح ${result.availableMb} MB)" to LoadErrorType.INSUFFICIENT_RAM
        else -> "خطأ غير متوقع" to LoadErrorType.LOAD_FAILED
    }

    private fun persistScannedIds(ids: Set<String>) {
        preferences.edit().putString(KEY_SCANNED_IDS, ids.joinToString("|")).apply()
    }

    private fun restoreScannedIds(): Set<String> {
        val raw = preferences.getString(KEY_SCANNED_IDS, "") ?: ""
        return if (raw.isBlank()) emptySet() else raw.split("|").toSet()
    }

    private companion object {
        const val KEY_MODEL_ID       = "selected_model_id"
        const val KEY_MODEL_PATH     = "selected_model_path"
        const val KEY_MODEL_REGISTRY = "model_registry_json"
        const val KEY_SCANNED_IDS    = "scanned_model_ids"
        const val KEY_SESSION_ID     = "current_session_id"
    }
}
