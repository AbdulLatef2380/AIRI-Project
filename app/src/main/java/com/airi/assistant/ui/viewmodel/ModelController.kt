package com.airi.assistant.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.airi.assistant.ai.CatalogEntry
import com.airi.assistant.ai.LlamaManager
import com.airi.assistant.ai.LlamaNative
import com.airi.assistant.ai.ModelCapabilities
import com.airi.assistant.ai.ModelCatalog
import com.airi.assistant.ai.ModelInfo
import com.airi.assistant.ai.ModelLoader
import com.airi.assistant.ai.ModelManager
import com.airi.assistant.ai.ModelRegistry
import com.airi.assistant.ai.ModelSource
import com.airi.assistant.ai.ModelType
import com.airi.assistant.ai.ModelValidator
import com.airi.assistant.ai.PerformanceMode
import com.airi.assistant.ai.RuntimeSupervisor
import com.airi.assistant.ai.ValidationResult
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.core.debug.EventSeverity
import com.airi.assistant.core.debug.GenerationPhase
import com.airi.assistant.core.debug.ModeSource
import com.airi.assistant.core.debug.RuntimeDiagnosticsState
import com.airi.assistant.core.debug.RuntimeEventLog
import com.airi.assistant.core.debug.ThermalLevel
import com.airi.assistant.execution.prefs.ExecModePreferences
import com.airi.assistant.tools.ModelDownloadManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * ModelController — owns all model lifecycle logic extracted from ChatViewModel.
 *
 * ChatViewModel constructs one instance and delegates via the public functions.
 * State mutations go through the injected [modelState] MutableStateFlow so the
 * UI binding contract is unchanged. All functions that were `private fun` in
 * ChatViewModel are now `internal fun` here.
 *
 * REAL extraction: ChatViewModel's lines 2845–3287 (443 LOC) live here.
 * ChatViewModel now calls `modelController.loadModel(...)` etc.
 */
