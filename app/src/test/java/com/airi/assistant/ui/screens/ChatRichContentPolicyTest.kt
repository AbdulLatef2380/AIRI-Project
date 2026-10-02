package com.airi.assistant.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRichContentPolicyTest {
    @Test fun shortReplyStaysInline() {
        assertFalse(ChatRichContentPolicy.needsFullscreen("رد قصير"))
    }

    @Test fun longReplyGetsFullscreenViewer() {
        assertTrue(ChatRichContentPolicy.needsFullscreen("x".repeat(601)))
    }

    @Test fun codeBlockGetsFullscreenViewerEvenWhenShort() {
        assertTrue(ChatRichContentPolicy.needsFullscreen("```json\n{\"ok\":true}\n```") )
    }

    @Test fun structuredJsonGetsFullscreenViewerWithoutFence() {
        assertTrue(ChatRichContentPolicy.needsFullscreen("{\"model\":\"airi\"}"))
    }

    @Test fun markdownTableGetsFullscreenViewer() {
        assertTrue(ChatRichContentPolicy.needsFullscreen("| A | B |\n|---|---|\n| 1 | 2 |"))
    }
}
