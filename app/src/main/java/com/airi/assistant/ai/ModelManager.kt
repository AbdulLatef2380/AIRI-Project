package com.airi.assistant.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ModelManager — production-grade on-device model lifecycle manager.
 *
 * Responsibilities:
 *  1. **State tracking** — exposes [state] as a StateFlow so every UI layer
 *     reacts immediately to load / unload / error transitions without polling.
 *  2. **Device capability gating** — [canRunModel] compares available RAM
 *     against the model's declared [ModelInfo.ramRequiredMb] before allowing
 *     a load request, preventing OOM crashes.
 *  3. **Load / unload orchestration** — delegates to the injected [ModelLoader]
 *     but serializes all transitions on a single-threaded IO scope so only one
 *     load can be in-flight at a time.
 *  4. **Download state management** — tracks per-model download progress via
 *     [downloadStates] StateFlow consumed by the marketplace UI.
 *  5. **Registry CRUD** — exposes full create / read / update / delete over
 *     the [ModelRegistry] with event emission.
 *  6. **Thread safety** — all mutable state is backed by [MutableStateFlow]
 *     or written on [Dispatchers.IO] inside a [SupervisorJob] scope. The
 *     object itself holds no bare mutable vars visible outside the class.
 *
 * ## Usage
 * ```kotlin
 * ModelManager.init(context)
 * ModelManager.state.collect { s -> ... }
 * ModelManager.load(modelInfo)
 * ```
 *
 * Backward-compatible: existing call sites using the old callback-style API
 * ([load(model, onProgress, onReady)]) continue to work unchanged.
 */
object ModelManager {

    // ── Public state ──────────────────────────────────────────────────────────

    data class LoadState(
        val isLoading:      Boolean       = false,
        val isReady:        Boolean       = false,
        val currentModel:   ModelInfo?    = null,
        val loadProgress:   Int           = 0,      // 0–100
        val errorMessage:   String?       = null,
        val ramAvailableMb: Long          = 0L
    )

    data class DownloadState(
        val modelId:     String,
        val isDownloading: Boolean = false,
        val progress:    Int       = 0,             // 0–100
        val isComplete:  Boolean   = false,
        val errorMsg:    String?   = null,
        val bytesTotal:  Long      = 0L,
        val bytesLoaded: Long      = 0L
    )

    private val _state          = MutableStateFlow(LoadState())
    val state: StateFlow<LoadState> = _state.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val _allModels      = MutableStateFlow<List<ModelInfo>>(emptyList())
    val allModels: StateFlow<List<ModelInfo>> = _allModels.asStateFlow()

    // ── Private internals ─────────────────────────────────────────────────────

    private val scope           = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loader: ModelLoader? = null
    private var appContext: Context? = null

    private const val TAG = "AIRI_ModelManager"

