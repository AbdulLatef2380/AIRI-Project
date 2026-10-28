package com.airi.assistant.ui.text

import kotlin.system.measureTimeMillis
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard for the parsing/reconciliation path used by streaming ARPE. */
class AdaptiveContentPerformanceTest {
    @Test
    fun longStreamingReplyRemainsBounded() {
        val session = AdaptiveContentSession("perf-message")
        val base = buildString {
            repeat(80) { index ->
                append("paragraph $index\n")
                append("```kotlin\nval value$index = $index\n```\n")
            }
        }
        val elapsedMs = measureTimeMillis {
            repeat(12) { pass ->
                session.plan(
                    text = if (pass == 0) base else "new lead $pass\n$base",
                    isStreaming = pass < 11,
                )
            }
        }
        assertTrue("ARPE parsing took ${elapsedMs}ms", elapsedMs < 5_000L)
    }
}
