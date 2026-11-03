package com.airi.assistant.accessibility.security

import kotlinx.coroutines.CancellationException

/**
 * P4 — explicit approval before the `read_screen` tool reads another app's screen.
 *
 * What the tool reads becomes part of the model context and, with a cloud model, is sent to the
 * AI provider. The UI registers [approver] while a chat is on screen; it shows what will be read
 * and returns the user's decision. With no approver (background work, no screen) the read is
 * refused — fail-closed. An approval covers that single read only.
 */
object ScreenReadConsent {

    @Volatile
    var approver: (suspend (packageName: String) -> Boolean)? = null

    suspend fun request(packageName: String): Boolean {
        val ask = approver ?: return false
        return try {
            ask(packageName)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
