package com.airi.assistant.ai

import android.util.Log

object LlamaNative {
    private var available = false
    private var loadFailure: String? = null

    init {
        try {
            System.loadLibrary("airi_native")
            available = true
            Log.i("LlamaNative", "Native library airi_native loaded")
            // AIRI_PROOF: this single line, present in logcat at app start,
            // is a positive proof that lib/arm64-v8a/libairi_native.so is in
            // the installed APK and dlopen() succeeded. If it is MISSING from
            // logcat, the failure is at the build/packaging layer — not in
            // any of the runtime code paths. See:
            //   .github/workflows/android_build.yml (CI verification)
            //   app/build.gradle.kts task airiVerifyNativeInApk
            Log.i("AIRI_PROOF", "NATIVE_LIB_LOADED lib=airi_native abi=arm64-v8a")
        } catch (e: UnsatisfiedLinkError) {
            available = false
            loadFailure = e.message
            Log.e("LlamaNative", "Native library airi_native not found: ${e.message}", e)
            // AIRI_PROOF: explicit failure tag so the user (or test harness)
            // can grep logcat for ONE string and decide which layer is broken.
            // If you see this line, no inference path will ever work — the
            // .so is missing from the installed APK. Fix the BUILD, not the
            // runtime code.
            Log.e("AIRI_PROOF",
                "NATIVE_LIB_MISSING lib=airi_native abi=arm64-v8a reason=${e.message}")
        }
    }

    fun isAvailable(): Boolean = available

    fun loadFailureMessage(): String? = loadFailure

    interface ProgressCallback {
        fun onProgress(percent: Int)
    }

    // ── Model load ───────────────────────────────────────────────────────────
    external fun loadModelWithProgress(modelPath: String, callback: ProgressCallback)
    external fun loadModel(modelPath: String): String

    // ── Session API (incremental, KV-cache reuse) ────────────────────────────
    // Lifecycle:
    //   beginSession()                     // wipes KV, n_past = 0
    //   appendUserTurn("…<|im_end|>\n…")   // tokenizes JUST this fragment,
    //                                      // decodes at n_past, n_past += k.
    //                                      // Last token decoded with logits=true.
    //   generateNextTokens(N) { chunk -> } // samples; each token decoded into KV.
    //   appendAssistantTurn("<|im_end|>\n")// closes the assistant turn.
    //   …repeat appendUserTurn / generateNextTokens / appendAssistantTurn…
    //   resetSession()                     // wipes KV when conversation ends.
    external fun beginSession()
    external fun resetSession()
    external fun appendUserTurn(text: String)
    external fun appendAssistantTurn(text: String)
    external fun generateNextTokens(maxTokens: Int, callback: (String) -> Unit)
    external fun getKvPosition(): Int
    external fun getNCtx(): Int

    // ── Legacy one-shot API (resets session per call). Kept for tool-call
    //    follow-up paths that need a stateless reformulation. Calling these
    //    DESTROYS the in-flight session — callers must mark their session as
    //    invalidated after use. ────────────────────────────────────────────
    external fun generateResponse(prompt: String): String
    external fun generateStream(prompt: String, onToken: (String) -> Unit)

    external fun cancel()

    // ── SPEC v2: state-machine entry points ─────────────────────────────────
    //
    // nativeCancel()        — equivalent to cancel() but routed through the
    //                         dedicated state-machine cancel flag. The decode
    //                         loop checks the flag every iteration so
    //                         cancellation latency is bounded by one
    //                         llama_decode step. Lock-free; safe to call from
    //                         any thread (does NOT block on the llama mutex).
    //
    // nativeGetLastStatus() — returns the result code of the most recent
    //                         appendUserTurn / appendAssistantTurn /
    //                         generateNextTokens / generateNextTokensSpeculative
    //                         call:
    //                            0  = OK
    //                           -1  = ERROR             (decode/llama failure)
    //                           -2  = CANCELLED         (cancel flag was set)
    //                           -3  = CONTEXT_OVERFLOW  (n_past+N >= n_ctx)
    //                         The Kotlin safe-generation handler reads this
    //                         right after the JNI call returns to decide:
    //                           -3 → fullReset() + retry once
    //                           -1 → fullReset() + stop
    //                           -2 → stop cleanly
    //
    // nativeFullReset()     — destroys the native llama_context and rebuilds
    //                         it from the same llama_model with the cached
    //                         cparams (n_ctx, n_threads). The CLEANUP step of
    //                         the state machine: ANY error during
    //                         PREFLIGHT/PREFILL/GENERATE results in a full
    //                         context reset before the next turn.
    external fun nativeCancel()

    // nativeClearCancel() — atomically clears the native cancel flag. Called
    // at the START of every generation cycle (inside lifecycleLock, before
    // reconcileSession) to ensure no stale flag from a previous timeout,
    // user cancel, or generate-entry early exit poisons the incremental-
    // session path (which skips beginSession() and its implicit clear).
    // Lock-free — safe to call from any thread.
    external fun nativeClearCancel()

    external fun nativeGetLastStatus(): Int
    external fun nativeFullReset()

