package com.airi.assistant.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.ai.LlamaManager
import com.airi.assistant.ai.ModelInfo
import com.airi.assistant.ai.ModelLoader
import com.airi.assistant.ai.ModelManager
import com.airi.assistant.ai.ModelRegistry
import com.airi.assistant.ai.ModelSource
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

data class ModelUiState(
    val selectedModelId: String = "",
    val selectedModelName: String = "No model",
    val selectedModelPath: String = "",
    val selectedModelSize: Long = 0L,
    val isModelLoading: Boolean = false,
    val isModelReady: Boolean = false,
    val loadError: String? = null,
    val downloadedModelAvailable: Boolean = false,
    val downloadedModelPath: String = "",
    val availableModels: List<ModelInfo> = emptyList()
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
        _modelState.update { it.copy(isModelLoading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val path = FileUtils.copyToInternalStorage(appContext, uri)
                val model = createModelFromFile(File(path), ModelSource.LOCAL_FILE, "custom")
                ModelRegistry.addModel(model)
                persistRegistry()
                preferences.edit()
                    .putString(KEY_MODEL_ID, model.id)
                    .putString(KEY_MODEL_PATH, model.path)
                    .apply()
                refreshModelList()
                loadModel(model)
            } catch (e: Exception) {
                _modelState.update {
                    it.copy(
                        isModelLoading = false,
                        isModelReady = false,
                        loadError = e.localizedMessage ?: "Could not import model",
                        availableModels = ModelManager.getAllModels()
                    )
                }
            }
        }
    }

    fun selectModel(modelId: String) {
        val model = ModelRegistry.getById(modelId)
        if (model == null) {
            _modelState.update { it.copy(loadError = "Model was not found in the local registry") }
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
            _modelState.update { it.copy(loadError = "Downloaded model file was not found") }
            return
        }
        val model = createModelFromFile(file, ModelSource.DOWNLOADED, "chat")
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
        _modelState.update {
            val downloadedFile = downloadManager.getModelFile()
            it.copy(
                downloadedModelAvailable = downloadManager.isModelDownloaded(),
                downloadedModelPath = downloadedFile.absolutePath,
                availableModels = ModelManager.getAllModels()
            )
        }
    }

    private fun loadModel(model: ModelInfo) {
        val file = File(model.path)
        if (!file.exists()) {
            _modelState.update {
                it.copy(
                    selectedModelId = model.id,
                    selectedModelName = model.name,
                    selectedModelPath = model.path,
                    selectedModelSize = 0L,
                    isModelLoading = false,
                    isModelReady = false,
                    loadError = "Model file does not exist",
                    availableModels = ModelManager.getAllModels()
                )
            }
            return
        }

        _modelState.update {
            it.copy(
                selectedModelId = model.id,
                selectedModelName = model.name,
                selectedModelPath = model.path,
                selectedModelSize = model.size,
                isModelLoading = true,
                isModelReady = false,
                loadError = null,
                downloadedModelAvailable = downloadManager.isModelDownloaded(),
                downloadedModelPath = downloadManager.getModelFile().absolutePath,
                availableModels = ModelManager.getAllModels()
            )
        }

        ModelManager.load(model) { success ->
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
                    loadError = if (success) null else "Model could not be loaded by the inference engine",
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
            ?: File(savedPath).takeIf { it.exists() }?.let { createModelFromFile(it, ModelSource.LOCAL_FILE, "custom") }
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
            availableModels = ModelManager.getAllModels()
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
            ModelRegistry.addModel(createModelFromFile(downloadedFile, ModelSource.DOWNLOADED, "chat"))
            persistRegistry()
        }
    }

    private fun refreshModelList() {
        _modelState.update { it.copy(availableModels = ModelManager.getAllModels()) }
    }

    private fun createModelFromFile(file: File, source: ModelSource, type: String): ModelInfo {
        return ModelInfo(
            name = file.nameWithoutExtension,
            fileName = file.name,
            size = file.length(),
            quantization = detectQuantization(file.name),
            path = file.absolutePath,
            source = source,
            id = file.absolutePath,
            type = type,
            isLocal = true
        )
    }

    private fun detectQuantization(fileName: String): String {
        val lowerName = fileName.lowercase()
        return when {
            "q4" in lowerName || "4bit" in lowerName || "4-bit" in lowerName -> "4-bit"
            "q5" in lowerName || "5bit" in lowerName || "5-bit" in lowerName -> "5-bit"
            "q8" in lowerName || "8bit" in lowerName || "8-bit" in lowerName -> "8-bit"
            else -> "GGUF"
        }
    }

    private companion object {
        const val KEY_MODEL_ID = "selected_model_id"
        const val KEY_MODEL_PATH = "selected_model_path"
        const val KEY_MODEL_REGISTRY = "model_registry_json"
    }
}
