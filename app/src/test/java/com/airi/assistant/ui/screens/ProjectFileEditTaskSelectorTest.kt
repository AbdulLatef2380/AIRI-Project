package com.airi.assistant.ui.screens

import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFileEditTaskSelectorTest {

    @Test
    fun selectsOnlyTheSingleOwnedRunningTaskWithExactRunAndStep() {
        val selected = runningTask(id = "selected", projectId = "project-a")
        val result = ProjectFileEditTaskSelector.select(
            tasks = listOf(
                selected,
                runningTask(id = "other-project", projectId = "project-b"),
                runningTask(id = "missing-step", projectId = "project-a", stepId = null),
                runningTask(id = "paused", projectId = "project-a", status = DurableTaskStatus.PAUSED)
            ),
            projectId = "project-a"
        )

        assertEquals(selected.id, result.task?.id)
        assertEquals(1, result.eligibleTaskCount)
        assertFalse(result.requiresExplicitTaskChoice)
    }

    @Test
    fun refusesToChooseArbitrarilyWhenMultipleTasksCanOwnTheEdit() {
        val result = ProjectFileEditTaskSelector.select(
            tasks = listOf(
                runningTask(id = "first", projectId = "project-a"),
                runningTask(id = "second", projectId = "project-a")
            ),
            projectId = "project-a"
        )

        assertNull(result.task)
        assertEquals(2, result.eligibleTaskCount)
        assertTrue(result.requiresExplicitTaskChoice)
    }

    @Test
    fun returnsNoTaskWhenProjectHasNoEligibleRunningOwner() {
        val result = ProjectFileEditTaskSelector.select(
            tasks = listOf(runningTask(id = "other-project", projectId = "project-b")),
            projectId = "project-a"
        )

        assertNull(result.task)
        assertEquals(0, result.eligibleTaskCount)
        assertFalse(result.requiresExplicitTaskChoice)
    }

    private fun runningTask(
        id: String,
        projectId: String,
        status: DurableTaskStatus = DurableTaskStatus.RUNNING,
        stepId: String? = "apply-edit"
    ) = DurableTask(
        id = id,
        projectId = projectId,
        missionId = "$id-mission",
        title = "Fixture task",
        description = "Fixture task",
        agentId = "fixture-agent",
        input = "Fixture input",
        status = status,
        currentRunId = "run-$id",
        currentStepId = stepId
    )
}
