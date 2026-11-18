package com.airi.assistant.ui.generative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingGenerativeUiActionGateTest {
    private val action = GenerativeUiAction("details", mapOf("prompt" to "explain"), "exec-1", "42")

    @Test
    fun submittingTheStagedTextClaimsTheActionOnce() {
        val gate = PendingGenerativeUiActionGate()
        gate.stage(action)
        assertEquals(action, gate.claim("  ${action.prompt}  "))
        assertNull(gate.claim(action.prompt))
    }

    @Test
    fun editedTextNeverClaimsTheAction() {
        val gate = PendingGenerativeUiActionGate()
        gate.stage(action)
        assertNull(gate.claim(action.prompt + " plus my own words"))
        assertFalse(gate.hasPending())
    }

    @Test
    fun composerEditAndSessionChangeDropTheStagedAction() {
        val gate = PendingGenerativeUiActionGate()
        gate.stage(action)
        gate.onComposerTextChanged(action.prompt)
        assertTrue(gate.hasPending())
        gate.onComposerTextChanged("something else")
        assertFalse(gate.hasPending())

        gate.stage(action)
        gate.clear()
        assertNull(gate.claim(action.prompt))
    }
}
