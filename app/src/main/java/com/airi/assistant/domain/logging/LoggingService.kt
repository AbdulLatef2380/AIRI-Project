package com.airi.assistant.domain.logging

import android.util.Log

object LoggingService {

    private const val DEFAULT_TAG = "AIRI"

    fun debug(tag: String = DEFAULT_TAG, msg: String) {
        Log.d(tag, msg)
    }

    fun info(tag: String = DEFAULT_TAG, msg: String) {
        Log.i(tag, msg)
    }

    fun warn(tag: String = DEFAULT_TAG, msg: String) {
        Log.w(tag, msg)
    }

    fun error(tag: String = DEFAULT_TAG, msg: String, cause: Throwable? = null) {
        if (cause != null) Log.e(tag, msg, cause) else Log.e(tag, msg)
    }

    fun logExecution(
        tag: String = DEFAULT_TAG,
        input: String,
        success: Boolean,
        durationMs: Long
    ) {
        val status = if (success) "✓" else "✗"
        Log.d(tag, "$status Executed '${input.take(80)}' in ${durationMs}ms")
    }
}
