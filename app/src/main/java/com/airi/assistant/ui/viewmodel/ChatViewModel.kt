package com.airi.assistant.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.ai.CatalogEntry
import com.airi.assistant.ai.DeviceProfiler
import com.airi.assistant.ai.DeviceTier
import com.airi.assistant.ai.LlamaManager
import com.airi.assistant.ai.ModelCatalog
import com.airi.assistant.ai.ModelInfo
import com.airi.assistant.ai.ModelLoader
import com.airi.assistant.ai.ModelManager
import com.airi.assistant.ai.ModelRegistry
import com.airi.assistant.ai.ModelScout
import com.airi.assistant.ai.ModelSource
import com.airi.assistant.ai.ModelValidator
import com.airi.assistant.ai.ValidationResult
import com.airi.assistant.memory.entity.ChatMessage as MemoryChatMessage
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.tools.FileUtils
import com.airi.assistant.tools.ModelDownloadManager
import com.airi.assistant.tools.ModelDownloadService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val text: String,
    val isUser: Boolean
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

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext      = application.applicationContext
    private val preferences     = appContext.getSharedPreferences("airi_ui_state", Context.MODE_PRIVATE)
    private val llamaManager    = LlamaManager(appContext)
    private val memoryManager   = MemoryManager(appContext)
    private val downloadManager = ModelDownloadManager(appContext)
    private val gson            = Gson()

    // ── Chat messages ────────────────────────────────────────────────────────
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Live streaming (token by token) ─────────────────────────────────────
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    // ── Agent state ──────────────────────────────────────────────────────────
    private val _agentState = MutableStateFlow(AgentState())
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    // ── Model state ──────────────────────────────────────────────────────────
    private val _modelState = MutableStateFlow(createInitialModelState())
    val modelState: StateFlow<ModelUiState> = _modelState.asStateFlow()

    // ── Generation settings ──────────────────────────────────────────────────
    private val _temperature = MutableStateFlow(
        preferences.getFloat("gen_temperature", 0.7f)
    )
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _maxTokens = MutableStateFlow(
        preferences.getInt("gen_max_tokens", 512)
    )
    val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

    private val _systemPrompt = MutableStateFlow(
        preferences.getString("gen_system_prompt", "").orEmpty()
    )
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    // ── Memory browser ───────────────────────────────────────────────────────
    private val _memoryEntries = MutableStateFlow<List<MemoryChatMessage>>(emptyList())
    val memoryEntries: StateFlow<List<MemoryChatMessage>> = _memoryEntries.asStateFlow()

    private val _memoryCount = MutableStateFlow(0)
    val memoryCount: StateFlow<Int> = _memoryCount.asStateFlow()

    init {
        ModelManager.setLoader(ModelLoader(llamaManager))
        loadHistoryFromDb()
        val savedModel = ModelRegistry.getById(_modelState.value.selectedModelId)
        if (savedModel != null && File(savedModel.path).exists()) {
            loadModel(savedModel)
        }
        refreshRecommendedModels()
    }

    // ── History ──────────────────────────────────────────────────────────────

    private fun loadHistoryFromDb() {
        viewModelScope.launch {
            val history = runCatching { memoryManager.getRecentMessages(60) }.getOrElse { emptyList() }
            _messages.value = history.reversed().map { msg ->
                ChatMessage(text = msg.content, isUser = msg.role == "user")
            }
        }
    }

    fun loadMemoryEntries() {
        viewModelScope.launch {
            _memoryEntries.value = runCatching { memoryManager.getRecentMessages(200) }.getOrElse { emptyList() }
            _memoryCount.value = runCatching { memoryManager.getMessageCount() }.getOrElse { 0 }
        }
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    fun sendMessage(input: String) {
        val trimmedInput = input.trim()
        if (trimmedInput.isEmpty()) return

        if (ModelManager.getCurrent() == null || !_modelState.value.isModelReady) {
            _messages.update {
                it + ChatMessage("قم باختيار نموذج أولاً — اختر نموذج من قائمة النماذج.", isUser = false)
            }
            return
        }

        _messages.update { it + ChatMessage(trimmedInput, true) }
        _agentState.value = AgentState(isWorking = true, currentAction = "Generating response…")
        _streamingText.value = ""

        llamaManager.generateStream(
            prompt = trimmedInput,
            onToken = { token ->
                _streamingText.update { it + token }
            },
            onComplete = { fullResponse ->
                _messages.update { it + ChatMessage(fullResponse, isUser = false) }
                _streamingText.value = ""
                _agentState.value = AgentState()
            }
        )
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _streamingText.value = ""
        _agentState.value = AgentState()
    }

    fun clearMemory() {
        viewModelScope.launch {
            runCatching { memoryManager.clearAll() }
            _messages.value = emptyList()
            _memoryEntries.value = emptyList()
            _memoryCount.value = 0
            _streamingText.value = ""
            _agentState.value = AgentState()
        }
    }

    // ── Generation settings ───────────────────────────────────────────────────

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

    // ── Model import / selection ──────────────────────────────────────────────

    fun importModel(uri: Uri) {
        _modelState.update { it.copy(isModelLoading = true, loadError = null, loadErrorType = LoadErrorType.NONE, loadProgress = 0) }
        viewModelScope.launch {
            try {
                val path = FileUtils.copyToInternalStorage(appContext, uri)
                val file = File(path)
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
                _modelState.update {
                    it.copy(isModelLoading = false, isModelReady = false,
                        loadError = e.localizedMessage ?: "Could not import model",
                        loadErrorType = LoadErrorType.LOAD_FAILED, loadProgress = -1,
                        availableModels = ModelManager.getAllModels())
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
                downloadedModelPath = downloadedFile.absolutePath,
                availableModels = ModelManager.getAllModels()
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

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun loadModel(model: ModelInfo) {
        val file = File(model.path)
        val validation = ModelValidator.validate(file, appContext, model.ramRequiredMb)
        if (validation !is ValidationResult.Valid) {
            val (msg, type) = validationMessage(validation)
            _modelState.update {
                it.copy(selectedModelId = model.id, selectedModelName = model.name, selectedModelPath = model.path,
                    selectedModelSize = model.size, isModelLoading = false, isModelReady = false,
                    loadError = msg, loadErrorType = type, loadProgress = -1, availableModels = ModelManager.getAllModels())
            }
            return
        }
        ModelManager.unload()
        _modelState.update {
            it.copy(selectedModelId = model.id, selectedModelName = model.name, selectedModelPath = model.path,
                selectedModelSize = model.size, isModelLoading = true, isModelReady = false,
                loadError = null, loadErrorType = LoadErrorType.NONE, loadProgress = 0,
                downloadedModelAvailable = downloadManager.isModelDownloaded(),
                downloadedModelPath = downloadManager.getModelFile().absolutePath,
                availableModels = ModelManager.getAllModels())
        }
        ModelManager.load(model, onProgress = { percent ->
            _modelState.update { it.copy(loadProgress = percent) }
        }) { success ->
            if (success) {
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
        val savedId   = preferences.getString(KEY_MODEL_ID, "").orEmpty()
        val savedPath = preferences.getString(KEY_MODEL_PATH, "").orEmpty()
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
            name = matched?.name ?: file.nameWithoutExtension,
            fileName = file.name,
            size = file.length(),
            quantization = matched?.quantization ?: detectQuantization(file.name),
            path = file.absolutePath,
            source = source,
            id = file.absolutePath,
            type = type,
            isLocal = true,
            ramRequiredMb = matched?.ramRequiredMb ?: 0,
            contextSize = matched?.contextSize ?: 0
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
    }
}
