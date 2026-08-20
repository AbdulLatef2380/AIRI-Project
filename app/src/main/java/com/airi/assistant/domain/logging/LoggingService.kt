package com.airi.assistant.domain.logging

import android.util.Log
import com.airi.assistant.BuildConfig

/**
 * LoggingService — release-safe centralized logging gate.
 *
 * ── PRODUCTION HARDENING ─────────────────────────────────────────────────────
 * The previous implementation unconditionally called Log.d() and Log.v(),
 * which emit debug/verbose entries in production builds. This leaks internal
 * state details into device logs, wastes battery (log writes are I/O), and
 * can expose user-context strings.
 *
 * Fix: [debug] and [logExecution] are now no-ops in release builds.
 * [info], [warn], and [error] are always emitted — they represent genuine
 * operational events that support post-incident debugging.
 *
 * R8 will inline these guards in release builds (constant folding on
 * BuildConfig.DEBUG = false), eliminating the call overhead entirely.
 */
object LoggingService {

    private const val DEFAULT_TAG = "AIRI"

    /** Debug — emitted only in debug builds. No-op in production. */
    fun debug(tag: String = DEFAULT_TAG, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    /** Info — always emitted (operational signal, not sensitive). */
    fun info(tag: String = DEFAULT_TAG, msg: String) {
        Log.i(tag, msg)
    }

    /** Warn — always emitted. */
    fun warn(tag: String = DEFAULT_TAG, msg: String) {
        Log.w(tag, msg)
    }

    /** Error — always emitted. */
    fun error(tag: String = DEFAULT_TAG, msg: String, cause: Throwable? = null) {
        if (cause != null) Log.e(tag, msg, cause) else Log.e(tag, msg)
    }

    /**
     * Execution summary — gated behind debug flag.
     * Avoid logging [input] text in production (may contain user content).
     */
    fun logExecution(
        tag: String = DEFAULT_TAG,
        input: String,
        success: Boolean,
        durationMs: Long
    ) {
        if (!BuildConfig.DEBUG) return
        val status = if (success) "SUCCESS" else "FAILURE"
        Log.d(tag, "EXECUTION status=$status inputChars=${input.length} durationMs=$durationMs")
    }
}
