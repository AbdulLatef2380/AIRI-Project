package com.airi.assistant.ai.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillDependencyReadinessTest {
    @Test
    fun checksEveryDeclaredConnectorThroughLiveReadiness() {
        val queried = mutableListOf<String>()
        val ready = SkillDependencyReadiness.areAvailable(
            dependencies = listOf("model:cloud", "connector:github", "connector:dropbox"),
            isConnectorReady = { id -> queried += id; id == "github" },
        )

        assertFalse(ready)
        assertTrue(queried.containsAll(listOf("github", "dropbox")))
    }

    @Test
    fun skillsWithoutConnectorDependenciesDoNotRequireConnectorState() {
        assertTrue(SkillDependencyReadiness.areAvailable(listOf("permission:memory")) { false })
    }

    @Test
    fun unknownConnectorDependenciesFailClosed() {
        assertFalse(SkillDependencyReadiness.areAvailable(listOf("connector:unknown")) { false })
    }

    @Test
    fun exactActionDependenciesRequireEveryDeclaredActionToBeExecutable() {
        val checked = mutableListOf<String>()
        val ready = SkillDependencyReadiness.areActionsAvailable(
            listOf("google:gmail_list", "google:gmail_read"),
        ) { connectorId, actionId ->
            checked += "$connectorId:$actionId"
            actionId == "gmail_list"
        }

        assertFalse(ready)
        assertEquals(listOf("google:gmail_list", "google:gmail_read"), checked)
        assertFalse(SkillDependencyReadiness.areActionsAvailable(listOf("telegram:")) { _, _ -> true })
    }
}
