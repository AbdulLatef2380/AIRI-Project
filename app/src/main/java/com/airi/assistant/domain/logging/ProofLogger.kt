package com.airi.assistant.domain.logging

import android.util.Log

object ProofLogger {

    private const val TAG = "AIRI_RUNTIME"

    fun fastPathUsed(input: String) {
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "FAST_PATH_USED input_preview=\"${input.take(40)}\"")
    }

    fun streamStarted(queryType: String, model: String, tokens: Int) {
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "STREAM_STARTED queryType=$queryType model=$model maxTokens=$tokens")
    }

    fun firstToken(latencyMs: Long, queryType: String) {
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "FIRST_TOKEN latency_ms=$latencyMs queryType=$queryType")
    }

    fun streamCancelled(byUser: Boolean, tokensStreamed: Int) {
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "STREAM_CANCELLED by_user=$byUser tokens_streamed=$tokensStreamed")
    }

    fun cutTriggered(tokensStreamed: Int, elapsedMs: Long) {
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "CUT_TRIGGERED tokens_streamed=$tokensStreamed elapsed_ms=$elapsedMs")
    }

    fun paywallTriggered(reason: String, level: String) {
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "PAYWALL_TRIGGERED reason=$reason level=$level")
    }

    fun classificationResult(input: String, queryType: String, wordCount: Int) {
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "CLASSIFICATION_RESULT type=$queryType words=$wordCount input_preview=\"${input.take(40)}\"")
    }

    fun diagnosticsResult(testName: String, passed: Boolean, detail: String) {
        val outcome = if (passed) "PASS" else "FAIL"
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "DIAGNOSTICS $outcome test=\"$testName\" detail=\"$detail\"")
    }
}
