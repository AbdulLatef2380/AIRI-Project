package com.airi.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopShortcutPolicyTest {

    @Test
    fun `control n starts a new draft`() {
        assertEquals(
            DesktopCommand.START_NEW_DRAFT,
            DesktopShortcutPolicy.resolve("N", controlOrCommandPressed = true)
        )
    }

    @Test
    fun `control k focuses the composer`() {
        assertEquals(
            DesktopCommand.FOCUS_COMPOSER,
            DesktopShortcutPolicy.resolve("K", controlOrCommandPressed = true)
        )
    }

    @Test
    fun `escape dismisses transient UI`() {
        assertEquals(
            DesktopCommand.DISMISS_TRANSIENT_UI,
            DesktopShortcutPolicy.resolve("ESCAPE", controlOrCommandPressed = false)
        )
    }

    @Test
    fun `plain text keys are not captured as commands`() {
        assertNull(DesktopShortcutPolicy.resolve("N", controlOrCommandPressed = false))
    }
}
