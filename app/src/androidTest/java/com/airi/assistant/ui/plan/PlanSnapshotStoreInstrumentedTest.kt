package com.airi.assistant.ui.plan

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanSnapshotStoreInstrumentedTest {
    @Test
    fun snapshotSurvivesNewStoreInstanceAndRestoresMemoryContext() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = PlanSnapshotStore(context)
        val snapshot = PlanSnapshot(
            executionId = "instrumented-run",
            goal = "Restore active task",
            sessionId = "session-restore",
            projectId = "project-restore",
            memoryQuery = "remember project constraints",
            skillIds = listOf("research"),
            connectorIds = listOf("github"),
            steps = listOf(PlanStepModel("step-1", "Load persisted plan"))
        )
        first.save(snapshot)

        val restored = PlanSnapshotStore(context).load()
        assertNotNull(restored)
        assertEquals(snapshot, restored)
        assertTrue(PlanSnapshotPolicy.canRestore(restored!!))
        assertTrue(restored.sessionId == "session-restore")
        assertTrue(restored.projectId == "project-restore")

        first.clear()
    }
}
