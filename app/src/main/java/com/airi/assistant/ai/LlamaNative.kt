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
        } catch (e: UnsatisfiedLinkError) {
            available = false
            loadFailure = e.message
            Log.e("LlamaNative", "Native library airi_native not found: ${e.message}", e)
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
}