internal class ModelController(
    private val appContext:        Context,
    private val viewModelScope:    CoroutineScope,
    private val llamaManager:      LlamaManager,
    private val downloadManager:   ModelDownloadManager,
    private val runtimeSupervisor: RuntimeSupervisor,
    private val execModePrefs:     ExecModePreferences,
    private val preferences:       SharedPreferences,
    private val perfPrefs:         SharedPreferences,
    private val modelState:        MutableStateFlow<ModelUiState>,
    private val performanceModeProvider: () -> PerformanceMode,
    private val generationPhaseProvider: () -> GenerationPhase,
    private val onDiagnosticsReady:      (RuntimeDiagnosticsState) -> Unit
) {
    companion object {
        private const val TAG       = "AIRI_ModelController"
        private const val PROOF_TAG = "AIRI"
        const val KEY_MODEL_ID       = "selected_model_id"
        const val KEY_MODEL_PATH     = "selected_model_path"
        const val KEY_MODEL_REGISTRY = "model_registry_json"
        const val KEY_SCANNED_IDS    = "scanned_model_ids"
    }

    private val gson = Gson()
    private val loadRequestSequence = AtomicLong(0L)

    /** Set when a model is loaded successfully. Used for uptime + diagnostics. */
    var modelLoadedAtMs: Long = 0L
        private set

    /** Last recorded generation duration for diagnostics overlay. */
    var lastGenerationDurationMs: Long = 0L

    // ── Model loading ─────────────────────────────────────────────────────────

    internal fun loadModel(model: ModelInfo) {
        val requestId = loadRequestSequence.incrementAndGet()
        val file       = File(model.path)
        val validation = ModelValidator.validate(file, appContext, model.ramRequiredMb)
        if (validation !is ValidationResult.Valid) {
            val (msg, type) = validationMessage(validation)
            modelState.value = modelState.value.copy(
                selectedModelId   = model.id,   selectedModelName = model.name,
                selectedModelPath = model.path, selectedModelSize = model.size,
                isModelLoading    = false,       isModelReady      = false,
                loadError         = msg,         loadErrorType     = type,
                loadProgress      = -1,          availableModels   = ModelManager.getAllModels()
            )
            return
        }
        com.airi.assistant.domain.verification.VerificationTracker.recordCheck(
            "MEMORY", true, "model=${model.name} requiredMb=${model.ramRequiredMb}"
        )
        ModelManager.unload()
        val loadStart = System.currentTimeMillis()
        modelState.value = modelState.value.copy(
            selectedModelId   = model.id,   selectedModelName = model.name,
            selectedModelPath = model.path, selectedModelSize = model.size,
            isModelLoading    = true,        isModelReady      = false,
            loadError         = null,        loadErrorType     = LoadErrorType.NONE,
            loadProgress      = 0,
            downloadedModelAvailable = downloadManager.isModelDownloaded(),
            downloadedModelPath      = downloadManager.getModelFile().absolutePath,
            availableModels          = ModelManager.getAllModels()
        )
        ModelManager.load(model, onProgress = { percent ->
            if (ModelLoadRequestPolicy.shouldApply(requestId, loadRequestSequence.get())) {
                modelState.value = modelState.value.copy(loadProgress = percent)
            }
        }) { success ->
            if (!ModelLoadRequestPolicy.shouldApply(requestId, loadRequestSequence.get())) {
                Log.i(TAG, "Ignoring stale model-load callback request=$requestId model=${model.name}")
            } else if (success) {
                val loadMs = System.currentTimeMillis() - loadStart
                perfPrefs.edit().putLong("last_model_load_ms", loadMs).apply()
                AnalyticsService.modelLoaded(model.name, loadMs)
                preferences.edit()
                    .putString(KEY_MODEL_ID,   model.id)
                    .putString(KEY_MODEL_PATH, model.path)
                    .apply()
                persistRegistry()
                Log.i(TAG,      "LOAD_SUCCESS name=${model.name} loadMs=$loadMs")
                Log.i(PROOF_TAG,"MODEL_LOAD_SUCCESS name=${model.name} type=${model.type.label} loadMs=${loadMs}ms")
                com.airi.assistant.domain.verification.VerificationTracker.recordCheck(
                    "MODEL_LOAD", true, "path=${model.path} loadMs=$loadMs"
                )
                modelLoadedAtMs = System.currentTimeMillis()
                RuntimeEventLog.post(
                    subsystem = "MODEL",
                    severity  = EventSeverity.INFO,
                    reason    = "Loaded: ${model.name} (${model.type.label}) in ${loadMs}ms"
                )
                runtimeSupervisor.stop()
                runtimeSupervisor.start()
                refreshDiagnosticsSnapshot()
            } else {
                val failure = llamaManager.getLastLoadFailure() ?: "native inference engine returned failure"
                Log.e(TAG,      "LOAD_FAILED reason=$failure")
                Log.e(PROOF_TAG,"MODEL_LOAD_FAILURE name=${model.name} reason=$failure")
                com.airi.assistant.domain.verification.VerificationTracker.recordCheck(
                    "MODEL_LOAD", false, failure
                )
            }
            if (ModelLoadRequestPolicy.shouldApply(requestId, loadRequestSequence.get())) {
                val newCaps = if (success) ModelCapabilities.detect(model)
                              else ModelCapabilities.textOnlyFallback()
                modelState.value = modelState.value.copy(
                    isModelLoading = false,
                    isModelReady   = success,
                    loadError      = if (success) null
                        else "Model failed to load: ${llamaManager.getLastLoadFailure() ?: "unknown"}",
                    loadErrorType  = if (success) LoadErrorType.NONE else LoadErrorType.LOAD_FAILED,
                    loadProgress   = -1,
                    availableModels = ModelManager.getAllModels(),
                    capabilities   = newCaps
                )
                if (success) autoLoadVisionProjectorIfPresent(model)
            }
        }
    }

    internal fun autoLoadVisionProjectorIfPresent(model: ModelInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = File(appContext.cacheDir, "mmproj_active.gguf")
            val files  = File(appContext.filesDir, "vision/mmproj.gguf")
            val source: File? = when {
                cached.exists() && cached.length() > 1_000_000L -> cached
                files.exists()  && files.length()  > 1_000_000L ->
                    runCatching { files.copyTo(cached, overwrite = true) }.getOrNull()
                else -> runCatching {
                    appContext.assets.open("vision/mmproj.gguf").use { inp ->
                        cached.outputStream().use { out -> inp.copyTo(out) }
                    }
                    cached.takeIf { it.length() > 1_000_000L }
                }.getOrNull()
            }
            if (source == null) {
                Log.d(PROOF_TAG, "MMPROJ_AUTO_SKIP reason=no_projector model=${model.name}")
                return@launch
            }
            Log.i(PROOF_TAG, "MMPROJ_AUTO_TRY path=${source.absolutePath}")
            val loaded = llamaManager.loadMmprojSerialized(source.absolutePath)
            if (loaded) {
                val caps = ModelCapabilities.detect(model)
                withContext(Dispatchers.Main) {
                    modelState.value = modelState.value.copy(capabilities = caps)
                }
                Log.i(PROOF_TAG, "MMPROJ_AUTO_SUCCESS vision=${caps.vision}")
            } else {
                Log.w(PROOF_TAG, "MMPROJ_AUTO_FAILED path=${source.absolutePath}")
            }
        }
    }

    // ── Registry persistence ─────────────────────────────────────────────────

    internal fun createInitialModelState(): ModelUiState {
        ModelManager.setLoader(ModelLoader(llamaManager))
        restoreRegistry()
        syncDownloadedModelAvailability()
        val savedId    = preferences.getString(KEY_MODEL_ID, "").orEmpty()
        val savedPath  = preferences.getString(KEY_MODEL_PATH, "").orEmpty()
        val savedModel = ModelRegistry.getById(savedId)
            ?: ModelRegistry.getAll().firstOrNull { it.path == savedPath }
            ?: File(savedPath).takeIf { it.exists() && it.length() > 0 }
                ?.let { f -> createModelFromFile(f, ModelSource.LOCAL_FILE, "custom",
                    ModelCatalog.entries.find { it.fileName == f.name }) }
        if (savedModel != null) { ModelRegistry.addModel(savedModel); persistRegistry() }
        val downloadedFile = downloadManager.getModelFile()
        return ModelUiState(
            selectedModelId          = savedModel?.id.orEmpty(),
            selectedModelName        = savedModel?.name ?: "No model",
            selectedModelPath        = savedModel?.path.orEmpty(),
            selectedModelSize        = savedModel?.size ?: 0L,
            isModelReady             = false,
            downloadedModelAvailable = downloadManager.isModelDownloaded(),
            downloadedModelPath      = downloadedFile.absolutePath,
            availableModels          = ModelManager.getAllModels(),
            catalogModels            = com.airi.assistant.ai.ModelCatalog.entries,
            scannedModelIds          = restoreScannedIds()
        )
    }

    internal fun restoreRegistry() {
        val json = preferences.getString(KEY_MODEL_REGISTRY, null) ?: return
        val type = object : TypeToken<List<ModelInfo>>() {}.type
        val restored = runCatching { gson.fromJson<List<ModelInfo>>(json, type) }
            .getOrNull().orEmpty()
        ModelRegistry.replaceAll(restored.filter { File(it.path).exists() })
    }

    internal fun persistRegistry() {
        preferences.edit()
            .putString(KEY_MODEL_REGISTRY, gson.toJson(ModelManager.getAllModels()))
            .apply()
    }

    internal fun syncDownloadedModelAvailability() {
        val modelsDir = runCatching { downloadManager.getModelsDir() }.getOrNull() ?: return
        val ggufFiles = modelsDir.listFiles()
            ?.filter { it.isFile && it.name.lowercase().endsWith(".gguf") && it.length() > 50_000_000L }
            ?: return
        var changed = false
        for (file in ggufFiles) {
            val meta  = ModelCatalog.entries.find { it.fileName == file.name }
            val model = createModelFromFile(file, ModelSource.DOWNLOADED, "chat", meta)
            if (ModelRegistry.getById(model.id) == null) {
                ModelRegistry.addModel(model)
                changed = true
                Log.i(PROOF_TAG, "MODEL_REGISTERED name=${model.name} source=DOWNLOADED")
            }
        }
        if (changed) persistRegistry()
    }

    internal fun refreshModelList() {
        modelState.value = modelState.value.copy(availableModels = ModelManager.getAllModels())
    }

    internal fun createModelFromFile(
        file:    File,
        source:  ModelSource,
        type:    String,
        meta:    CatalogEntry? = null
    ): ModelInfo {
        val matched = meta ?: ModelCatalog.entries.find { it.fileName == file.name }
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

    internal fun detectQuantization(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            "q4_k_m" in lower -> "Q4_K_M"
            "q4"     in lower -> "4-bit"
            "q5"     in lower -> "5-bit"
            "q8"     in lower -> "8-bit"
            else              -> "GGUF"
        }
    }

    internal fun validationMessage(result: ValidationResult): Pair<String, LoadErrorType> = when (result) {
        is ValidationResult.FileNotFound    -> "File not found"                  to LoadErrorType.FILE_NOT_FOUND
        is ValidationResult.InvalidFormat   -> "Invalid file format"             to LoadErrorType.INVALID_FORMAT
        is ValidationResult.TooSmall        -> "File too small"              to LoadErrorType.TOO_SMALL
        is ValidationResult.InsufficientRam ->
            "Insufficient RAM — ${(result as ValidationResult.InsufficientRam).requiredMb} MB required" to LoadErrorType.INSUFFICIENT_RAM
        else                                -> "Unexpected error"                    to LoadErrorType.LOAD_FAILED
    }

    internal fun persistScannedIds(ids: Set<String>) {
        preferences.edit().putString(KEY_SCANNED_IDS, ids.joinToString("|")).apply()
    }

    internal fun restoreScannedIds(): Set<String> {
        val raw = preferences.getString(KEY_SCANNED_IDS, "") ?: ""
        return if (raw.isBlank()) emptySet() else raw.split("|").toSet()
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    internal fun refreshDiagnosticsSnapshot() {
        viewModelScope.launch(Dispatchers.Default) {
            val (thermalLevel, thermalRaw) = readThermalLevel()
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val availRamMb  = memInfo.availMem / (1024L * 1024L)
            val kvUsed      = runCatching { LlamaNative.getKvPosition() }.getOrDefault(0)
            val kvMax       = runCatching { LlamaNative.getNCtx() }.getOrDefault(0)
            val modelDesc   = runCatching { LlamaNative.getModelDescription() }.getOrDefault("UNAVAILABLE")
            val draftActive = runCatching { LlamaNative.isDraftLoaded() }.getOrDefault(false)
            val sessionId   = runCatching { LlamaNative.nativeGetSessionId() }.getOrDefault(0L)
            val genId       = runCatching { LlamaNative.nativeGetGenerationId() }.getOrDefault(0L)
            val uptimeMs    = if (modelLoadedAtMs > 0L) System.currentTimeMillis() - modelLoadedAtMs else 0L
            val mode        = performanceModeProvider()
            val partial = RuntimeDiagnosticsState(
                effectiveMode        = mode.name,
                modeSource           = ModeSource.MANUAL_OVERRIDE,
                thermalLevel         = thermalLevel,
                thermalRaw           = thermalRaw,
                availRamMb           = availRamMb,
                isLowMemory          = memInfo.lowMemory,
                kvUsed               = kvUsed,
                kvMax                = kvMax,
                modelName            = modelState.value.selectedModelName,
                modelQuant           = extractQuant(modelDesc),
                generationPhase      = generationPhaseProvider(),
                tokensPerSec         = llamaManager.lastMetrics.tokensPerSec,
                draftModelActive     = draftActive,
                gpuVulkanActive      = false,
                sessionId            = sessionId,
                generationId         = genId,
                replayTokenCount     = 0,
                nCtx                 = mode.nCtx,
                nThreads             = mode.nThreads,
                runtimeUptimeMs      = uptimeMs,
                generationDurationMs = lastGenerationDurationMs,
                speculativeActive    = draftActive,
                warnings             = emptyList()
            )
            val warnings = buildWarnings(partial)
            onDiagnosticsReady(partial.copy(warnings = warnings))
        }
    }

    private fun buildWarnings(state: RuntimeDiagnosticsState): List<String> {
        val w = mutableListOf<String>()
        if (state.modeSource == ModeSource.SUPERVISOR_THERMAL)
            w += "Thermal throttling active — runtime downgraded to ${state.effectiveMode}"
        if (state.modeSource == ModeSource.SUPERVISOR_MEMORY)
            w += "Low memory pressure — runtime downgraded to ${state.effectiveMode}"
        if ((state.thermalLevel == ThermalLevel.SEVERE || state.thermalLevel == ThermalLevel.CRITICAL)
            && state.modeSource != ModeSource.SUPERVISOR_THERMAL)
            w += "Device thermal: ${state.thermalLevel.name} — consider reducing workload"
        if (state.isLowMemory && state.modeSource != ModeSource.SUPERVISOR_MEMORY)
            w += "System is reporting low memory"
        if (state.kvMax > 0 && state.kvUsed * 100 / state.kvMax >= 85)
            w += "Context nearing overflow (${state.kvUsed}/${state.kvMax} tokens)"
        if (state.speculativeActive && !state.draftModelActive)
            w += "Speculative decoding enabled but draft model not loaded"
        return w
    }

    private fun readThermalLevel(): Pair<ThermalLevel, Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalLevel.NONE to 0
        return try {
            val pm     = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val status = pm.currentThermalStatus
            val level = when {
                status >= 4 -> ThermalLevel.CRITICAL
                status == 3 -> ThermalLevel.SEVERE
                status == 2 -> ThermalLevel.MODERATE
                status == 1 -> ThermalLevel.LIGHT
                else        -> ThermalLevel.NONE
            }
            level to status
        } catch (t: Throwable) {
            ThermalLevel.NONE to 0
        }
    }

    private fun extractQuant(modelDesc: String): String {
        if (modelDesc == "UNAVAILABLE" || modelDesc.isBlank()) return "—"
        val desc  = modelDesc.substringBefore('|')
        val regex = Regex("(IQ\\d+[_A-Z]*|Q\\d+[_KM_SLX0]*|BF16|F16|F32)", RegexOption.IGNORE_CASE)
        return regex.find(desc)?.value ?: "unknown"
    }
}
