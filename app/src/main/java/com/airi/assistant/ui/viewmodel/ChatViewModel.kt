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
import com.airi.assistant.ai.ModelSource
import com.airi.assistant.tools.FileUtils
import com.airi.assistant.tools.ModelDownloadManager
import com.airi.assistant.tools.ModelDownloadService
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
    val selectedModelName: String = "No model",
    val selectedModelPath: String = "",
    val selectedModelSize: Long = 0L,
    val isModelLoading: Boolean = false,
    val isModelReady: Boolean = false,
    val loadError: String? = null,
    val downloadedModelAvailable: Boolean = false,
    val downloadedModelPath: String = ""
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val preferences = appContext.getSharedPreferences("airi_ui_state", Context.MODE_PRIVATE)
    private val llamaManager = LlamaManager(appContext)
    private val downloadManager = ModelDownloadManager(appContext)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _agentState = MutableStateFlow(AgentState())
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _modelState = MutableStateFlow(createInitialModelState())
    val modelState: StateFlow<ModelUiState> = _modelState.asStateFlow()

    init {
        ModelManager.setLoader(ModelLoader(llamaManager))
        val savedPath = _modelState.value.selectedModelPath
        if (savedPath.isNotBlank() && File(savedPath).exists()) {
            loadModel(savedPath)
        }
    }

    fun sendMessage(input: String) {
        val trimmedInput = input.trim()
        if (trimmedInput.isEmpty()) return

        if (!_modelState.value.isModelReady) {
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
                preferences.edit().putString(KEY_MODEL_PATH, path).apply()
                loadModel(path)
            } catch (e: Exception) {
                _modelState.update {
                    it.copy(
                        isModelLoading = false,
                        isModelReady = false,
                        loadError = e.localizedMessage ?: "Could not import model"
                    )
                }
            }
        }
    }

    fun activateDownloadedModel() {
        val file = downloadManager.getModelFile()
        if (!file.exists()) {
            _modelState.update { it.copy(loadError = "Downloaded model file was not found") }
            return
        }
        preferences.edit().putString(KEY_MODEL_PATH, file.absolutePath).apply()
        loadModel(file.absolutePath)
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
        _modelState.update {
            val downloadedFile = downloadManager.getModelFile()
            it.copy(
                downloadedModelAvailable = downloadManager.isModelDownloaded(),
                downloadedModelPath = downloadedFile.absolutePath
            )
        }
    }

    private fun loadModel(path: String) {
        val file = File(path)
        if (!file.exists()) {
            _modelState.update {
                it.copy(
                    selectedModelName = file.name.ifBlank { "No model" },
                    selectedModelPath = path,
                    selectedModelSize = 0L,
                    isModelLoading = false,
                    isModelReady = false,
                    loadError = "Model file does not exist"
                )
            }
            return
        }

        val model = ModelInfo(
            name = file.nameWithoutExtension,
            fileName = file.name,
            size = file.length(),
            quantization = detectQuantization(file.name),
            path = file.absolutePath,
            source = ModelSource.LOCAL_FILE
        )

        _modelState.update {
            it.copy(
                selectedModelName = model.name,
                selectedModelPath = model.path,
                selectedModelSize = model.size,
                isModelLoading = true,
                isModelReady = false,
                loadError = null,
                downloadedModelAvailable = downloadManager.isModelDownloaded(),
                downloadedModelPath = downloadManager.getModelFile().absolutePath
            )
        }

        ModelManager.load(model) { success ->
            _modelState.update {
                it.copy(
                    isModelLoading = false,
                    isModelReady = success,
                    loadError = if (success) null else "Model could not be loaded by the inference engine"
                )
            }
        }
    }

    private fun createInitialModelState(): ModelUiState {
        val savedPath = preferences.getString(KEY_MODEL_PATH, "").orEmpty()
        val savedFile = File(savedPath)
        val downloadedFile = downloadManager.getModelFile()
        return ModelUiState(
            selectedModelName = if (savedFile.exists()) savedFile.nameWithoutExtension else "No model",
            selectedModelPath = savedPath,
            selectedModelSize = if (savedFile.exists()) savedFile.length() else 0L,
            isModelReady = false,
            downloadedModelAvailable = downloadManager.isModelDownloaded(),
            downloadedModelPath = downloadedFile.absolutePath
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
        const val KEY_MODEL_PATH = "selected_model_path"
    }
}
