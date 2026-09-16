package com.airi.assistant.ui.plan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSnapshotPolicyTest {
    private fun snapshot(
        sessionId: String = "session-1",
        goal: String = "Organize project files",
        detail: String? = null
    ) = PlanSnapshot(
        executionId = "run-1",
        goal = goal,
        sessionId = sessionId,
        projectId = "project-1",
        memoryQuery = goal,
        steps = listOf(PlanStepModel("step-1", "Inspect files", detail = detail))
    )

    @Test
    fun validSnapshotCanBeRestored() {
        assertTrue(PlanSnapshotPolicy.canRestore(snapshot()))
        assertFalse(PlanSnapshotPolicy.containsSecretMaterial(snapshot()))
    }

    @Test
    fun incompleteSnapshotCannotBeRestored() {
        assertFalse(PlanSnapshotPolicy.canRestore(snapshot(sessionId = "")))
        assertFalse(PlanSnapshotPolicy.canRestore(snapshot(goal = "")))
    }

    @Test
    fun secretMaterialIsRejectedFromSnapshot() {
        assertTrue(PlanSnapshotPolicy.containsSecretMaterial(snapshot(detail = "Authorization: Bearer token")))
        assertTrue(PlanSnapshotPolicy.containsSecretMaterial(snapshot(goal = "rotate sha256/abc pin")))
    }

    @Test
    fun snapshotCodecRestoresPlanAndMemoryContext() {
        val original = snapshot().copy(
            memoryQuery = "project deployment constraints",
            skillIds = listOf("research"),
            connectorIds = listOf("github")
        )
        val restored = PlanSnapshotCodec.decode(PlanSnapshotCodec.encode(original))
        assertTrue(restored != null)
        assertTrue(restored == original)
        assertTrue(restored!!.projectId == "project-1")
        assertTrue(restored.sessionId == "session-1")
    }
}
