package com.airi.assistant.ai.prompt

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryStoreSummaryScopeInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun summaryAndCoverageAreIsolatedByProjectAndPrivacyCeiling() {
        val session = "summary-scope-test"
        MemoryStore.clear(context, session)
        try {
            MemoryStore.setSummaryAndCoverage(context, session, "project-a", 1, "A-private-ish", 7)
            MemoryStore.setSummaryAndCoverage(context, session, "project-b", 1, "B-summary", 4)
            MemoryStore.setSummaryAndCoverage(context, session, "project-a", 2, "A-higher-ceiling", 9)

            assertEquals("A-private-ish", MemoryStore.getSummary(context, session, "project-a", 1))
            assertEquals(7, MemoryStore.getSummaryCoverage(context, session, "project-a", 1))
            assertEquals("B-summary", MemoryStore.getSummary(context, session, "project-b", 1))
            assertEquals(4, MemoryStore.getSummaryCoverage(context, session, "project-b", 1))
            assertEquals("A-higher-ceiling", MemoryStore.getSummary(context, session, "project-a", 2))
            assertEquals(9, MemoryStore.getSummaryCoverage(context, session, "project-a", 2))

            // Legacy session-only data is never visible to scoped prompt reads.
            context.getSharedPreferences("airi_session_compress", Context.MODE_PRIVATE)
                .edit().putString("summary::$session", "legacy-unscoped").apply()
            assertEquals("A-private-ish", MemoryStore.getSummary(context, session, "project-a", 1))
            assertEquals("", MemoryStore.getSummary(context, session, "project-c", 1))
            assertEquals("", MemoryStore.getSummary(context, session, "project-a", 0))
        } finally {
            MemoryStore.clear(context, session)
        }
    }

    @Test
    fun clearSessionAndClearAllRemoveEverySummaryScope() {
        val session = "summary-clear-test"
        MemoryStore.clear(context, session)
        MemoryStore.setSummaryAndCoverage(context, session, "project-a", 1, "saved", 3)
        MemoryStore.setSummaryAndCoverage(context, session, "project-b", 2, "saved too", 5)

        MemoryStore.clear(context, session)
        assertEquals("", MemoryStore.getSummary(context, session, "project-a", 1))
        assertEquals(0, MemoryStore.getSummaryCoverage(context, session, "project-b", 2))

        MemoryStore.setSummaryAndCoverage(context, session, "project-a", 1, "saved", 3)
        MemoryStore.clearAllFacts(context)
        assertEquals("", MemoryStore.getSummary(context, session, "project-a", 1))
        assertEquals(0, MemoryStore.getSummaryCoverage(context, session, "project-a", 1))
    }
}
