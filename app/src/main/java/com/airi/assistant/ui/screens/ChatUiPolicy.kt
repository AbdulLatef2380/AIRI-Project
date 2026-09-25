package com.airi.assistant.ui.screens

import com.airi.assistant.ui.viewmodel.ChatMessage

/** Pure UI contracts used to keep LazyColumn and Composer state deterministic. */
object ChatUiPolicy {
    const val EXPAND_AFTER_LINE_COUNT = 8

    fun shouldShowExpandAction(text: String): Boolean =
        text.lineSequence().count() >= EXPAND_AFTER_LINE_COUNT

    /**
     * Produces unique, deterministic keys even for legacy/imported messages that
     * accidentally carry the same in-memory uid. The first occurrence keeps the
     * uid unchanged; later duplicates receive an occurrence suffix.
     */
    fun messageKeys(messages: List<ChatMessage>): List<String> {
        val occurrences = HashMap<String, Int>()
        return messages.map { message ->
            val occurrence = (occurrences[message.uid] ?: 0) + 1
            occurrences[message.uid] = occurrence
            if (occurrence == 1) message.uid else "${message.uid}#$occurrence"
        }
    }
}
