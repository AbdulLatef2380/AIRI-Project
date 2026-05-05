package com.airi.assistant.core.analytics

import android.util.Log

/**
 * ProofLogger — structured AIRI_PROOF event bus.
 *
 * All autonomous agent runtime events are emitted through this object so
 * the logcat stream can be mechanically audited. Every method maps to a
 * single AIRI_PROOF log line with stable machine-readable fields.
 *
 * ## Usage
 * ```kotlin
 * ProofLogger.inferenceStart(requestId = "a1b2c3", promptChars = 1400, queueDepth = 0)
 * ProofLogger.inferenceComplete(requestId = "a1b2c3", tokens = 142, elapsedMs = 4321)
 * ```
 *
 * ## Log format
 * `AIRI_PROOF EVENT_NAME key1=val1 key2=val2 ...`
 *
 * ## Thread safety
 * All methods are thread-safe. [Log.i] / [Log.w] / [Log.e] are thread-safe.
 */
object ProofLogger {

    private const val TAG = "AIRI_PROOF"

    // ── Generic ───────────────────────────────────────────────────────────────

    /** Generic structured event. Prefer the typed helpers below. */
    fun log(event: String, data: String) =
        Log.i(TAG, "$event $data")

    fun warn(event: String, data: String) =
        Log.w(TAG, "$event $data")

    fun error(event: String, data: String, cause: Throwable? = null) =
        Log.e(TAG, "$event $data", cause)

    // ── Inference ─────────────────────────────────────────────────────────────

    fun inferenceEnqueue(requestId: String, queueDepth: Int, timeoutMs: Long) =
        Log.i(TAG, "INF_ENQUEUE id=$requestId queue=$queueDepth timeout=${timeoutMs}ms")

    fun inferenceStart(requestId: String, promptChars: Int, queueDepth: Int) =
        Log.i(TAG, "INF_START id=$requestId prompt_chars=$promptChars queue=$queueDepth")

    fun inferenceFirstToken(requestId: String, latencyMs: Long) =
        Log.i(TAG, "INF_FIRST_TOKEN id=$requestId latency=${latencyMs}ms")

    fun inferenceComplete(requestId: String, tokens: Int, elapsedMs: Long) =
        Log.i(TAG, "INF_COMPLETE id=$requestId tokens=$tokens elapsed=${elapsedMs}ms")

    fun inferenceCancelled(requestId: String, tokens: Int) =
        Log.i(TAG, "INF_CANCELLED id=$requestId tokens_partial=$tokens")

    fun inferenceTimeout(requestId: String, timeoutMs: Long, tokensPartial: Int) =
        Log.w(TAG, "INF_TIMEOUT id=$requestId timeout=${timeoutMs}ms tokens_partial=$tokensPartial")

    fun inferenceFailed(requestId: String, cause: String) =
        Log.e(TAG, "INF_FAILED id=$requestId cause=$cause")

    // ── Execution graph ───────────────────────────────────────────────────────

    fun graphStart(goal: String, totalNodes: Int) =
        Log.i(TAG, "GRAPH_START goal='${goal.take(80)}' nodes=$totalNodes")

    fun graphNodeStart(nodeId: String, action: String, wave: Int) =
        Log.i(TAG, "GRAPH_NODE_START id=$nodeId action=${action.take(40)} wave=$wave")

    fun graphNodeDone(nodeId: String, elapsedMs: Long) =
        Log.i(TAG, "GRAPH_NODE_DONE id=$nodeId elapsed=${elapsedMs}ms")

    fun graphNodeFailed(nodeId: String, reason: String, retryCount: Int) =
        Log.w(TAG, "GRAPH_NODE_FAILED id=$nodeId reason='${reason.take(80)}' retry=$retryCount")

    fun graphReflecting() =
        Log.i(TAG, "GRAPH_REFLECTING")

    fun graphComplete(success: Boolean, nodesCompleted: Int, elapsedMs: Long) =
        Log.i(TAG, "GRAPH_COMPLETE success=$success nodes_done=$nodesCompleted elapsed=${elapsedMs}ms")

    fun graphFailed(reason: String) =
        Log.w(TAG, "GRAPH_FAILED reason='${reason.take(120)}'")

    // ── Connectors ────────────────────────────────────────────────────────────

    fun connectorExecute(connectorId: String, action: String) =
        Log.i(TAG, "CONNECTOR_EXEC id=$connectorId action=$action")

    fun connectorSuccess(connectorId: String, action: String, elapsedMs: Long) =
        Log.i(TAG, "CONNECTOR_OK id=$connectorId action=$action elapsed=${elapsedMs}ms")

    fun connectorFailed(connectorId: String, action: String, code: String, retryable: Boolean) =
        Log.w(TAG, "CONNECTOR_FAIL id=$connectorId action=$action code=$code retryable=$retryable")

