package com.airi.assistant.ui.text

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingMarkdownPolicyTest {
    @Test fun keepsIncompleteLineOutOfMarkdownParser() {
        assertEquals(
            StreamingMarkdownPolicy.Split("# title\n", "**incomplete"),
            StreamingMarkdownPolicy.split("# title\n**incomplete"),
        )
    }

    @Test fun doesNotInventStablePrefixWithoutNewline() {
        assertEquals(
            StreamingMarkdownPolicy.Split("", "partial response"),
            StreamingMarkdownPolicy.split("partial response"),
        )
    }
}
