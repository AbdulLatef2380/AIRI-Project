package com.airi.assistant.core

object ScreenHasher {

    private var lastHash = 0

    fun isNewScreen(text: String): Boolean {
        val hash = text.hashCode()

        if (hash == lastHash) {
            return false
        }

        lastHash = hash
        return true
    }
}
