package com.airi.assistant.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.ai.CatalogEntry
import com.airi.assistant.ai.LlamaManager
import com.airi.assistant.ai.ModelCatalog
import com.airi.assistant.ai.ModelInfo
import com.airi.assistant.ai.ModelLoader
import com.airi.assistant.ai.ModelManager
import com.airi.assistant.ai.ModelRegistry
import com.airi.assistant.ai.ModelSource
import com.airi.assistant.ai.ModelValidator
import com.airi.assistant.ai.ValidationResult
import com.airi.assistant.tools.FileUtils
import com.airi.assistant.tools.ModelDownloadManager
import com.airi.assistant.tools.ModelDownloadService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
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
    val catalogModels: List<CatalogEntry> = ModelCatalog.entries
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val preferences = appContext.getSharedPreferences("airi_ui_state", Context.MODE_PRIVATE)
    private val llamaManager = LlamaManager(appContext)
    private val downloadManager = ModelDownloadManager(appContext)
    private val gson = Gson()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _agentState = MutableStateFlow(AgentState())
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _modelState = MutableStateFlow(createInitialModelState())
    val modelState: StateFlow<ModelUiState> = _modelState.asStateFlow()

    init {
        ModelManager.setLoader(ModelLoader(llamaManager))
        val savedModel = ModelRegistry.getById(_modelState.value.selectedModelId)
        if (savedModel != null && File(savedModel.path).exists()) {
            loadModel(savedModel)
        }
    }

    fun sendMessage(input: String) {
        val trimmedInput = input.trim()
        if (trimmedInput.isEmpty()) return

        if (!_modelState.value.isModelReady || ModelManager.getCurrent() == null) {
            _messages.update { it + ChatMessage("Select and activate a local model before sending.", isUser = false) }
            return
        }

        _messages.update { it + ChatMessage(trimmedInput, true) }
        _agentState.value = AgentState(true, "Generating response...")

        llamaManager.generate(trimmedInput) { response ->
            _messages.update { it + ChatMessage(response, isUser = false) }
            _agentState.value = AgentState()
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _agentState.value = AgentState()
    }

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
                            it.copy(
                                isModelLoading = false,
                                isModelReady = false,
                                loadError = msg,
                                loadErrorType = type,
                                loadProgress = -1,
                                availableModels = ModelManager.getAllModels()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _modelState.update {
                    it.copy(
                        isModelLoading = false,
                        isModelReady = false,
                        loadError = e.localizedMessage ?: "Could not import model",
                        loadErrorType = LoadErrorType.LOAD_FAILED,
                        loadProgress = -1,
                        availableModels = ModelManager.getAllModels()
                    )
                }
            }
        }
    }

    fun selectModel(modelId: String) {
        val model = ModelRegistry.getById(modelId)
        if (model == null) {
            _modelState.update { it.copy(loadError = "Model not found in local registry", loadErrorType = LoadErrorType.FILE_NOT_FOUND) }
            return
        }
        preferences.edit()
            .putString(KEY_MODEL_ID, model.id)
            .putString(KEY_MODEL_PATH, model.path)
            .apply()
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
        preferences.edit()
            .putString(KEY_MODEL_ID, model.id)
            .putString(KEY_MODEL_PATH, model.path)
            .apply()
        refreshModelList()
        loadModel(model)
    }

    fun downloadCatalogModel(entry: CatalogEntry) {
        val intent = Intent(appContext, ModelDownloadService::class.java).apply {
            putExtra(ModelDownloadService.EXTRA_DOWNLOAD_URL, entry.downloadUrl)
            putExtra(ModelDownloadService.EXTRA_FILENAME, entry.fileName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
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
        preferences.edit()
            .putString(KEY_MODEL_ID, model.id)
            .putString(KEY_MODEL_PATH, model.path)
            .apply()
        refreshModelList()
        loadModel(model)
    }

    fun startDefaultModelDownload() {
        val intent = Intent(appContext, ModelDownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
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

    private fun loadModel(model: ModelInfo) {
        val file = File(model.path)

        val validation = ModelValidator.validate(file, appContext, model.ramRequiredMb)
        if (validation !is ValidationResult.Valid) {
            val (msg, type) = validationMessage(validation)
            _modelState.update {
                it.copy(
                    selectedModelId = model.id,
                    selectedModelName = model.name,
                    selectedModelPath = model.path,
                    selectedModelSize = model.size,
                    isModelLoading = false,
                    isModelReady = false,
                    loadError = msg,
                    loadErrorType = type,
                    loadProgress = -1,
                    availableModels = ModelManager.getAllModels()
                )
            }
            return
        }

        ModelManager.unload()

        _modelState.update {
            it.copy(
                selectedModelId = model.id,
                selectedModelName = model.name,
                selectedModelPath = model.path,
                selectedModelSize = model.size,
                isModelLoading = true,
                isModelReady = false,
                loadError = null,
                loadErrorType = LoadErrorType.NONE,
                loadProgress = 0,
                downloadedModelAvailable = downloadManager.isModelDownloaded(),
                downloadedModelPath = downloadManager.getModelFile().absolutePath,
                availableModels = ModelManager.getAllModels()
            )
        }

        ModelManager.load(
            model,
            onProgress = { percent ->
                _modelState.update { it.copy(loadProgress = percent) }
            }
        ) { success ->
            if (success) {
                preferences.edit()
                    .putString(KEY_MODEL_ID, model.id)
                    .putString(KEY_MODEL_PATH, model.path)
                    .apply()
                persistRegistry()
            }
            _modelState.update {
                it.copy(
                    isModelLoading = false,
                    isModelReady = success,
                    loadError = if (success) null else "فشل تحميل النموذج في محرك الاستنتاج",
                    loadErrorType = if (success) LoadErrorType.NONE else LoadErrorType.LOAD_FAILED,
                    loadProgress = -1,
                    availableModels = ModelManager.getAllModels()
                )
            }
        }
    }

    private fun createInitialModelState(): ModelUiState {
        restoreRegistry()
        syncDownloadedModelAvailability()
        val savedId = preferences.getString(KEY_MODEL_ID, "").orEmpty()
        val savedPath = preferences.getString(KEY_MODEL_PATH, "").orEmpty()
        val savedModel = ModelRegistry.getById(savedId)
            ?: ModelRegistry.getAll().firstOrNull { it.path == savedPath }
            ?: File(savedPath).takeIf { it.exists() && it.length() > 0 }
                ?.let { f ->
                    val meta = ModelCatalog.entries.find { it.fileName == f.name }
                    createModelFromFile(f, ModelSource.LOCAL_FILE, "custom", meta)
                }
        if (savedModel != null) {
            ModelRegistry.addModel(savedModel)
            persistRegistry()
        }
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
            catalogModels = ModelCatalog.entries
        )
    }

    private fun restoreRegistry() {
        val json = preferences.getString(KEY_MODEL_REGISTRY, null) ?: return
        val type = object : TypeToken<List<ModelInfo>>() {}.type
        val restored = runCatching { gson.fromJson<List<ModelInfo>>(json, type) }.getOrNull().orEmpty()
        ModelRegistry.replaceAll(restored.filter { File(it.path).exists() })
    }

    private fun persistRegistry() {
        preferences.edit()
            .putString(KEY_MODEL_REGISTRY, gson.toJson(ModelManager.getAllModels()))
            .apply()
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
        file: File,
        source: ModelSource,
        type: String,
        catalogMeta: CatalogEntry? = null
    ): ModelInfo {
        val matchedCatalog = catalogMeta ?: ModelCatalog.entries.find { it.fileName == file.name }
        return ModelInfo(
            name = matchedCatalog?.name ?: file.nameWithoutExtension,
            fileName = file.name,
            size = file.length(),
            quantization = matchedCatalog?.quantization ?: detectQuantization(file.name),
            path = file.absolutePath,
            source = source,
            id = file.absolutePath,
            type = type,
            isLocal = true,
            ramRequiredMb = matchedCatalog?.ramRequiredMb ?: 0,
            contextSize = matchedCatalog?.contextSize ?: 0
        )
    }

    private fun detectQuantization(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            "q4_k_m" in lower -> "Q4_K_M"
            "q4" in lower || "4bit" in lower -> "4-bit"
            "q5" in lower || "5bit" in lower -> "5-bit"
            "q8" in lower || "8bit" in lower -> "8-bit"
            else -> "GGUF"
        }
    }

    private fun validationMessage(result: ValidationResult): Pair
