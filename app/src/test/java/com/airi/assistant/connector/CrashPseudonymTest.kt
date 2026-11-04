package com.airi.assistant.connector

import com.airi.assistant.crash.FirebaseCrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CrashPseudonymTest {
    @Test fun neverContainsTheUid() {
        val out = FirebaseCrashReporter.pseudonymize("firebase-uid-12345")
        assertFalse(out.contains("firebase-uid-12345"))
        assertEquals(21, out.length) // "anon-" + 16 hex
    }

    @Test fun stablePerAccountAndDifferentAcrossAccounts() {
        assertEquals(FirebaseCrashReporter.pseudonymize("a"), FirebaseCrashReporter.pseudonymize("a"))
        assertNotEquals(FirebaseCrashReporter.pseudonymize("a"), FirebaseCrashReporter.pseudonymize("b"))
    }

    @Test fun blankStaysBlank() = assertEquals("", FirebaseCrashReporter.pseudonymize("  "))
}
