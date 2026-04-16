package com.airi.assistant.accessibility.service

object OverlayBridge {
    // المستمع الذي سيقوم الـ OverlayService بالتسجيل فيه
    var suggestionListener: ((String, String) -> Unit)? = null

    fun showSuggestion(text: String, context: String) {
        suggestionListener?.invoke(text, context)
    }
}
