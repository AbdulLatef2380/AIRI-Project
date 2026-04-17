package com.airi.assistant.core

import com.airi.assistant.domain.logging.LoggingService

object AiriLogger {

    private const val TAG = "AIRI"

    fun d(msg: String) = LoggingService.debug(TAG, msg)
    fun e(msg: String) = LoggingService.error(TAG, msg)
    fun i(msg: String) = LoggingService.info(TAG, msg)
    fun w(msg: String) = LoggingService.warn(TAG, msg)
}
