package com.airi.assistant.ui.viewmodel

/**
 * Ensures that only the latest explicit model-selection request may mutate the
 * visible model state. Native model loading is asynchronous and an older
 * callback can otherwise arrive after the user has selected another model.
 */
internal object ModelLoadRequestPolicy {
    fun shouldApply(requestId: Long, latestRequestId: Long): Boolean =
        requestId == latestRequestId
}