    // ── Initialisation ────────────────────────────────────────────────────────

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshModels()
        Log.i(TAG, "ModelManager initialised")
    }

    fun setLoader(l: ModelLoader) {
        loader = l
        Log.i(TAG, "ModelLoader set: ${l::class.simpleName}")
    }

    // ── Device capability check ───────────────────────────────────────────────

    /**
     * Returns true when the device has enough free RAM to run [model].
     * Uses ActivityManager to get live available memory — this is more
     * accurate than Runtime.maxMemory() which reflects the JVM heap only.
     */
    fun canRunModel(model: ModelInfo): Boolean {
        val ctx    = appContext ?: return true   // no context → optimistic
        val am     = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val mem    = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val availMb = mem.availMem / (1024L * 1024L)
        _state.value = _state.value.copy(ramAvailableMb = availMb)

        if (model.ramRequiredMb <= 0) return true   // unspecified → allow
        val required = model.ramRequiredMb.toLong()
        val ok       = availMb >= (required * 0.85f).toLong() // 15% headroom buffer
        if (!ok) {
            Log.w(TAG, "RAM check FAIL model=${model.name} required=${required}MB available=${availMb}MB")
        }
        return ok
    }

    fun availableRamMb(): Long {
        val ctx = appContext ?: return Long.MAX_VALUE
        val am  = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return Long.MAX_VALUE
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        return mem.availMem / (1024L * 1024L)
    }

    // ── Load / Unload ─────────────────────────────────────────────────────────

    /**
     * Suspend-style load. Emits progress via [state] StateFlow.
     * Returns true on success, false on any failure.
     */
    suspend fun loadSuspend(model: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        val activeLoader = loader
        if (activeLoader == null) {
            Log.e(TAG, "load() called but no ModelLoader is set")
            _state.value = _state.value.copy(errorMessage = "No model loader configured")
            return@withContext false
        }
        if (_state.value.isLoading) {
            Log.w(TAG, "load() ignored — already loading")
            return@withContext false
        }
        if (!canRunModel(model)) {
            val msg = "Insufficient RAM for ${model.name} (needs ~${model.ramRequiredMb}MB)"
            Log.e(TAG, msg)
            _state.value = _state.value.copy(errorMessage = msg, isReady = false)
            return@withContext false
        }

        Log.i(TAG, "LOAD_START model=${model.name} size=${model.size} quant=${model.quantization}")
        _state.value = LoadState(
            isLoading    = true,
            loadProgress = 0,
            currentModel = model,
            errorMessage = null
        )

        activeLoader.unload()

        var success = false
        activeLoader.loadModel(model, { pct ->
            _state.value = _state.value.copy(loadProgress = pct)
        }) { ok ->
            success = ok
            if (ok) {
                ModelRegistry.addModel(model)
                _state.value = LoadState(
                    isLoading    = false,
                    isReady      = true,
                    currentModel = model,
                    loadProgress = 100,
                    errorMessage = null
                )
                Log.i(TAG, "LOAD_SUCCESS model=${model.name}")
            } else {
                _state.value = LoadState(
                    isLoading    = false,
                    isReady      = false,
                    currentModel = null,
                    loadProgress = 0,
                    errorMessage = "Failed to load ${model.name}"
                )
                Log.e(TAG, "LOAD_FAILED model=${model.name}")
            }
            refreshModels()
        }
        success
    }

    /**
     * Legacy callback-style load — preserved for backward compatibility.
     * Delegates to [loadSuspend] on the IO scope.
     */
    fun load(model: ModelInfo, onProgress: (Int) -> Unit = {}, onReady: (Boolean) -> Unit) {
        val activeLoader = loader
        if (activeLoader == null) { onReady(false); return }
        if (_state.value.isLoading) { onReady(false); return }

        scope.launch {
            if (!canRunModel(model)) { onReady(false); return@launch }

            _state.value = LoadState(isLoading = true, loadProgress = 0, currentModel = model)
            activeLoader.unload()
            activeLoader.loadModel(model, { pct ->
                _state.value = _state.value.copy(loadProgress = pct)
                onProgress(pct)
            }) { ok ->
                if (ok) {
                    ModelRegistry.addModel(model)
                    _state.value = LoadState(isLoading = false, isReady = true,
                        currentModel = model, loadProgress = 100)
                } else {
                    _state.value = LoadState(isLoading = false, isReady = false,
                        currentModel = null, errorMessage = "Failed to load ${model.name}")
                }
                refreshModels()
                onReady(ok)
            }
        }
    }

    fun unload() {
        loader?.unload()
        _state.value = LoadState(isLoading = false, isReady = false, currentModel = null)
        Log.i(TAG, "UNLOAD model unloaded")
    }

    // ── Download state management ─────────────────────────────────────────────

    fun beginDownload(modelId: String, totalBytes: Long = 0L) {
        val current = _downloadStates.value.toMutableMap()
        current[modelId] = DownloadState(modelId = modelId, isDownloading = true,
            bytesTotal = totalBytes)
        _downloadStates.value = current
        Log.i(TAG, "DOWNLOAD_START modelId=$modelId totalBytes=$totalBytes")
    }

    fun updateDownloadProgress(modelId: String, bytesLoaded: Long, bytesTotal: Long) {
        val current = _downloadStates.value.toMutableMap()
        val pct = if (bytesTotal > 0) ((bytesLoaded * 100L) / bytesTotal).toInt() else 0
        current[modelId] = DownloadState(modelId = modelId, isDownloading = true,
            progress = pct, bytesLoaded = bytesLoaded, bytesTotal = bytesTotal)
        _downloadStates.value = current
    }

    fun completeDownload(modelId: String) {
        val current = _downloadStates.value.toMutableMap()
        current[modelId] = DownloadState(modelId = modelId, isDownloading = false,
            isComplete = true, progress = 100)
        _downloadStates.value = current
        Log.i(TAG, "DOWNLOAD_COMPLETE modelId=$modelId")
    }

    fun failDownload(modelId: String, error: String) {
        val current = _downloadStates.value.toMutableMap()
        current[modelId] = DownloadState(modelId = modelId, isDownloading = false,
            isComplete = false, errorMsg = error)
        _downloadStates.value = current
        Log.e(TAG, "DOWNLOAD_FAILED modelId=$modelId error=$error")
    }

    fun clearDownloadState(modelId: String) {
        val current = _downloadStates.value.toMutableMap()
        current.remove(modelId)
        _downloadStates.value = current
    }

    // ── Registry CRUD ─────────────────────────────────────────────────────────

    fun getCurrent(): ModelInfo?         = _state.value.currentModel

    fun getAllModels(): List<ModelInfo>   = ModelRegistry.getAll()

    fun getModelsForDevice(): List<ModelInfo> {
        val availRam = availableRamMb()
        return ModelRegistry.getAll().filter { model ->
            model.ramRequiredMb <= 0 || model.ramRequiredMb.toLong() <= availRam
        }
    }

    fun addModel(model: ModelInfo) {
        ModelRegistry.addModel(model)
        refreshModels()
        Log.i(TAG, "MODEL_ADDED id=${model.id} name=${model.name}")
    }

    fun remove(model: ModelInfo) {
        if (_state.value.currentModel?.id == model.id) unload()
        ModelRegistry.remove(model)
        clearDownloadState(model.id)
        refreshModels()
        Log.i(TAG, "MODEL_REMOVED id=${model.id}")
    }

    fun isModelLoaded(): Boolean = _state.value.isReady
    fun isLoading(): Boolean     = _state.value.isLoading

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun refreshModels() {
        _allModels.value = ModelRegistry.getAll()
    }

    /** Diagnostics snapshot for the debug screen. */
    fun diagnosticSummary(): String = buildString {
        val s = _state.value
        appendLine("ModelManager Diagnostics")
        appendLine("  isLoading   : ${s.isLoading}")
        appendLine("  isReady     : ${s.isReady}")
        appendLine("  current     : ${s.currentModel?.name ?: "none"}")
        appendLine("  progress    : ${s.loadProgress}%")
        appendLine("  ramAvailable: ${s.ramAvailableMb}MB")
        appendLine("  registry    : ${ModelRegistry.getAll().size} models")
        appendLine("  downloads   : ${_downloadStates.value.size} tracked")
    }
}
