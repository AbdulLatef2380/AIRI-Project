package com.airi.desktop

enum class DesktopCommand {
    START_NEW_DRAFT,
    FOCUS_COMPOSER,
    DISMISS_TRANSIENT_UI
}

object DesktopShortcutPolicy {

    fun resolve(key: String, controlOrCommandPressed: Boolean): DesktopCommand? = when {
        controlOrCommandPressed && key.equals("N", ignoreCase = true) -> DesktopCommand.START_NEW_DRAFT
        controlOrCommandPressed && key.equals("K", ignoreCase = true) -> DesktopCommand.FOCUS_COMPOSER
        key.equals("ESCAPE", ignoreCase = true) -> DesktopCommand.DISMISS_TRANSIENT_UI
        else -> null
    }
}
