package com.airi.assistant.core.analytics

import android.util.Log

object ProofLogger {

    private const val TAG = "AIRI"

    fun log(event: String, data: String) {
        Log.d(TAG, "$event | $data")
    }
}
