package com.airi.assistant.core

import android.util.Log

object AiriLogger {
    private const val TAG = "AIRI"

    fun d(msg: String) {
        Log.d(TAG, msg)
        logToCrashlytics("D", msg)
    }

    fun e(msg: String, throwable: Throwable? = null) {
        Log.e(TAG, msg, throwable)
        logToCrashlytics("E", msg)
        throwable?.let { recordException(it) }
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        logToCrashlytics("I", msg)
    }

    fun skill(skillName: String, input: String, success: Boolean) {
        val msg = "Skill[$skillName] success=$success input=${input.take(80)}"
        Log.d(TAG, msg)
        logToCrashlytics("SKILL", msg)
    }

    fun agent(step: String, detail: String = "") {
        val msg = "Agent[$step] $detail"
        Log.d(TAG, msg)
        logToCrashlytics("AGENT", msg)
    }

    fun apiFail(api: String, error: String) {
        val msg = "ApiFail[$api] $error"
        Log.w(TAG, msg)
        logToCrashlytics("API_FAIL", msg)
    }

    private fun logToCrashlytics(level: String, msg: String) {
        try {
            com.google.firebase.crashlytics.ktx.crashlytics.log("$level: $msg")
        } catch (_: Exception) {
        }
    }

    private fun recordException(throwable: Throwable) {
        try {
            com.google.firebase.crashlytics.ktx.crashlytics.recordException(throwable)
        } catch (_: Exception) {
        }
    }
}
