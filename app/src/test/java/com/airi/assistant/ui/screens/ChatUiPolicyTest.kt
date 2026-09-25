package com.airi.assistant.ui.screens

import com.airi.assistant.ui.viewmodel.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiPolicyTest {
    @Test
    fun duplicateMessageUidsBecomeUniqueLazyListKeys() {
        val messages = listOf(
            ChatMessage(text = "same", isUser = true, uid = "legacy-uid"),
            ChatMessage(text = "same", isUser = true, uid = "legacy-uid"),
            ChatMessage(text = "different", isUser = false, uid = "other")
        )

        val keys = ChatUiPolicy.messageKeys(messages)

        assertEquals(listOf("legacy-uid", "legacy-uid#2", "other"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun uniqueMessageUidsRemainStableForComposeState() {
        val messages = listOf(
            ChatMessage(text = "one", isUser = true, uid = "a"),
            ChatMessage(text = "two", isUser = false, uid = "b")
        )

        assertEquals(listOf("a", "b"), ChatUiPolicy.messageKeys(messages))
    }

    @Test
    fun expandActionStartsAtEightLinesNotEarlier() {
        assertFalse(ChatUiPolicy.shouldShowExpandAction("one\ntwo\nthree\nfour\nfive\nsix\nseven"))
        assertTrue(ChatUiPolicy.shouldShowExpandAction((1..8).joinToString("\n") { "line $it" }))
        assertTrue(ChatUiPolicy.shouldShowExpandAction((1..20).joinToString("\n") { "line $it" }))
    }
}
