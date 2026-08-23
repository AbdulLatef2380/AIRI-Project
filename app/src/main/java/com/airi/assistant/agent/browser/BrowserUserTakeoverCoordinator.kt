package com.airi.assistant.agent.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The only handoff boundary allowed to launch a public browser from AIRI.
 * Agents can offer a request but cannot open it. The Compose host must display
 * the request and call [confirm] only after a visible, user-initiated choice.
 */
object BrowserUserTakeoverCoordinator {

    data class Request internal constructor(
        val id: String,
        val normalizedUrl: String,
        val reason: String
    )

    enum class ConfirmResult {
        OPENED,
        NOT_PENDING,
        BLOCKED_ON_REVALIDATION,
        NO_HANDLER
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /**
     * Admits only public URLs that still require explicit user takeover. The
     * URL is normalized by [BrowserNavigationPolicy] before it reaches UI.
     */
    fun request(rawUrl: String, reason: String): Request? {
        val decision = BrowserNavigationPolicy.evaluate(
            rawUrl,
            BrowserNavigationPolicy.Operation.OPEN_EXTERNAL
        ) as? BrowserNavigationPolicy.Decision.RequiresUserTakeover ?: return null
        val existing = _pending.value
        if (existing?.normalizedUrl == decision.normalizedUrl) return existing
        return Request(
            id = UUID.randomUUID().toString(),
            normalizedUrl = decision.normalizedUrl,
            reason = decision.reason.ifBlank { reason }.take(MAX_REASON_LENGTH)
        ).also { _pending.value = it }
    }

    fun dismiss(requestId: String) {
        if (_pending.value?.id == requestId) _pending.value = null
    }

    /**
     * Revalidates the request and launches the platform browser once. This is
     * intentionally an Android UI-bound action, not a sub-agent operation.
     */
    fun confirm(context: Context, requestId: String): ConfirmResult {
        val request = _pending.value?.takeIf { it.id == requestId }
            ?: return ConfirmResult.NOT_PENDING
        _pending.value = null
        val decision = BrowserNavigationPolicy.evaluate(
            request.normalizedUrl,
            BrowserNavigationPolicy.Operation.OPEN_EXTERNAL
        ) as? BrowserNavigationPolicy.Decision.RequiresUserTakeover
            ?: return ConfirmResult.BLOCKED_ON_REVALIDATION
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(decision.normalizedUrl)).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ConfirmResult.OPENED
        } catch (_: ActivityNotFoundException) {
            ConfirmResult.NO_HANDLER
        } catch (_: SecurityException) {
            ConfirmResult.NO_HANDLER
        }
    }

    private const val MAX_REASON_LENGTH = 240
}