    // ── SPEC v3: stability primitives ───────────────────────────────────────
    //
    // nativeCountTokens(text)    — exact token count of `text` under the loaded
    //                              vocab. Returns:
    //                                ≥ 0  the count
    //                                 -1  no model loaded
    //                                 -2  tokenizer failure
    //                              Read-only on g_ctx (does NOT touch KV);
    //                              cheap enough to call once per history
    //                              message before every turn so the JVM can
    //                              enforce a token budget instead of a
    //                              message-count budget.
    //
    // nativeGetSessionId()       — monotonic counter, bumped on every event
    //                              that creates / replaces / wipes the
    //                              llama_context (loadModel,
    //                              loadModelWithProgress, setRuntimeMode,
    //                              nativeFullReset, beginSession,
    //                              resetSession). Captured by LlamaManager
    //                              before issuing generateNextTokens and
    //                              re-checked inside every Main-dispatched
    //                              onToken/onComplete to drop callbacks that
    //                              outlived their context.
    //
    // nativeGetGenerationId()    — monotonic counter, bumped at the entry
    //                              of every airi_generate_next call. Lets
    //                              the JVM detect "old generation streams
    //                              into new state" if a Main-dispatched
    //                              token block arrives after a newer
    //                              generation has already started.
    external fun nativeCountTokens(text: String): Int
    external fun nativeGetSessionId(): Long
    external fun nativeGetGenerationId(): Long

    // ── Runtime tuning (no model reload) ─────────────────────────────────────
    // Hot-swaps the llama_context with the requested n_ctx / n_threads. The
    // GGUF model stays mmapped; only the KV cache + scheduler are rebuilt.
    // Wipes KV — caller MUST follow with beginSession() before next message.
    external fun setRuntimeMode(nCtx: Int, nThreads: Int)

    // ── Telemetry ────────────────────────────────────────────────────────────
    // Returns [loadMs, tokenizeMs, prefillMs, firstTokenMs, decodeMs,
    //          decodedTokens, nPast, nCtx]. See LlamaBridge.cpp comment.
    external fun getLastTimings(): LongArray

    // ── Model metadata (for the on-device quantization benchmark) ────────────
    // Returns "<llama_model_desc>|<n_params>|<size_bytes>" or "UNAVAILABLE"
    // if no model is loaded. The desc is the same string llama.cpp prints at
    // load time and contains the quantization label (Q4_K_M, Q5_K_M, …).
    external fun getModelDescription(): String

    // ── Speculative decoding (optional, opt-in via SpeculativeManager) ───────
    // loadDraftModel returns one of:
    //   "DRAFT_OK", "MAIN_NOT_LOADED", "SAME_AS_MAIN", "FILE_NOT_FOUND",
    //   "INVALID_GGUF", "VOCAB_MISMATCH", "DRAFT_LOAD_FAILED", "DRAFT_CTX_FAILED"
    external fun loadDraftModel(modelPath: String): String
    external fun unloadDraftModel()
    external fun isDraftLoaded(): Boolean

    // generateNextTokensSpeculative falls back to the standard single-token
    // path automatically if the draft is missing or out of sync, so callers
    // can call it unconditionally once the feature flag is on.
    external fun generateNextTokensSpeculative(
        maxTokens: Int,
        draftN: Int,
        callback: (String) -> Unit
    )

    // Returns [drafted, accepted, runs]. Acceptance rate = accepted/drafted.
    external fun getSpecStats(): LongArray
    external fun resetSpecStats()

    // ── Embedding API (semantic memory, Phase 2) ─────────────────────────────
    // Uses a SECOND llama_model + llama_context inside the native bridge,
    // initialised with `embeddings = true` and `pooling_type = MEAN`. This
    // is independent of the chat context — calling these methods does NOT
    // touch the chat KV cache. Intended companion model is a small
    // sentence-embedding GGUF (e.g. bge-small-en-v1.5 ≈ 30MB, dim=384).
    //
    // loadEmbeddingModel returns:
    //   "OK dim=<N>"        – success, vector dimension is N
    //   "ERR_NULL_PATH"     – jstring was null
    //   "ERR_FILE"          – file_exists / size check failed
    //   "ERR_MODEL_LOAD"    – llama_model_load_from_file returned null
    //   "ERR_CTX_INIT"      – llama_init_from_model returned null
    //
    // computeEmbedding returns the L2-normalised pooled vector, or null
    // on any failure (decode error, no model loaded, empty input).
    external fun loadEmbeddingModel(modelPath: String): String
    external fun computeEmbedding(text: String): FloatArray?
    external fun unloadEmbeddingModel()
    external fun getEmbeddingDim(): Int

    // ── Vision / multimodal API (mmproj + mtmd, Phase 3) ─────────────────────
    // Wraps the upstream `tools/mtmd` library that we vendored under
    //   app/src/main/cpp/llama/tools/mtmd/
    // and gated behind the AIRI_HAS_MTMD CMake switch. The native bridge
    // uses the SAME g_model that loadModel() created, so:
    //   1. loadModel("…llama.gguf")
    //   2. loadMmproj("…llava-mmproj.gguf")  ← associates vision projector
    //   3. evalImageAndGenerate(prompt, rgb888, w, h, maxTokens)
    //
    // RGB byte layout MUST be packed RGB888, exactly width*height*3 bytes,
    // row-major top-down. The caller (ChatViewModel.generateWithImage) is
    // responsible for downscaling to a sensible dimension first — there is
    // no internal cap, so passing a 12 MP camera bitmap will OOM.
    //
    // AIRI_PROOF tags emitted from the native side:
    //   MMPROJ_LOADED / MMPROJ_LOAD_FAILED
    //   MMPROJ_UNLOADED
    //   MMPROJ_EVAL_OK / MMPROJ_EVAL_FAILED
    //   MMPROJ_GENERATE_DONE
    external fun loadMmproj(mmprojPath: String): Boolean
    external fun unloadMmproj()
    external fun isMmprojLoaded(): Boolean
    external fun evalImageAndGenerate(
        prompt: String,
        rgb888: ByteArray,
        width: Int,
        height: Int,
        maxNewTokens: Int
    ): String
}
