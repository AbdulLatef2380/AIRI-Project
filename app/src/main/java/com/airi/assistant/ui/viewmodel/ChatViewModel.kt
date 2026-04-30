package com.airi.assistant.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
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
import com.airi.assistant.ai.LlamaNative
import com.airi.assistant.ai.ModelCapabilities
import com.airi.assistant.ai.ModelCatalog
import com.airi.assistant.ai.VisionImage
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
    val traceId: String? = null,
    // Phase 1 crash fix: stable per-instance UUID for LazyColumn keys.
    // Previously the list used `msg.hashCode()` as the key. Two messages
    // with identical text+isUser produced an identical hashCode, which made
    // Compose throw `IllegalArgumentException: Key … was already used` and
    // tear down the screen — surfacing as a hard crash mid-conversation.
    // The Long `id` could ALSO collide on rapid-fire system messages built
    // without a Room id (default = currentTimeMillis). UUID is collision-
    // free by construction. This field is NOT persisted; it lives only for
    // the lifetime of the in-memory list.
    val uid: String = java.util.UUID.randomUUID().toString(),
    /**
     * Optional in-memory thumbnail reference for messages the user sent
     * with an attached image. UI-only — never persisted to Room (the
     * memory layer continues to store an `[image: name]` text marker).
     * `null` for every other message (default), so existing call sites
     * remain source-compatible.
     *
     * Format: either a `content://` URI from the picker, a `file://` URI,
     * or a raw `bitmap://<id>` sentinel for camera captures (the actual
     * Bitmap is held by the ViewModel's [transientCameraBitmaps] map).
     */
    val imageUri: String? = null
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
    val scannedModelIds: Set<String> = emptySet(),
    /**
     * Auto-detected capability profile of the *currently loaded* model.
     * Populated in the loadModel success branch via
     * [com.airi.assistant.ai.ModelCapabilities.detect]. Drives the
     * "Vision: yes/no" badge in the UI so the user can never mistake
     * "I attached an image" for "the model understood the image".
     */
    val capabilities: com.airi.assistant.ai.ModelCapabilities =
        com.airi.assistant.ai.ModelCapabilities.textOnlyFallback()
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

    /**
     * One-shot URI handed off from [sendMessageWithImage]'s text-fallback
     * branch (no vision model loaded) to the next [sendMessage] call so the
     * user's bubble can still render the picked thumbnail. Consumed exactly
     * once at the message-add site (line ~556) and then cleared. Always
     * `null` for plain text sends, so default behaviour is unchanged.
     */
    private var pendingImageUriForNextSend: String? = null
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    /**
     * True while the active generation has produced at least one token but
     * hasn't produced a new token for ≥5s (see LlamaManager.STALL_WARNING_MS).
     * The UI can surface a non-fatal hint while this is true. Auto-resets at
     * the start of the next generation.
     */
    private val _stallActive = MutableStateFlow(false)
    val stallActive: StateFlow<Boolean> = _stallActive.asStateFlow()
    fun clearStallHint() { _stallActive.value = false }

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
        // Hot-swap the native context (n_ctx + threads) without unloading the
        // model. KV is wiped; next message re-primes via reconcileSession.
        llamaManager.applyRuntimeMode(mode)
        Log.i("AIRI_PERF",
            "PerformanceMode -> ${mode.name} n_ctx=${mode.nCtx} threads=${mode.nThreads}")
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
            // Phase-1 instrumentation: surface the user-triggered cancel as a
            // first-class proof tag so we can confirm the Stop button → JNI
            // cancel pipeline end-to-end from logcat alone. The matching
            // GEN_CANCEL_HONORED tag is emitted by LlamaManager when the
            // native token-callback observes cancelRequested == true.
            Log.i("AIRI_PROOF", "GEN_CANCEL_REQUESTED source=user_button")
            com.airi.assistant.domain.logging.ProofLogger.streamCancelled(
                byUser = true,
                tokensStreamed = 0
            )
        } else {
            Log.i("AIRI_PROOF", "GEN_CANCEL_NOOP reason=not_generating")
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
            // Consume the one-shot attachment hand-off (if any) so the
            // user's bubble can render the picked thumbnail even on the
            // text-marker fallback path. Cleared in the same step so it
            // never leaks into a subsequent plain text send.
            val attachedForBubble = pendingImageUriForNextSend
            pendingImageUriForNextSend = null
            _messages.update {
                it + ChatMessage(
                    text = trimmedInput,
                    isUser = true,
                    id = userMessage.id,
                    imageUri = attachedForBubble
                )
            }

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
            val deviceWeak = isDeviceWeak()
            val remote = RemoteModelRegistry.getActive()
            val baseSystemPromptCore = buildGenerationSystemPrompt(trimmedInput, perfMode, queryType)

            // ── Phase 1.5 — semantic memory injection ────────────────────────────
            // The user demanded: "Connect semantic memory to the prompt". We do
            // it HERE, BEFORE PromptCompressor.compose, so the compressor's
            // existing 90% n_ctx budget cap is the ultimate guard against KV
            // overflow. We additionally cap the semantic block at SEMANTIC_PCT
            // (20%) of n_ctx — semantic memory must never starve the user
            // prompt or the recent-history slice.
            //
            // Budgeting math (anchored to PromptCompressor.estimateTokens =
            // chars/4): at nCtx=2048 → 20% = 410 tokens ≈ 1640 chars, which
            // comfortably fits 5 hits at ≤220 chars each.
            //
            // ⚠ HARD GUARD: if the embedding model isn't loaded the call
            // returns "" silently (with VECTOR_SEARCH_SKIPPED already logged)
            // so we never block the chat path on memory work.
            val baseSystemPrompt = run {
                val semanticBudgetTokens = (perfMode.nCtx * SEMANTIC_BUDGET_PCT) / 100
                val hits = memoryManager.semanticSearch(sessionId, trimmedInput, k = SEMANTIC_TOP_K)
                val (semBlock, _, _) = memoryManager.embeddingService.formatContextWithBudget(
                    hits = hits, maxTokens = semanticBudgetTokens
                )
                if (semBlock.isBlank()) baseSystemPromptCore
                else baseSystemPromptCore.trimEnd() + "\n\n" + semBlock
            }

            // ── Memory facts: harvest from THIS user message before composing ───
            // (so "my name is X" said in this turn participates in compression).
            val newFacts = com.airi.assistant.ai.prompt.MemoryExtractor.extract(trimmedInput)
            if (newFacts.isNotEmpty()) {
                com.airi.assistant.ai.prompt.MemoryStore
                    .mergeFacts(appContext, sessionId, newFacts)
                Log.i("AIRI_PROMPT_COMPRESS",
                    "EXTRACTED facts=${newFacts.size} session=$sessionId")
            }

            // ── Structured prompt compression ───────────────────────────────────
            // Replaces the old "send last 12 messages verbatim" path with the
            // 5-section envelope (System / Memory / Summary / Recent / User) plus
            // a hard 90% n_ctx token-budget cap. We DO NOT change inference logic
            // — only the prompt envelope and the trimmed history slice.
            val activeNCtx = perfMode.nCtx
            val compressed = com.airi.assistant.ai.prompt.PromptCompressor.compose(
                ctx               = appContext,
                baseSystemPrompt  = baseSystemPrompt,
                history           = history,
                userInput         = trimmedInput,
                nCtx              = activeNCtx,
                sessionId         = sessionId
            )
            llamaManager.setHistory(compressed.recentMessages)
            val systemPrompt = compressed.augmentedSystemPrompt
            // Remember whether we should re-summarize older turns AFTER this
            // generation completes (sequencing: never run during generation).
            val needsResummarize = compressed.shouldResummarize
            val olderToFold = if (needsResummarize)
                history.dropLast(compressed.recentMessages.size + compressed.stats.droppedRecent)
                else emptyList()
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
                    // Bumped to match LlamaManager.DEFAULT_FIRST_TOKEN_TIMEOUT_MS:
                    // a cold mmap prefill on a 2B Q4 model on a mid-range
                    // Snapdragon genuinely needs 60-90s. 60s caused false
                    // ERR_FIRST_TOKEN_TIMEOUT for legitimate slow loads.
                    timeoutMs = 120_000L,
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
                        // Partial cut: if running too long with enough tokens, stop early.
                        // Hard guard: NEVER cut before the first token has been emitted.
                        val elapsed = System.currentTimeMillis() - streamStart
                        if (firstTokenReceived &&
                            ResponseOptimizer.shouldSemanticCut(_streamingText.value, elapsed, tokenCount, queryType, subscriptionManager.isPremium()) &&
                            !_isCancelled.get()
                        ) {
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
                        // Generation finished — clear any active stall hint.
                        _stallActive.value = false
                        viewModelScope.launch {
                            finish(responseToSave, totalLatency, tokenCount)
                            // Sequencing: only NOW (after the user-facing
                            // generation has fully ended) is it safe to reuse
                            // the single global llama_context for the
                            // summarization pass. We MUST never run this
                            // concurrently with generateStream.
                            if (needsResummarize && olderToFold.isNotEmpty()) {
                                runCatching {
                                    val prevSummary = com.airi.assistant.ai.prompt.MemoryStore
                                        .getSummary(appContext, sessionId)
                                    com.airi.assistant.ai.prompt.ConversationSummarizer.summarize(
                                        ctx          = appContext,
                                        sessionId    = sessionId,
                                        llamaManager = llamaManager,
                                        olderTurns   = olderToFold,
                                        previousSummary = prevSummary
                                    )
                                }.onFailure {
                                    Log.w("AIRI_PROMPT_COMPRESS",
                                        "summarize failed: ${it.message}")
                                }
                            }
                        }
                    },
                    onStallWarning = {
                        // Non-fatal: native decode is slow but still alive.
                        // Surface as a UI hint via stallActive flow.
                        _stallActive.value = true
                    },
                    onError = { errorMsg ->
                        viewModelScope.launch {
                            thinkingJob?.cancel()
                            thinkingJob = null
                            _isCancelled.set(false)
                            _stallActive.value = false

                            // Categorize the error code emitted by LlamaManager.
                            // Only INACTIVITY_TIMEOUT (i.e. tokens started flowing then stopped)
                            // is a "responded too slowly" condition that warrants the
                            // fast/remote-mode upsell message. FIRST_TOKEN_TIMEOUT and
                            // ERR_NATIVE are real failures and must surface their own message
                            // so the user knows the engine actually failed.
                            val isInactivityAfterFirstToken =
                                errorMsg.startsWith(com.airi.assistant.ai.LlamaManager.ERR_INACTIVITY_TIMEOUT)
                            val isFirstTokenTimeout =
                                errorMsg.startsWith(com.airi.assistant.ai.LlamaManager.ERR_FIRST_TOKEN_TIMEOUT)
                            val isNativeError =
                                errorMsg.startsWith(com.airi.assistant.ai.LlamaManager.ERR_NATIVE)

                            // If we already streamed any tokens, persist whatever we have
                            // through the normal finish path instead of overwriting it.
                            if (firstTokenReceived && _streamingText.value.isNotBlank() && !isInactivityAfterFirstToken) {
                                val partial = _streamingText.value
                                val totalLatency = System.currentTimeMillis() - requestStart
                                viewModelScope.launch { finish(partial, totalLatency, tokenCount) }
                                return@launch
                            }

                            val fallbackRemote = RemoteModelRegistry.getActive()
                            if (fallbackRemote != null) {
                                _streamingText.value = "Thinking..."
                                streamRemoteResponse(fallbackRemote, trimmedInput, systemPrompt, perfMode, streamStart, finish)
                                return@launch
                            }

                            val userVisible = when {
                                // True post-first-token slowdown — the original "fast mode"
                                // upsell message is appropriate here.
                                isInactivityAfterFirstToken ->
                                    "تعذر توليد الرد بسرعة. جرّب Fast Mode أو Remote Model."
                                // No first token within the budget — engine is stalled
                                // (cold cache, oversized prompt, or model not warming up).
                                isFirstTokenTimeout ->
                                    "النموذج لم يبدأ التوليد خلال المهلة. جرّب رسالة أقصر أو أعد تحميل النموذج."
                                // Native crash / exception bubbled up from JNI.
                                isNativeError ->
                                    "حدث خطأ في المحرك المحلي. تفاصيل: ${errorMsg.removePrefix(com.airi.assistant.ai.LlamaManager.ERR_NATIVE).trim()}"
                                else ->
                                    "تعذر إكمال التوليد. ($errorMsg)"
                            }
                            val assistantMessage = memoryManager.recordChatMessage(sessionId, "assistant", userVisible)
                            _messages.update { it + ChatMessage(userVisible, isUser = false, assistantMessage.id) }
                            _streamingText.value = ""
                            _agentState.value = AgentState()
                            refreshSessions()
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

        // Pull the native breakdown that LlamaManager just snapshotted and
        // persist it for the Generation Statistics screen.
        val m = llamaManager.lastMetrics
        val effectiveTps = if (m.tokensPerSec > 0f) m.tokensPerSec else tps
        perfPrefs.edit()
            .putFloat("tokens_per_sec",   effectiveTps)
            .putLong ("last_latency_ms",  elapsed)
            .putLong ("last_tokenize_ms", m.tokenizeMs)
            .putLong ("last_prefill_ms",  m.prefillMs)
            .putLong ("last_first_tok_ms",m.firstTokenMs)
            .putLong ("last_decode_ms",   m.decodeMs)
            .putInt  ("last_decoded_toks",m.decodedTokens)
            .putInt  ("last_n_past",      m.nPast)
            .putInt  ("last_n_ctx",       m.nCtx)
            .apply()

        // ── Per-quantization benchmark record ────────────────────────────────
        // Append a single row to the on-device benchmark store so the
        // "Model Performance" screen can compare quantizations empirically.
        runCatching {
            val meta = com.airi.assistant.perf.ModelMetaProbe.probe()
            val current = com.airi.assistant.ai.ModelManager.getCurrent()
            val mode = _performanceMode.value
            val sizeMb = when {
                meta != null && meta.sizeBytes > 0 -> meta.sizeBytes / (1024L * 1024L)
                else -> com.airi.assistant.perf.ModelMetaProbe.fileSizeMb(current?.path)
            }
            val rec = com.airi.assistant.perf.ModelBenchmark(
                timestamp      = System.currentTimeMillis(),
                modelId        = current?.id ?: "unknown",
                modelDesc      = meta?.description ?: (current?.name ?: "unknown"),
                quantLabel     = meta?.quantLabel ?: "UNKNOWN",
                nParams        = meta?.nParams ?: 0L,
                modelSizeMb    = sizeMb,
                nCtx           = if (m.nCtx > 0) m.nCtx else mode.nCtx,
                nThreads       = mode.nThreads,
                firstTokenMs   = m.firstTokenMs,
                totalLatencyMs = elapsed,
                decodedTokens  = if (m.decodedTokens > 0) m.decodedTokens else tokenCount,
                tokensPerSec   = effectiveTps,
                processMemMb   = com.airi.assistant.perf.ModelMetaProbe.processMemoryMb(),
                perfClass      = com.airi.assistant.perf.PerfClassifier
                    .classify(effectiveTps, m.firstTokenMs)
            )
            com.airi.assistant.perf.ModelBenchmarkRepository.append(appContext, rec)
        }.onFailure { Log.w("AIRI_BENCH", "record failed: ${it.message}") }
    }

    private fun isDeviceWeak(): Boolean {
        val profile = DeviceProfiler.profile(appContext)
        return profile.tier == DeviceTier.LOW || profile.availableRamMb < 1500 || profile.cpuCores <= 4
    }

    // ── Vision pipeline (Phase 3 — wired end-to-end) ─────────────────────────
    //
    // Two public entry points:
    //
    //   loadMmproj(uri)
    //     User-driven: pick a *.gguf projector file from storage. We copy it
    //     into the app's cache (the native loader needs a real filesystem
    //     path, not a content:// URI), call the serialized loader on the
    //     llama dispatcher, and re-detect ModelCapabilities so the UI badge
    //     flips to "vision: yes" the moment the projector is wired up.
    //
    //   sendMessageWithImage(text, imageUri, capturedBitmap)
    //     Replaces the old "[ATTACHMENT: image: name]" text-marker hack.
    //     If the loaded model has vision==true (which now requires a
    //     projector), we decode → downscale (≤672px) → RGB888 → call the
    //     native bridge and surface the reply in the chat. If vision is
    //     NOT available (no projector loaded, or model isn't multimodal),
    //     we fall back to the existing text-marker path so the chat still
    //     accepts the user's message with an honest acknowledgement.
    //
    // Both paths emit AIRI_PROOF tags so the on-device debug log can prove
    // exactly which branch ran.

    /** UI hook: the vision badge should turn green only when this is true. */
    fun isVisionReady(): Boolean = _modelState.value.capabilities.vision

    fun loadMmproj(uri: Uri) {
        val current = ModelManager.getCurrent()
        if (current == null) {
            Log.w("AIRI_PROOF", "MMPROJ_LOAD_REJECTED reason=no_model_loaded")
            _messages.update {
                it + ChatMessage("حمّل النموذج النصي أولاً قبل تحميل ملف الرؤية.", isUser = false)
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Step 1 — materialize the URI to a real path we can mmap.
            // The native loader is path-based; copying once into cache is
            // correct (and lets us re-load after process death).
            val cacheFile = File(appContext.cacheDir, "mmproj_active.gguf")
            val ok = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { out -> input.copyTo(out) }
                } != null
            }.getOrElse { e ->
                Log.e("AIRI_PROOF", "MMPROJ_COPY_FAILED ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (!ok || !cacheFile.exists() || cacheFile.length() < 1_000_000L) {
                withContext(Dispatchers.Main) {
                    _messages.update {
                        it + ChatMessage(
                            "تعذر نسخ ملف ال mmproj. تحقق من الصلاحيات وأعد المحاولة.",
                            isUser = false
                        )
                    }
                }
                Log.w("AIRI_PROOF", "MMPROJ_COPY_INSUFFICIENT bytes=${cacheFile.length()}")
                return@launch
            }
            Log.i("AIRI_PROOF",
                "MMPROJ_COPY_OK bytes=${cacheFile.length()} path=${cacheFile.absolutePath}")

            // Step 2 — load through the serialized dispatcher (must not race
            // against an in-flight generate).
            val loaded = llamaManager.loadMmprojSerialized(cacheFile.absolutePath)

            // Step 3 — re-detect capabilities NOW that the projector is in
            // place. Fixes the prior bug where vision was always false
            // because detect() ran at model-load time before any mmproj.
            val newCaps = if (loaded) ModelCapabilities.detect(current)
                          else _modelState.value.capabilities
            withContext(Dispatchers.Main) {
                _modelState.update { it.copy(capabilities = newCaps) }
                if (loaded && newCaps.vision) {
                    _messages.update {
                        it + ChatMessage("تم تحميل ملف الرؤية بنجاح. الآن يمكنك إرسال صور.", isUser = false)
                    }
                } else if (loaded) {
                    _messages.update {
                        it + ChatMessage(
                            "تم تحميل ملف الرؤية، لكن النموذج النصي الحالي ليس من النماذج المدعومة لتحليل الصور.",
                            isUser = false
                        )
                    }
                } else {
                    _messages.update {
                        it + ChatMessage("فشل تحميل ملف الرؤية. تأكد أن الملف هو mmproj صالح.", isUser = false)
                    }
                }
            }
        }
    }

    /**
     * Image-aware send. Three branches:
     *
     *   (A) No image attached → forwards to the standard text [sendMessage].
     *   (B) Image attached AND vision wired → real native vision pipeline.
     *   (C) Image attached but vision NOT wired → text-marker fallback so
     *       the user's message still goes through (no UI dead-end).
     *
     * Vision generation does NOT stream tokens (the JNI returns the full
     * reply at once), so we drive [_streamingText] with an "Analyzing image…"
     * stage hint until the native call returns.
     *
     * Hard guards (per spec — limits on size, RAM, time):
     *   • Image is downscaled to ≤672px longest side BEFORE allocation.
     *   • RGB byte array is capped at 4MB by VisionImage.MAX_RGB_BYTES.
     *   • Token cap is min(perfMode.maxTokens, 256) — vision prefill
     *     eats the latency budget so long replies aren't worth it.
     *   • Native call has a 180s wall-clock deadline (LlamaManager).
     */
    /**
     * PHASE 3 (actual fix): single, unified attachment dispatcher. Every
     * attachment the user adds — image, camera capture, or file — comes
     * through this one function as a [com.airi.assistant.domain.ChatAttachment].
     * The historical [sendMessageWithImage] is preserved as a one-image
     * delegate so existing callers (and the speech tests) keep working,
     * but **all new UI code goes through this function**.
     *
     * Capability decision (the *only* fork in the unified path):
     *   1. If at least one attachment is an image AND vision is ready
     *      (mmproj loaded + capability flag) → call the native vision
     *      pipeline with the first image. Any *additional* attachments
     *      become text markers appended after the prompt so the model
     *      still knows about them.
     *   2. Otherwise → every attachment becomes a `[image: …]` /
     *      `[file: …]` text marker and the message goes through the
     *      normal text [sendMessage] path. This matches the old
     *      "[ATTACHMENT: image: name]" fallback but works for files too.
     *
     * No hidden forks: the only branching is this one block.
     */
    fun sendMessageWithAttachments(
        input: String,
        attachments: List<com.airi.assistant.domain.ChatAttachment>
    ) {
        if (attachments.isEmpty()) {
            sendMessage(input.trim())
            return
        }
        if (_modelState.value.isModelLoading) return

        val trimmed = input.trim()
        val visionReady = _modelState.value.capabilities.vision &&
            runCatching { LlamaNative.isMmprojLoaded() }.getOrDefault(false)

        // Find the first visual image attachment, if any.
        val primaryImage = attachments.firstOrNull { it.isVisualImage }
        val extras       = attachments - listOfNotNull(primaryImage).toSet()

        Log.i(
            "AIRI_PROOF",
            "ATTACHMENTS_DISPATCH count=${attachments.size} " +
                "image_primary=${primaryImage?.kind?.name ?: "none"} " +
                "extras=${extras.size} vision_ready=$visionReady"
        )

        if (primaryImage != null && visionReady) {
            // Vision path — keep extras visible to the model as text markers.
            val markers = extras.joinToString(separator = "\n") { it.toTextMarker() }
            val fullText = if (markers.isBlank()) trimmed
                           else if (trimmed.isBlank()) markers
                           else "$trimmed\n\n$markers"
            sendMessageWithImage(fullText, primaryImage.uri, primaryImage.bitmap)
        } else {
            // Text-marker path — every attachment becomes one [image:]/[file:] line.
            val markers = attachments.joinToString(separator = "\n") { it.toTextMarker() }
            val fullText = if (trimmed.isBlank()) markers else "$trimmed\n\n$markers"
            // PHASE 3: when there's an image we still want the user bubble
            // to display the thumbnail, so re-use the existing single-shot
            // pendingImageUriForNextSend hand-off used by the old fallback.
            primaryImage?.uri?.let { pendingImageUriForNextSend = it.toString() }
            sendMessage(fullText)
        }
    }

    fun sendMessageWithImage(input: String, imageUri: Uri?, capturedBitmap: Bitmap?) {
        val trimmedInput = input.trim()
        // Branch A: nothing attached → existing text path.
        if (imageUri == null && capturedBitmap == null) {
            sendMessage(trimmedInput)
            return
        }
        if (_modelState.value.isModelLoading) return

        val visionReady = _modelState.value.capabilities.vision &&
                          runCatching { LlamaNative.isMmprojLoaded() }.getOrDefault(false)
        val attachmentName = imageUri?.lastPathSegment ?: "camera_capture"

        // Resolve an in-memory displayable URI ONCE so the user's chat
        // bubble can render the actual thumbnail (Phase 1 spec). For a
        // camera capture we persist the Bitmap to the cache dir as JPEG
        // and use the resulting file URI; for a picker URI we just stringify.
        // Failures are swallowed — a missing thumbnail is degraded UX,
        // never a crash.
        val displayableUri: String? = runCatching {
            when {
                imageUri != null -> imageUri.toString()
                capturedBitmap != null -> {
                    val dir = File(appContext.cacheDir, "chat_attachments").apply { mkdirs() }
                    val out = File(dir, "cam_${System.currentTimeMillis()}.jpg")
                    out.outputStream().use { os ->
                        capturedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
                    }
                    Uri.fromFile(out).toString()
                }
                else -> null
            }
        }.getOrNull()

        // Branch C: image present but vision NOT wired → marker fallback.
        if (!visionReady) {
            Log.i("AIRI_PROOF",
                "VISION_FALLBACK_TEXT_MARKER reason=no_vision_wired name=$attachmentName")
            val finalText = buildString {
                append(trimmedInput)
                append("\n\n[ATTACHMENT: image: ").append(attachmentName)
                append("] (vision model not loaded — respond based on filename only)")
            }
            // Hand off the thumbnail to sendMessage so the user's bubble
            // still shows the picked image (consumed exactly once).
            pendingImageUriForNextSend = displayableUri
            sendMessage(finalText)
            return
        }

        // Branch B: real vision call.
        val current = ModelManager.getCurrent()
        if (current == null || !_modelState.value.isModelReady) {
            _messages.update {
                it + ChatMessage("قم بتحميل النموذج النصي أولاً.", isUser = false)
            }
            return
        }

        viewModelScope.launch {
            val sessionId = currentSessionOrCreate()
            val wasEmpty = _messages.value.isEmpty()

            // Record the user's turn FIRST (with a structured marker so the
            // chat history stays text-serializable for memory/export).
            val userMarker = if (trimmedInput.isBlank()) "[image: $attachmentName]"
                             else "$trimmedInput\n\n[image: $attachmentName]"
            val userMsg = memoryManager.recordChatMessage(sessionId, "user", userMarker)
            if (wasEmpty) memoryManager.renameSession(sessionId, "Image: ${attachmentName.take(40)}")
            _messages.update {
                it + ChatMessage(
                    text = userMarker,
                    isUser = true,
                    id = userMsg.id,
                    imageUri = displayableUri
                )
            }

            subscriptionManager.recordMessage()
            AnalyticsService.messageSent()

            _agentState.value = AgentState(isWorking = true, currentAction = "Analyzing image...")
            _streamingText.value = "Analyzing image..."
            _isCancelled.set(false)

            // ── Bitmap prep (off the main thread) ────────────────────────
            val rgbBundle = withContext(Dispatchers.Default) {
                val bmp: Bitmap? = when {
                    capturedBitmap != null -> VisionImage.downscaleBitmap(capturedBitmap)
                    imageUri != null      -> VisionImage.decodeAndDownscale(appContext, imageUri)
                    else                  -> null
                }
                bmp?.let { ready ->
                    val rgb = VisionImage.bitmapToRgb888(ready)
                    if (rgb != null) Triple(rgb, ready.width, ready.height) else null
                }
            }

            if (rgbBundle == null) {
                _agentState.value = AgentState()
                _streamingText.value = ""
                _messages.update {
                    it + ChatMessage("تعذر معالجة الصورة (تأكد من تنسيقها وحجمها).", isUser = false)
                }
                Log.w("AIRI_PROOF", "VISION_PREP_FAILED name=$attachmentName")
                return@launch
            }
            val (rgb888, w, h) = rgbBundle

            // Token cap: vision prefill is expensive, no point asking for
            // 1k tokens — clip to 256.
            val visionTokens = minOf(_performanceMode.value.maxTokens, 256)
            val visionPrompt = trimmedInput.ifBlank { "Describe this image in detail." }

            val visionStart = System.currentTimeMillis()
            llamaManager.generateWithImage(
                prompt    = visionPrompt,
                rgb888    = rgb888,
                width     = w,
                height    = h,
                maxTokens = visionTokens,
                onComplete = { fullText ->
                    val elapsed = System.currentTimeMillis() - visionStart
                    Log.i("AIRI_PROOF",
                        "VISION_REPLY_DELIVERED elapsed_ms=$elapsed reply_len=${fullText.length}")
                    viewModelScope.launch {
                        val asstMsg = memoryManager.recordChatMessage(
                            sessionId, "assistant", fullText
                        )
                        _messages.update {
                            it + ChatMessage(fullText, isUser = false, id = asstMsg.id)
                        }
                        _streamingText.value = ""
                        _agentState.value = AgentState()
                        refreshSessions()
                        refreshPowerLevel()
                    }
                },
                onError = { errMsg ->
                    Log.w("AIRI_PROOF", "VISION_REPLY_FAILED $errMsg")
                    viewModelScope.launch {
                        _messages.update {
                            it + ChatMessage("تعذر تحليل الصورة: $errMsg", isUser = false)
                        }
                        _streamingText.value = ""
                        _agentState.value = AgentState()
                    }
                }
            )
        }
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
                createNewSession()
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

    /**
     * Cancels any in-flight catalog download started via
     * [downloadCatalogModel]. Safe to call when no download is active —
     * the worker simply re-checks the cancel flag and exits cleanly.
     * Triggered from the "Cancel" button rendered on each catalog card
     * while a download is in progress (see ModelSettingsScreen).
     */
    fun cancelCatalogDownload() {
        ModelDownloadService.cancel(appContext)
        Log.i("AIRI_MODEL_DOWNLOAD", "USER_CANCEL_REQUESTED")
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
            // Auto-detect capabilities ONLY on success. On failure we keep
            // the prior (or fallback) capability profile so the UI doesn't
            // claim "vision: no" for a load that never finished.
            val newCaps = if (success) {
                com.airi.assistant.ai.ModelCapabilities.detect(model)
            } else {
                _modelState.value.capabilities
            }
            _modelState.update {
                val nativeReason = llamaManager.getLastLoadFailure() ?: "unknown reason / سبب غير معروف"
                it.copy(isModelLoading = false, isModelReady = success,
                    loadError = if (success) null
                                else "Model failed to load: $nativeReason\nفشل تحميل النموذج: $nativeReason",
                    loadErrorType = if (success) LoadErrorType.NONE else LoadErrorType.LOAD_FAILED,
                    loadProgress = -1, availableModels = ModelManager.getAllModels(),
                    capabilities = newCaps)
            }
            // Vision is auto-managed: when a text model finishes loading we
            // silently try to re-attach any previously cached projector or a
            // bundled asset. No user action required; if nothing's there the
            // chat simply runs in text-only mode.
            if (success) autoLoadVisionProjectorIfPresent(model)
        }
    }

    /**
     * Silently rehydrates the vision projector after a text model loads.
     *
     * Order of preference:
     *   1. `cacheDir/mmproj_active.gguf`         (sticky from last session)
     *   2. `filesDir/vision/mmproj.gguf`         (auto-installed projector)
     *   3. `assets/vision/mmproj.gguf`           (APK-bundled fallback,
     *                                             copied to cache once)
     *
     * Never prompts the user, never opens a picker, never writes a chat
     * message on absence. The previous manual [loadMmproj] entry point is
     * preserved for power users who want to point at a specific file.
     */
    private fun autoLoadVisionProjectorIfPresent(model: ModelInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = File(appContext.cacheDir, "mmproj_active.gguf")
            val files  = File(appContext.filesDir, "vision/mmproj.gguf")
            val source: File? = when {
                cached.exists() && cached.length() > 1_000_000L -> cached
                files.exists()  && files.length()  > 1_000_000L -> {
                    // Promote to the cache path so the existing loader
                    // contract stays unchanged.
                    runCatching { files.copyTo(cached, overwrite = true) }.getOrNull()
                }
                else -> {
                    // APK asset fallback (only attempted on first run after
                    // an install that ships a bundled projector).
                    runCatching {
                        appContext.assets.open("vision/mmproj.gguf").use { input ->
                            cached.outputStream().use { out -> input.copyTo(out) }
                        }
                        cached.takeIf { it.length() > 1_000_000L }
                    }.getOrNull()
                }
            }
            if (source == null) {
                Log.d("AIRI_PROOF", "MMPROJ_AUTO_SKIP reason=no_projector_present model=${model.name}")
                return@launch
            }
            Log.i("AIRI_PROOF", "MMPROJ_AUTO_TRY path=${source.absolutePath} bytes=${source.length()}")
            val loaded = llamaManager.loadMmprojSerialized(source.absolutePath)
            if (loaded) {
                val caps = com.airi.assistant.ai.ModelCapabilities.detect(model)
                withContext(Dispatchers.Main) {
                    _modelState.update { it.copy(capabilities = caps) }
                }
                Log.i("AIRI_PROOF", "MMPROJ_AUTO_SUCCESS vision=${caps.vision}")
            } else {
                Log.w("AIRI_PROOF", "MMPROJ_AUTO_FAILED path=${source.absolutePath}")
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
        // ── Phase 1.5 — semantic memory injection budget ────────────────────────
        // Hard cap on the share of n_ctx that semantic recall is allowed to
        // consume. PromptCompressor keeps its own 90% n_ctx budget; this is
        // an EARLIER, tighter cap so memory cannot starve the user prompt or
        // the recent-history slice. 20% means at nCtx=2048 → ≤410 tokens of
        // recall; at nCtx=4096 → ≤819. Both leave ample room for everything
        // else and stay well under the llama.cpp KV ceiling that the user
        // explicitly warned about ("crashes easily with large contexts").
        const val SEMANTIC_BUDGET_PCT = 20
        const val SEMANTIC_TOP_K      = 5
    }
}
