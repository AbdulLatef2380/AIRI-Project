package com.airi.assistant.core

import android.util.Log

object AiriLogger {
    private const val TAG = "AIRI"

    fun d(msg: String) { Log.d(TAG, msg) }
    fun e(msg: String) { Log.e(TAG, msg) }
    fun i(msg: String) { Log.i(TAG, msg) }
}
