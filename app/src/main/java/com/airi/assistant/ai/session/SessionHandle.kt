package com.airi.assistant.ai.session

import com.airi.assistant.ai.context.ContextBudget

/**
 * SessionHandle — explicit ownership token for one native inference session.
 *
 * SPRINT 3: Session-Aware Architecture.
 *
 * ## Problem it solves
 * The native layer (LlamaBridge.cpp) uses file-scope globals:
 *   g_llm_ctx   — the llama_context pointer
 *   g_kv_cache  — managed internally by llama_context
 *   g_n_past    — KV-cache position counter
 *   g_session_id — monotonic counter bumped on every context replacement
 *
 * Before Sprint 3 these were implicitly assumed to be "the" session — there
 * was no Kotlin object representing session ownership. Any subsystem could
 * call LlamaNative directly without knowing whether its assumed context was
 * still alive.
 *
 * ## What SessionHandle provides
 * A SessionHandle is minted after every native `beginSession()` call and
 * captures the `nativeGetSessionId()` counter at that moment. Any caller
 * holding a SessionHandle can verify with `matchesNative()` that the native
 * context hasn't been replaced under it (e.g. by a concurrent fullReset).
 *
 * ## Thread safety
 * SessionHandle is an immutable data class. It is safe to read from any
 * thread. `matchesNative()` calls into the native layer — call it only on
 * the llamaDispatcher or while holding lifecycleLock.
 *
 * ## Future multi-agent path (Sprint 4 design)
 * When multiple native contexts exist (one per agent), each agent will hold
 * a distinct SessionHandle. The native bridge will route JNI calls by
 * `sessionId`, eliminating the global-context assumption. No change to the
 * SessionHandle API will be required — only the native routing layer changes.
 *
 * ## Current behaviour
 * Single-session only. Behaviour is identical to before Sprint 3 — the handle
 * is checked but there is only ever one session so it always matches.
 * No user-visible change.
 */
data class SessionHandle(
    /**
     * Monotonic counter from LlamaNative.nativeGetSessionId().
     * Bumped on every event that creates/replaces/wipes the llama_context:
     * loadModel, loadModelWithProgress, setRuntimeMode, nativeFullReset,
     * beginSession, resetSession.
     */
    val sessionId: Long,

    /** ContextBudget captured when this session was opened. */
    val contextBudget: ContextBudget,

    /** Model path this session was opened for (for diagnostics). */
    val modelPath: String,

    /** Wall-clock timestamp when the session was created (for diagnostics). */
    val openedAtMs: Long = System.currentTimeMillis()
) {
    /** True when this handle was minted from a real native session. */
    val isValid: Boolean get() = sessionId >= 0

    /**
     * Returns true when the native session counter still matches the captured
     * [sessionId]. A mismatch means the native context was replaced (fullReset,
     * model swap, or setRuntimeMode) while this handle was outstanding.
     *
     * Must be called on the llamaDispatcher or while holding lifecycleLock.
     */
    fun matchesNative(): Boolean = try {
        com.airi.assistant.ai.LlamaNative.nativeGetSessionId() == sessionId
    } catch (e: Throwable) {
        false
    }

    fun toLogString(): String =
        "SessionHandle id=$sessionId model=${modelPath.substringAfterLast('/')} " +
        "budget=${contextBudget.nCtx} valid=$isValid openedAt=$openedAtMs"

    companion object {
        /**
         * Sentinel value — used before the first session is established or
         * after session invalidation. [isValid] returns false.
         */
        val NONE = SessionHandle(
            sessionId     = -1L,
            contextBudget = ContextBudget.UNLOADED,
            modelPath     = ""
        )
    }
}
