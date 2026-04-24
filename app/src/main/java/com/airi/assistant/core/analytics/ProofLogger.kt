package com.airi.assistant.core.analytics

import android.util.Log

object ProofLogger {

    private const val TAG = "AIRI_PROOF"

    fun log(event: String, data: String) {
        Log.d(TAG, "$event | $data")
    }
}
