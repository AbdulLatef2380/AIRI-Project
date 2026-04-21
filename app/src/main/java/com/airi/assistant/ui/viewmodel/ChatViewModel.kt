package com.airi.assistant.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.airi.assistant.domain.monetization.PaywallTriggerEngine.UpsellLevel
import com.airi.assistant.domain.monetization.PricingConfig
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
import com.airi.assistant.ai.QueryClassifier
import com.airi.assistant.ai.QueryType
import com.airi.assistant.ai.ResponseOptimizer
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

data class DebugState(
    val lastQueryType: String     = "-",
    val lastModelName: String     = "-",
    val lastFirstTokenMs: Long    = -1L,
    val lastTotalLatencyMs: Long  = -1L,
    val p50LatencyMs: Long        = -1L,
    val p90LatencyMs: Long        = -1L,
    val lastTokensPerSec: Float   = 0f,
    val lastIsFastPath: Boolean   = false,
    val lastWasCut: Boolean       = false,
    val currentVoiceState: String = "IDLE"
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

    // ── AI Power Level — decreases with free usage, exposed to UI ────────────

    private val _powerLevel = MutableStateFlow(subscriptionManager.getPowerLevel())
    val powerLevel: StateFlow<Float> = _powerLevel.asStateFlow()

    private fun refreshPowerLevel() {
        val level = subscriptionManager.getPowerLevel()
        _powerLevel.value = level
        AnalyticsService.powerLevelChanged(level)
    }

    // ── Smart reply suggestions ───────────────────────────────────────────────

    private val _smartReplies = MutableStateFlow<List<String>>(emptyList())
    val smartReplies: StateFlow<List<String>> = _smartReplies.asStateFlow()

    fun clearSmartReplies() { _smartReplies.value = emptyList() }

    // ── Debug state (verification layer) ─────────────────────────────────────

    private val _debugState = MutableStateFlow(DebugState())
    val debugState: StateFlow<DebugState> = _debugState.asStateFlow()

    private val _systemIntegrityFailed = MutableStateFlow(false)
    val systemIntegrityFailed: StateFlow<Boolean> = _systemIntegrityFailed.asStateFlow()

    fun clearSystemIntegrityFailed() { _systemIntegrityFailed.value = false }

    fun updateVoiceState(stateName: String) {
        com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", stateName)
        com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = stateName) }
        _debugState.update { it.copy(currentVoiceState = stateName) }
    }

    fun runDiagnostics() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val report = com.airi.assistant.core.debug.Diagnostics.runDiagnostics()
            if (!report.allPassed) {
                _systemIntegrityFailed.value = true
            }
        }
    }

    // ── Generation cancellation ───────────────────────────────────────────────

    private val _isCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    fun cancelGeneration() {
        if (_agentState.value.isWorking) {
            _isCancelled.set(true)
            llamaManager.cancelStream()
            Log.d("AIRI_SPEED", "cancelGeneration: user triggered")
            com.airi.assistant.domain.logging.ProofLogger.streamCancelled(
                byUser = true,
                tokensStreamed = 0
            )
        }
    }

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

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ModelDownloadService.ACTION_DOWNLOAD_COMPLETE) return
            val fileName = intent.getStringExtra(ModelDownloadService.EXTRA_RESULT_FILENAME) ?: return
            val filePath = intent.getStringExtra(ModelDownloadService.EXTRA_RESULT_PATH) ?: return
            Log.i("AIRI_PROOF", "DOWNLOAD_BROADCAST_RECEIVED fileName=$fileName path=$filePath")
            viewModelScope.launch(Dispatchers.IO) {
                val file = File(filePath)
                if (file.exists() && file.length() > 50_000_000L) {
                    val catalogMeta = ModelCatalog.entries.find { it.fileName == fileName }
                    val model = createModelFromFile(file, ModelSource.DOWNLOADED, "chat", catalogMeta)
                    ModelRegistry.addModel(model)
                    persistRegistry()
                    withContext(Dispatchers.Main) {
                        refreshModelList()
                        _modelState.update {
                            it.copy(downloadedModelAvailable = true, downloadedModelPath = filePath)
                        }
                    }
                }
            }
        }
    }

    init {
        ModelManager.setLoader(ModelLoader(llamaManager))
        val filter = IntentFilter(ModelDownloadService.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(downloadCompleteReceiver, filter)
        }
        loadInitialSession()
        val savedModel = ModelRegistry.getById(_modelState.value.selectedModelId)
        if (savedModel != null && File(savedModel.path).exists()) {
            loadModel(savedModel)
        }
        refreshRecommendedModels()
        runDiagnostics()
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { appContext.unregisterReceiver(downloadCompleteReceiver) }
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

        // ── Intent classification (before any async work) ─────────────────────
        val queryType = QueryClassifier.classifyQuery(trimmedInput)
        val wordCount = trimmedInput.split(Regex("\\s+")).size
        Log.d("AIRI_INTENT", "type=${queryType.name} input_words=$wordCount")
        com.airi.assistant.domain.logging.ProofLogger.classificationResult(
            input = trimmedInput, queryType = queryType.name, wordCount = wordCount
        )
        com.airi.assistant.core.analytics.ProofLogger.log("CLASSIFICATION", queryType.name)
        com.airi.assistant.core.debug.RuntimeStore.update { copy(lastQueryType = queryType.name) }
        _debugState.update { it.copy(lastQueryType = queryType.name) }

        // ── Subscription gate: enforce daily message quota (PolicyEngine) ────────
        val policyResult = com.airi.assistant.domain.policy.PolicyEngine.checkSubscriptionMessage(subscriptionManager)
        if (policyResult is com.airi.assistant.domain.policy.PolicyEngine.PolicyResult.Denied) {
            if (ReferralManager.consumeBonusUsage()) {
                AnalyticsService.funnelStep("bonus_message_used")
            } else {
                val summary = subscriptionManager.getUsageSummary()
                PaywallTriggerEngine.onLimitReached("daily_messages", summary.messagesUsed, summary.messagesLimit)
                _messages.update {
                    it + ChatMessage(
                        PaywallTriggerEngine.getPaywallMessage(PaywallTriggerEngine.TriggerReason.LimitReached),
                        isUser = false
                    )
                }
                refreshPowerLevel()
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
            val wasEmpty   = _messages.value.isEmpty()
            val rawHistory = memoryManager.loadSession(sessionId)
            val history    = ResponseOptimizer.smartTrim(rawHistory)
            Log.d("AIRI_TRIM", "before=${rawHistory.size} after=${history.size}")
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
            val paywallLevel = PaywallTriggerEngine.onMessageSent(subscriptionManager.isPremium())
            val triggerPaywallAfterSend = paywallLevel != UpsellLevel.NONE
            refreshPowerLevel()
            _messages.update { it + ChatMessage(trimmedInput, true, userMessage.id) }

            // ── Phase 1 & 5 — Soft limit: degrade quality + add delay for free users ──
            val softPhase = subscriptionManager.getSoftLimitPhase()
            if (softPhase >= 1 && !subscriptionManager.isPremium()) {
                val delayMs = PricingConfig.SOFT_LIMIT_DELAY_MS
                Log.d("AIRI_MONET", "softLimit phase=$softPhase delay=${delayMs}ms")
                AnalyticsService.softLimitApplied(softPhase, if (softPhase >= 2) PricingConfig.NEAR_LIMIT_TOKEN_FACTOR else PricingConfig.SOFT_LIMIT_TOKEN_FACTOR)
                delay(delayMs)
            }

            // ── Fast response shortcut — bypass model inference for known replies ──
            val startTimeMs = System.currentTimeMillis()
            val fastHit = ResponseOptimizer.tryFastResponse(trimmedInput)
            if (fastHit != null) {
                val fastLatency = System.currentTimeMillis() - startTimeMs
                Log.d("AIRI_FAST", "hit=true response_len=${fastHit.length}")
                com.airi.assistant.domain.logging.ProofLogger.fastPathUsed(trimmedInput)
                com.airi.assistant.core.analytics.ProofLogger.log("FAST_PATH", "true latency=${fastLatency}ms")
                com.airi.assistant.domain.verification.VerificationTracker.record(
                    com.airi.assistant.domain.verification.VerificationEvent(
                        type      = "FAST",
                        latencyMs = fastLatency,
                        tokens    = fastHit.length / 5,
                        wasCut    = false,
                        queryType = queryType.name
                    )
                )
                val fastP50 = com.airi.assistant.domain.verification.VerificationTracker.p50LatencyMs()
                val fastP90 = com.airi.assistant.domain.verification.VerificationTracker.p90LatencyMs()
                com.airi.assistant.core.debug.RuntimeStore.update {
                    copy(fastPath = true, wasCut = false, totalLatencyMs = fastLatency,
                         lastQueryType = queryType.name, p50LatencyMs = fastP50, p90LatencyMs = fastP90)
                }
                _debugState.update { it.copy(
                    lastIsFastPath      = true,
                    lastWasCut          = false,
                    lastFirstTokenMs    = 0L,
                    lastTotalLatencyMs  = fastLatency,
                    p50LatencyMs        = fastP50,
                    p90LatencyMs        = fastP90,
                    lastTokensPerSec    = 0f,
                    lastModelName       = "fast-table"
                )}
                val fastMsg = memoryManager.recordChatMessage(sessionId, "assistant", fastHit)
                _messages.update { it + ChatMessage(fastHit, isUser = false, id = fastMsg.id) }
                _smartReplies.value = ResponseOptimizer.generateSuggestions(fastHit)
                _streamingText.value = ""
                _agentState.value = AgentState()
                refreshSessions()
                return@launch
            }
            Log.d("AIRI_FAST", "hit=false")
            _debugState.update { it.copy(lastIsFastPath = false) }
            _smartReplies.value = emptyList()
            _isCancelled.set(false)

            val previewHint = when (queryType) {
                QueryType.SIMPLE     -> "Thinking..."
                QueryType.ANALYTICAL -> "Analyzing..."
                QueryType.ACTION     -> "Preparing..."
                QueryType.CREATIVE   -> "Imagining..."
                QueryType.UNKNOWN    -> "Thinking..."
            }
            _agentState.value = AgentState(isWorking = true, currentAction = previewHint)
            _streamingText.value = previewHint

            val allThinkingStages = setOf(
                "Thinking...", "Analyzing...", "Preparing...", "Imagining...",
                "Planning...", "Generating...", "Reasoning...", "Creating..."
            )

            var thinkingJob: kotlinx.coroutines.Job? = viewModelScope.launch {
                kotlinx.coroutines.delay(800)
                if (_streamingText.value in allThinkingStages) {
                    val stage2 = when (queryType) {
                        QueryType.ANALYTICAL -> "Reasoning..."
                        QueryType.CREATIVE   -> "Creating..."
                        QueryType.ACTION     -> "Planning..."
                        else                 -> "Generating..."
                    }
                    _streamingText.value = stage2
                    _agentState.update { it.copy(currentAction = stage2) }
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
                        subscriptionManager.recordConsecutiveSuccess()
                        // Soft paywall after first agent execution (non-blocking)
                        val agentLevel = PaywallTriggerEngine.onAgentExecuted(subscriptionManager.isPremium())
                        if (agentLevel != UpsellLevel.NONE) {
                            _upgradePrompt.value = UpgradePrompt(
                                message = PaywallTriggerEngine.getPaywallMessage(PaywallTriggerEngine.TriggerReason.FirstAgentExecution),
                                source = PaywallTriggerEngine.TriggerReason.FirstAgentExecution.source
                            )
                        }
                    } else {
                        subscriptionManager.resetConsecutiveSuccesses()
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
                    refreshPowerLevel()
                    // Fire soft paywall (message threshold) after agent response is shown
                    if (triggerPaywallAfterSend && !_paywallTrigger.value) {
                        _upgradePrompt.value = UpgradePrompt(
                            message = PaywallTriggerEngine.getPaywallMessage(PaywallTriggerEngine.TriggerReason.MessageThreshold),
                            source = PaywallTriggerEngine.TriggerReason.MessageThreshold.source
                        )
                    }
                    return@launch
                }

                // isLlmFallback == true — proceed to local LLM
            }

            val requestStart = System.currentTimeMillis()
            val streamStart  = requestStart
            var tokenCount   = 0
            var firstTokenReceived = false
            var partialCutText = ""
            llamaManager.setHistory(history)
            val deviceWeak = isDeviceWeak()
            val remote = RemoteModelRegistry.getActive()
            val systemPrompt = buildGenerationSystemPrompt(trimmedInput, perfMode, queryType)
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

            // ── Dynamic generation control — adjust per intent type ───────────────
            val recentP90 = com.airi.assistant.domain.verification.VerificationTracker.p90LatencyMs()
            val genConfig = ResponseOptimizer.adaptiveGeneration(
                queryType = queryType,
                ramCappedMaxTokens = adaptiveMaxTokens,
                recentP90Ms = recentP90,
                isPremium = subscriptionManager.isPremium()
            )
            // ── Input-size driven token cap — short inputs need short answers ─────
            val inputSizeCapped = ResponseOptimizer.inputSizeTokenCap(trimmedInput.length, genConfig.maxTokens)
            // ── Phase 1 — Soft token cap for free users in degradation zone ───────
            val finalMaxTokens = when {
                !subscriptionManager.isPremium() && softPhase >= 2 ->
                    (inputSizeCapped * PricingConfig.NEAR_LIMIT_TOKEN_FACTOR).toInt().coerceAtLeast(64)
                !subscriptionManager.isPremium() && softPhase >= 1 ->
                    (inputSizeCapped * PricingConfig.SOFT_LIMIT_TOKEN_FACTOR).toInt().coerceAtLeast(96)
                else -> inputSizeCapped
            }
            if (softPhase >= 1 && !subscriptionManager.isPremium()) {
                Log.d("AIRI_MONET", "softTokenCap phase=$softPhase original=$inputSizeCapped capped=$finalMaxTokens")
            }
            Log.d("AIRI_SPEED", "input_len=${trimmedInput.length} gen_tokens=${genConfig.maxTokens} final_cap=$finalMaxTokens")
            Log.d("AIRI_GEN", "mode=${queryType.name} tokens=$finalMaxTokens temp=${genConfig.temperature} model=${_modelState.value.selectedModelName} input_len=${trimmedInput.length}")
            com.airi.assistant.domain.logging.ProofLogger.streamStarted(
                queryType = queryType.name,
                model     = _modelState.value.selectedModelName,
                tokens    = finalMaxTokens
            )
            _debugState.update { it.copy(
                lastModelName  = _modelState.value.selectedModelName,
                lastWasCut     = false
            )}
            val finish: suspend (String, Long, Int) -> Unit = { fullResponse, elapsedMs, tokens ->
                recordGenerationStats(elapsedMs, tokens)
                val tps      = if (elapsedMs > 0) tokens * 1000f / elapsedMs.coerceAtLeast(1) else 0f
                val wasCutNow = _isCancelled.get()
                com.airi.assistant.core.analytics.ProofLogger.log(
                    "COMPLETE", "latency=${elapsedMs}ms tokens=$tokens tps=%.1f cut=$wasCutNow".format(tps)
                )
                com.airi.assistant.domain.verification.VerificationTracker.record(
                    com.airi.assistant.domain.verification.VerificationEvent(
                        type      = "LLM",
                        latencyMs = elapsedMs,
                        tokens    = tokens,
                        wasCut    = wasCutNow,
                        queryType = queryType.name
                    )
                )
                val p50 = com.airi.assistant.domain.verification.VerificationTracker.p50LatencyMs()
                val p90 = com.airi.assistant.domain.verification.VerificationTracker.p90LatencyMs()
                com.airi.assistant.core.debug.RuntimeStore.update {
                    copy(
                        totalLatencyMs = elapsedMs,
                        p50LatencyMs = p50,
                        p90LatencyMs = p90,
                        tokensPerSecond = tps,
                        fastPath = false,
                        wasCut   = wasCutNow
                    )
                }
                _debugState.update { it.copy(
                    lastTotalLatencyMs = elapsedMs,
                    p50LatencyMs       = p50,
                    p90LatencyMs       = p90,
                    lastTokensPerSec   = tps
                )}
                AnalyticsService.responseGenerated(elapsedMs, tps, _modelState.value.selectedModelName, false)
                if (fullResponse.isNotBlank()) {
                    val wasToolCall = handleToolIfNeeded(fullResponse, sessionId)
                    if (!wasToolCall) {
                        val assistantMessage = memoryManager.recordChatMessage(sessionId, "assistant", fullResponse)
                        _messages.update { it + ChatMessage(fullResponse, isUser = false, assistantMessage.id) }
                    }
                    refreshSessions()
                    refreshPowerLevel()

                    // ── Phase 2 — success_moment paywall (after N consecutive good responses) ─
                    subscriptionManager.recordConsecutiveSuccess()
                    val successes = subscriptionManager.getConsecutiveSuccesses()
                    val successLevel = PaywallTriggerEngine.onSuccessfulResponse(successes, subscriptionManager.isPremium())
                    if (successLevel != UpsellLevel.NONE && !_paywallTrigger.value) {
                        _upgradePrompt.value = UpgradePrompt(
                            message = PaywallTriggerEngine.getPaywallMessage(PaywallTriggerEngine.TriggerReason.SuccessMoment),
                            source = PaywallTriggerEngine.TriggerReason.SuccessMoment.source
                        )
                    }

                    // ── Phase 5 — speed_upsell (slow response detected for free users) ───
                    val speedLevel = PaywallTriggerEngine.onSlowResponse(elapsedMs, subscriptionManager.isPremium())
                    if (speedLevel != UpsellLevel.NONE && !_paywallTrigger.value) {
                        _upgradePrompt.value = UpgradePrompt(
                            message = PaywallTriggerEngine.getPaywallMessage(PaywallTriggerEngine.TriggerReason.SpeedUpsell),
                            source = PaywallTriggerEngine.TriggerReason.SpeedUpsell.source
                        )
                    }

                    val cutLevel = if (wasCutNow) PaywallTriggerEngine.onResponseCut(subscriptionManager.isPremium()) else UpsellLevel.NONE
                    if (cutLevel != UpsellLevel.NONE && !_paywallTrigger.value) {
                        _upgradePrompt.value = UpgradePrompt(
                            message = PaywallTriggerEngine.getPaywallMessage(PaywallTriggerEngine.TriggerReason.ResponseCut),
                            source = PaywallTriggerEngine.TriggerReason.ResponseCut.source
                        )
                    }

                    val powerLevel = PaywallTriggerEngine.onPowerUser(PaywallTriggerEngine.getTotalMessages(), subscriptionManager.isPremium())
                    if (powerLevel != UpsellLevel.NONE && !_paywallTrigger.value) {
                        _upgradePrompt.value = UpgradePrompt(
                            message = PaywallTriggerEngine.getPaywallMessage(PaywallTriggerEngine.TriggerReason.PowerUser),
                            source = PaywallTriggerEngine.TriggerReason.PowerUser.source
                        )
                    }

                    // ── Message threshold upsell ─────────────────────────────────────────
                    if (triggerPaywallAfterSend && !_paywallTrigger.value && successLevel == UpsellLevel.NONE) {
                        _upgradePrompt.value = UpgradePrompt(
                            message = PaywallTriggerEngine.getPaywallMessage(PaywallTriggerEngine.TriggerReason.MessageThreshold),
                            source = PaywallTriggerEngine.TriggerReason.MessageThreshold.source
                        )
                    }
                }
                _smartReplies.value = ResponseOptimizer.generateSuggestions(fullResponse)
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
                    maxTokens = finalMaxTokens,
                    temperature = genConfig.temperature,
                    repeatPenalty = repeatPenalty,
                    topK = topK,
                    topP = topP,
                    minP = minP,
                    presencePenalty = presencePenalty,
                    frequencyPenalty = frequencyPenalty,
                    // First-token deadline (covers slow CPU prompt decode on phones).
                    // Post-first-token inactivity timeout is owned by LlamaManager.
                    timeoutMs = 90_000L,
                    onToken = { tokenBatch ->
                        thinkingJob?.cancel()
                        thinkingJob = null
                        if (!firstTokenReceived) {
                            firstTokenReceived = true
                            val firstTokenMs = System.currentTimeMillis() - requestStart
                            Log.d("AIRI_SPEED", "first_token=${firstTokenMs}ms query=${queryType.name}")
                            com.airi.assistant.domain.logging.ProofLogger.firstToken(firstTokenMs, queryType.name)
                            com.airi.assistant.core.analytics.ProofLogger.log("FIRST_TOKEN", "$firstTokenMs ms")
                            com.airi.assistant.core.debug.RuntimeStore.update { copy(firstTokenMs = firstTokenMs) }
                            _debugState.update { it.copy(lastFirstTokenMs = firstTokenMs) }
                        }
                        tokenCount += tokenBatch.length / 4 + 1
                        _streamingText.update { current ->
                            if (current in allThinkingStages) tokenBatch else current + tokenBatch
                        }
                        // Partial cut: if running too long with enough tokens, stop early
                        val elapsed = System.currentTimeMillis() - streamStart
                        if (ResponseOptimizer.shouldSemanticCut(_streamingText.value, elapsed, tokenCount, queryType, subscriptionManager.isPremium()) && !_isCancelled.get()) {
                            val cutResult = ResponseOptimizer.semanticCut(_streamingText.value)
                            partialCutText = cutResult.text.ifBlank { _streamingText.value }
                            Log.d("AIRI_SPEED", "cut_triggered=true tokens_streamed=$tokenCount total_latency=${elapsed}ms")
                            com.airi.assistant.domain.logging.ProofLogger.cutTriggered(tokenCount, elapsed)
                            com.airi.assistant.core.analytics.ProofLogger.log("CUT", "triggered tokens=$tokenCount elapsed=${elapsed}ms")
                            com.airi.assistant.core.debug.RuntimeStore.update { copy(wasCut = true) }
                            _debugState.update { it.copy(lastWasCut = true) }
                            _isCancelled.set(true)
                            llamaManager.cancelStream()
                        }
                    },
                    onComplete = { fullResponse ->
                        val totalLatency = System.currentTimeMillis() - requestStart
                        Log.d("AIRI_SPEED", "tokens_streamed=$tokenCount total_latency=${totalLatency}ms first_token=$firstTokenReceived cut=${_isCancelled.get()}")
                        val responseToSave = if (_isCancelled.get() && partialCutText.isNotBlank()) {
                            partialCutText
                        } else {
                            fullResponse
                        }
                        _isCancelled.set(false)
                        viewModelScope.launch {
                            finish(responseToSave, totalLatency, tokenCount)
                        }
                    },
                    onError = {
                        viewModelScope.launch {
                            thinkingJob?.cancel()
                            thinkingJob = null
                            _isCancelled.set(false)
                            val fallbackRemote = RemoteModelRegistry.getActive()
                            if (fallbackRemote != null) {
                                _streamingText.value = "Thinking..."
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
        perfMode: PerformanceMode = _performanceMode.value,
        queryType: QueryType = QueryType.UNKNOWN
    ): String = promptService.buildSystemPrompt(
        modePrompt      = _agentMode.value.prompt,
        responseStyle   = _responseStyle.value,
        customPrompt    = _systemPrompt.value.trim(),
        performanceMode = perfMode,
        queryType       = queryType
    )

    private fun buildGenerationSystemPrompt(
        input: String,
        perfMode: PerformanceMode,
        queryType: QueryType = QueryType.UNKNOWN
    ): String {
        return if (promptService.isSimpleQuery(input) || queryType == QueryType.SIMPLE) {
            promptService.buildSimpleSystemPrompt(_agentMode.value.prompt, perfMode.maxTokens)
        } else {
            buildEffectiveSystemPrompt(perfMode, queryType)
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
                val copy = withContext(Dispatchers.IO) { FileUtils.copyModelFromSaf(appContext, uri) }
                val file = copy.file
                Log.i("AIRI_MODEL", "IMPORT SUCCESS path=${file.absolutePath} sourceBytes=${copy.sourceSizeBytes} copiedBytes=${copy.copiedBytes}")
                com.airi.assistant.domain.verification.VerificationTracker.recordCheck("MODEL_IMPORT", true, "path=${file.absolutePath} copied=${copy.copiedBytes}")
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
                Log.e("AIRI_MODEL", "IMPORT FAILED: ${e.message}", e)
                com.airi.assistant.domain.verification.VerificationTracker.recordCheck("MODEL_IMPORT", false, e.message ?: "unknown")
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
        Log.i("AIRI_PROOF", "MODEL_ACTIVATED name=${model.name} id=${model.id} type=${model.type.label} path=${model.path}")
        preferences.edit().putString(KEY_MODEL_ID, model.id).putString(KEY_MODEL_PATH, model.path).apply()
        loadModel(model)
    }

    // ── Chat history import (mirror of export) ────────────────────────────────
    /**
     * Imports a previously-exported JSON chat file into the *current* session.
     * Returns the number of messages successfully ingested. The UI refreshes
     * automatically because messages are appended through MemoryManager and
     * then re-broadcast via the existing _messages flow.
     */
    fun importChatJson(uri: Uri, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                com.airi.assistant.util.ChatImporter.importFromUri(appContext, uri)
            }
            if (imported.isEmpty()) {
                onResult(0)
                return@launch
            }
            // Ensure we have a session to attach the messages to.
            if (_currentSessionId.value.isBlank()) {
                Log.w("AIRI_STORAGE", "importChatJson: no active session — creating one")
                createNewChat()
            }
            val targetSession = _currentSessionId.value
            if (targetSession.isBlank()) {
                onResult(0)
                return@launch
            }
            var added = 0
            val newUiMessages = mutableListOf<ChatMessage>()
            for (m in imported) {
                runCatching {
                    val saved = memoryManager.recordChatMessage(targetSession, m.role, m.content)
                    newUiMessages += ChatMessage(
                        text = m.content,
                        isUser = (m.role == "user"),
                        id = saved.id
                    )
                    added++
                }.onFailure {
                    Log.w("AIRI_STORAGE", "importChatJson: skipped row reason=${it.message}")
                }
            }
            if (newUiMessages.isNotEmpty()) {
                _messages.update { it + newUiMessages }
            }
            refreshSessions()
            Log.i("AIRI_PROOF", "CHAT_IMPORTED count=$added session=$targetSession")
            onResult(added)
        }
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
            putExtra(ModelDownloadService.EXTRA_EXPECTED_SIZE_BYTES, entry.sizeBytes)
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
        if (file.length() < (entry.sizeBytes * 0.97).toLong()) {
            val reason = "Downloaded model incomplete expected=${entry.sizeBytes} actual=${file.length()}"
            Log.e("AIRI_MODEL_DOWNLOAD", "FAILED reason=$reason")
            com.airi.assistant.domain.verification.VerificationTracker.recordCheck("DOWNLOAD", false, reason)
            _modelState.update { it.copy(loadError = reason, loadErrorType = LoadErrorType.TOO_SMALL) }
            return
        }
        com.airi.assistant.domain.verification.VerificationTracker.recordCheck("DOWNLOAD", true, "file=${file.absolutePath} size=${file.length()}")
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
        com.airi.assistant.domain.verification.VerificationTracker.recordCheck("MEMORY", true, "model=${model.name} requiredMb=${model.ramRequiredMb}")
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
                Log.i("AIRI_MODEL", "LOAD SUCCESS path=${model.path} model=${model.name} loadMs=$loadMs")
                Log.i("AIRI_PROOF", "MODEL_LOAD_SUCCESS name=${model.name} type=${model.type.label} loadMs=${loadMs}ms path=${model.path}")
                com.airi.assistant.domain.verification.VerificationTracker.recordCheck("MODEL_LOAD", true, "path=${model.path} loadMs=$loadMs")
            } else {
                val failure = llamaManager.getLastLoadFailure() ?: "native inference engine returned failure"
                Log.e("AIRI_MODEL", "LOAD FAILED: $failure")
                Log.e("AIRI_PROOF", "MODEL_LOAD_FAILURE name=${model.name} type=${model.type.label} reason=$failure path=${model.path}")
                com.airi.assistant.domain.verification.VerificationTracker.recordCheck("MODEL_LOAD", false, failure)
            }
            _modelState.update {
                it.copy(isModelLoading = false, isModelReady = success,
                    loadError = if (success) null else "فشل تحميل النموذج في محرك الاستنتاج: ${llamaManager.getLastLoadFailure() ?: "سبب غير معروف"}",
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
        val modelsDir = runCatching { downloadManager.getModelsDir() }.getOrNull() ?: return
        val ggufFiles = modelsDir.listFiles()
            ?.filter { it.isFile && it.name.lowercase().endsWith(".gguf") && it.length() > 50_000_000L }
            ?: emptyList()
        Log.i("AIRI_PROOF", "SYNC_MODELS_SCAN dir=${modelsDir.absolutePath} ggufCount=${ggufFiles.size}")
        var changed = false
        for (file in ggufFiles) {
            val catalogMeta = ModelCatalog.entries.find { it.fileName == file.name }
            val model = createModelFromFile(file, ModelSource.DOWNLOADED, "chat", catalogMeta)
            if (ModelRegistry.getById(model.id) == null) {
                ModelRegistry.addModel(model)
                changed = true
                Log.i("AIRI_PROOF", "MODEL_REGISTERED name=${model.name} source=DOWNLOADED_SCAN path=${file.absolutePath}")
            }
        }
        if (changed) persistRegistry()
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
