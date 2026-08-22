package com.airi.assistant.execution.privacy

import com.airi.assistant.execution.ExecutionRequest

/**
 * Result of a [PrivacyGuard.evaluate] call.
 *
 *  - [Allowed] — the request may proceed to the cloud backend. The [sanitized]
 *    request must be used instead of the original; it contains cleaned prompt,
 *    system prompt, and conversation history. [strippedItems] contains only
 *    redaction categories for audit logging, never matched raw values.
 *
 *  - [Blocked] — the request must NOT reach the cloud. The caller should
 *    either route to the local backend or return an explicit error to the user.
 */
sealed class SanitizationResult {

    data class Allowed(
        val sanitized:     ExecutionRequest,
        val strippedItems: List<String>     // redaction categories only; never raw content
    ) : SanitizationResult() {
        val wasSanitized: Boolean get() = strippedItems.isNotEmpty()
    }

    data class Blocked(
        val reason: String
    ) : SanitizationResult()

    val isAllowed: Boolean get() = this is Allowed
    val isBlocked: Boolean get() = this is Blocked
}
