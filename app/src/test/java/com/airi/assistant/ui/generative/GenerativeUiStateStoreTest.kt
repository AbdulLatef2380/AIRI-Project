package com.airi.assistant.ui.generative

import org.junit.Assert.assertEquals
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
}
