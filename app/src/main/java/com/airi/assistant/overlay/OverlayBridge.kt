package com.airi.assistant.overlay

import android.util.Log

object OverlayBridge {

    fun showSuggestion(suggestion: String, context: String) {
        // حالياً مجرد Log حتى نبني Overlay System لاحقاً
        Log.d("AIRI_OVERLAY", "Suggestion: $suggestion")
    }
}