    // ── Memory / RAG ──────────────────────────────────────────────────────────

    fun ragRetrieval(query: String, k: Int, semantic: Boolean) =
        Log.i(TAG, "RAG_RETRIEVAL query='${query.take(60)}' k=$k semantic=$semantic")

    fun ragContextBuilt(chars: Int) =
        Log.i(TAG, "RAG_CONTEXT_BUILT chars=$chars")

    fun episodicRecord(category: String, id: String) =
        Log.i(TAG, "EPISODIC_RECORD category=$category id=$id")

    fun errorMemoryRecord(category: String, code: String, occurrences: Int) =
        Log.i(TAG, "ERROR_MEMORY category=$category code=$code occurrences=$occurrences")

    fun prefInferred(key: String, confidence: Float) =
        Log.i(TAG, "PREF_INFERRED key=$key confidence=$confidence")

    // ── Voice / TTS / STT ────────────────────────────────────────────────────

    fun voiceState(state: String) =
        Log.i(TAG, "VOICE_STATE $state")

    fun ttsSpeak(chars: Int, utteranceId: String) =
        Log.i(TAG, "TTS_SPEAK chars=$chars utterance_id=$utteranceId")

    fun ttsComplete(utteranceId: String) =
        Log.i(TAG, "TTS_DONE utterance_id=$utteranceId")

    fun sttResult(chars: Int, engine: String) =
        Log.i(TAG, "STT_RESULT chars=$chars engine=$engine")

    fun hotwordDetected(engine: String) =
        Log.i(TAG, "HOTWORD_DETECTED engine=$engine")

    fun hotwordDisabled(reason: String) =
        Log.w(TAG, "HOTWORD_DISABLED reason=$reason")

    // ── Cloud / Hybrid ────────────────────────────────────────────────────────

    fun cloudRequest(provider: String, model: String, promptTokens: Int) =
        Log.i(TAG, "CLOUD_REQUEST provider=$provider model=$model prompt_tokens=$promptTokens")

    fun cloudResponse(provider: String, tokens: Int, elapsedMs: Long) =
        Log.i(TAG, "CLOUD_RESPONSE provider=$provider total_tokens=$tokens elapsed=${elapsedMs}ms")

    fun cloudFailed(provider: String, errorType: String, retryable: Boolean) =
        Log.w(TAG, "CLOUD_FAILED provider=$provider error=$errorType retryable=$retryable")

    fun cloudFailover(from: String, to: String) =
        Log.i(TAG, "CLOUD_FAILOVER from=$from to=$to")

    fun hybridRouting(execMode: String, deviceWeak: Boolean, fallback: Boolean) =
        Log.i(TAG, "HYBRID_ROUTING mode=$execMode device_weak=$deviceWeak fallback=$fallback")

    // ── Thermal / Memory watchdog ─────────────────────────────────────────────

    fun thermalThrottle(level: Int, action: String) =
        Log.w(TAG, "THERMAL_THROTTLE level=$level action=$action")

    fun memoryPressure(freeRamMb: Long, action: String) =
        Log.w(TAG, "MEMORY_PRESSURE free_ram_mb=$freeRamMb action=$action")

    // ── Session / lifecycle ───────────────────────────────────────────────────

    fun sessionStart(sessionId: String) =
        Log.i(TAG, "SESSION_START id=$sessionId")

    fun sessionClear(sessionId: String) =
        Log.i(TAG, "SESSION_CLEAR id=$sessionId")

    fun modelLoaded(modelName: String, sizeMb: Long, elapsedMs: Long) =
        Log.i(TAG, "MODEL_LOADED name='$modelName' size_mb=$sizeMb elapsed=${elapsedMs}ms")

    fun modelUnloaded(modelName: String) =
        Log.i(TAG, "MODEL_UNLOADED name='$modelName'")

    // ── Diagnostics ───────────────────────────────────────────────────────────

    fun diagnosticsResult(testName: String, passed: Boolean, detail: String) {
        val outcome = if (passed) "PASS" else "FAIL"
        Log.i(TAG, "DIAGNOSTICS outcome=$outcome test='$testName' detail='${detail.take(100)}'")
    }

    fun fastPathUsed(inputPreview: String) =
        Log.i(TAG, "FAST_PATH_USED input_preview='${inputPreview.take(40)}'")

    fun streamStarted(queryType: String, model: String, tokens: Int) =
        Log.i(TAG, "STREAM_STARTED query_type=$queryType model=$model max_tokens=$tokens")

    fun classificationResult(queryType: String, wordCount: Int, inputPreview: String) =
        Log.i(TAG, "CLASSIFICATION_RESULT type=$queryType words=$wordCount input='${inputPreview.take(40)}'")
}
