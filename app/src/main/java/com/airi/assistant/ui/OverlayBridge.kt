package com.airi.assistant.ui

import android.util.Log
import com.airi.assistant.accessibility.service.OverlayBridge

object OverlayBridge {

    fun showSuggestion(suggestion: String, context: String) {
        // حالياً مجرد Log حتى نبني Overlay System لاحقاً
        Log.d("AIRI_OVERLAY", "Suggestion: $suggestion")
    }
}
