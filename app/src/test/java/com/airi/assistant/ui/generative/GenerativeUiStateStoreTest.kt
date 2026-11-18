package com.airi.assistant.ui.generative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativeUiStateStoreTest {
    @Test
    fun stateSurvivesIndependentReadsAndIsBounded() {
        val store = GenerativeUiStateStore()
        store.update("message:form") { it.copy(formValues = mapOf("name" to "A"), submitted = true) }

        val restored = store.state("message:form")
        assertEquals("A", restored.formValues["name"])
        assertTrue(restored.submitted)
    }

    @Test
    fun valuesAreTruncatedBeforeRetention() {
        val store = GenerativeUiStateStore()
        val longValue = "x".repeat(700)
        store.update("message:form") { it.copy(formValues = mapOf("field" to longValue)) }

        assertEquals(500, store.state("message:form").formValues.getValue("field").length)
    }

    @Test
    fun sameBlockIdInTwoMessagesNeverSharesState() {
        val store = GenerativeUiStateStore()
        store.update(GenerativeUiStateStore.key("1", "chart")) { it.copy(selectedIndex = 2) }
        assertEquals(-1, store.state(GenerativeUiStateStore.key("2", "chart")).selectedIndex)
        assertEquals(2, store.state(GenerativeUiStateStore.key("1", "chart")).selectedIndex)
    }

    @Test
    fun removingAMessageDropsOnlyItsState() {
        val store = GenerativeUiStateStore()
        store.update(GenerativeUiStateStore.key("1", "form")) { it.copy(submitted = true) }
        store.update(GenerativeUiStateStore.key("12", "form")) { it.copy(submitted = true) }
        store.removeMessage("1")
        assertFalse(store.state(GenerativeUiStateStore.key("1", "form")).submitted)
        assertTrue(store.state(GenerativeUiStateStore.key("12", "form")).submitted)
    }

    @Test
    fun actionLifecycleIsScopedAndRemovedWithItsMessage() {
        val store = GenerativeUiStateStore()
        store.setActionPhase("1", "send", GenerativeUiStateStore.ActionPhase.RUNNING)
        store.setActionPhase("12", "send", GenerativeUiStateStore.ActionPhase.SUCCEEDED)

        assertEquals(GenerativeUiStateStore.ActionPhase.RUNNING, store.actionPhase("1", "send"))
        assertEquals(GenerativeUiStateStore.ActionPhase.SUCCEEDED, store.actionPhase("12", "send"))
        store.removeMessage("1")
        assertEquals(null, store.actionPhase("1", "send"))
        assertEquals(GenerativeUiStateStore.ActionPhase.SUCCEEDED, store.actionPhase("12", "send"))
    }

    @Test
    fun evictionRemovesTheLeastRecentlyUsedEntry() {
        val store = GenerativeUiStateStore()
        store.update("keep:form") { it.copy(submitted = true) }
        repeat(300) { index ->
            store.update("m$index:b") { it.copy(submitted = true) }
            store.state("keep:form") // keeps the in-use entry fresh
        }
        assertTrue(store.state("keep:form").submitted)
        assertTrue(store.size() <= 256)
    }
}
